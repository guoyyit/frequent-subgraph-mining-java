package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.github.guoyyit.frequentsubgraphminingjava.durability.DurableEvent;
import com.github.guoyyit.frequentsubgraphminingjava.durability.DurabilityStore;
import com.github.guoyyit.frequentsubgraphminingjava.durability.GraphCodec;
import com.github.guoyyit.frequentsubgraphminingjava.durability.SnapshotData;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternIndex;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternKeyFilters;
import com.github.guoyyit.frequentsubgraphminingjava.mining.SingleGraphPatterns;
import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.model.MutationType;
import com.github.guoyyit.frequentsubgraphminingjava.model.Pattern;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local frequent-subgraph service with incremental updates, snapshot isolation and
 * crash recovery.
 *
 * <h2>Concurrency</h2>
 * Committed state ({@link Snapshot}) is immutable. Mutations take an exclusive
 * {@link ReentrantLock}; queries never take the lock and instead read the current
 * immutable snapshot reference. A query therefore observes either the complete
 * pre-mutation state or the complete post-mutation state — never a partial mix.
 *
 * <h2>Durability</h2>
 * A mutation is committed only after its event has been appended and fsynced to the
 * WAL; only then is the new snapshot published. On startup the latest snapshot is
 * loaded and subsequent WAL events replayed, recreating the exact committed state
 * (same graph set and sequence), so no change is lost and none is double-counted.
 *
 * <h2>Incremental maintenance</h2>
 * The committed snapshot carries a {@link PatternIndex} built without support or
 * edge-count pruning. A mutation re-enumerates only the affected graph and replaces
 * its postings; {@link #query} filters by support/size/labels. Hence query results
 * are bit-for-bit consistent with a full re-mining of the current collection.
 */
public final class FrequentSubgraphService {

    /**
     * Hard per-graph budget for the maintained index. Bounds both enumeration work and
     * the number of distinct patterns retained per graph, so a pathological single
     * graph can never hang commits or exhaust memory; graphs exceeding it are flagged
     * in the snapshot and reported as truncated by queries.
     */
    static final MiningBudget MAINTENANCE_BUDGET = new MiningBudget(50_000, 50_000, 3_000L);

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.empty();
    private DurabilityStore store;
    private Path dataDir;
    private long snapshotEvery = 100;

    public FrequentSubgraphService() {
    }

    public FrequentSubgraphService(Path dataDir, long snapshotEvery) {
        this.dataDir = dataDir;
        this.snapshotEvery = snapshotEvery;
    }

    // ---------- lifecycle ----------

    /** Initialize durability and recover the last consistent committed state. */
    public void start() {
        if (dataDir != null && store == null) {
            store = new DurabilityStore(dataDir);
        }
        if (store == null) {
            this.snapshot = Snapshot.empty();
            return;
        }
        try {
            GraphCodec.SnapshotParsed parsed = store.readSnapshot();
            TreeMap<String, Graph> graphs = new TreeMap<>();
            long lastSeq = 0;
            if (parsed != null) {
                lastSeq = parsed.lastSequence();
                graphs.putAll(parsed.graphs());
            }
            // Rebuild the bounded index from the snapshot graph set.
            RebuiltIndex rebuilt = buildIndex(graphs);
            Snapshot current = new Snapshot(lastSeq,
                    Collections.unmodifiableMap(graphs), rebuilt.index(), rebuilt.truncated());
            for (DurableEvent event : store.readWal()) {
                if (event.sequence() <= lastSeq) {
                    continue;
                }
                current = applyMutation(current, event);
            }
            this.snapshot = current;
        } catch (IOException e) {
            throw new UncheckedIOException("recovery failed", e);
        }
    }

    // ---------- mutations ----------

    public void upsertGraph(Graph graph) {
        if (graph == null || graph.getId() == null) {
            throw new IllegalArgumentException("graph and its id must not be null");
        }
        commit(MutationType.UPSERT, graph.getId(), graph);
    }

    public void deleteGraph(String graphId) {
        if (graphId == null) {
            throw new IllegalArgumentException("graphId must not be null");
        }
        commit(MutationType.DELETE, graphId, null);
    }

    private void commit(MutationType type, String graphId, Graph graph) {
        writeLock.lock();
        try {
            long seq = snapshot.sequence() + 1;
            DurableEvent event = new DurableEvent(seq, type, graphId,
                    System.currentTimeMillis(), graph);
            if (store != null) {
                // Durable first: crash before this returns means "not committed".
                store.appendEvent(event);
            }
            Snapshot next = applyMutation(snapshot, event);
            // Publish only after durability: readers see the whole change atomically.
            this.snapshot = next;
            maybeSnapshot(seq);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to commit mutation", e);
        } finally {
            writeLock.unlock();
        }
    }

    private Snapshot applyMutation(Snapshot current, DurableEvent event) {
        TreeMap<String, Graph> graphs = new TreeMap<>(current.graphs());
        Set<String> truncated = new HashSet<>(current.truncatedGraphs());
        PatternIndex index = current.index();
        if (event.type() == MutationType.DELETE) {
            graphs.remove(event.graphId());
            truncated.remove(event.graphId());
            index = index.withoutGraph(event.graphId());
        } else {
            Graph graph = event.graph();
            graphs.put(event.graphId(), graph);
            SingleGraphPatterns enumerator = new SingleGraphPatterns();
            Map<Pattern, List<Embedding>> enumerated =
                    enumerator.enumerate(graph, MiningConfig.unpruned(), MAINTENANCE_BUDGET);
            index = index.withGraphReplaced(graph, enumerated);
            if (enumerator.limitReached()) {
                truncated.add(event.graphId());
            } else {
                truncated.remove(event.graphId());
            }
        }
        return new Snapshot(event.sequence(),
                Collections.unmodifiableMap(graphs), index,
                Collections.unmodifiableSet(truncated));
    }

    private void maybeSnapshot(long seq) {
        if (store == null || snapshotEvery <= 0) {
            return;
        }
        if (seq % snapshotEvery != 0) {
            return;
        }
        try {
            Snapshot current = this.snapshot;
            store.writeSnapshot(new SnapshotData(seq, current.graphs()));
            store.truncateWal(0);
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot write failed", e);
        }
    }

    // ---------- queries ----------

    /**
     * Mine the current committed snapshot. Filters: min support, max edge count and
     * optional label whitelists (all must match; edge matching is directed and exact).
     */
    public MiningResult query(MiningConfig config, MiningBudget budget) {
        Snapshot current = this.snapshot;
        MiningConfig effective = config == null ? MiningConfig.unpruned() : config;
        MiningBudget effBudget = budget == null ? MiningBudget.unbounded() : budget;
        long start = System.nanoTime();

        List<MiningResult.PatternMatch> matches = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        String reason = null;

        for (Pattern pattern : current.index().allPatterns()) {
            scanned++;
            if (pattern.getEdgeCount() > effective.getMaxEdges()) {
                continue;
            }
            if (!patternAllowedByIndex(pattern, current.index(), effective)) {
                continue;
            }
            Map<String, List<Embedding>> byGraph = current.index().embeddingsByGraph(pattern);
            int support = byGraph.size();
            if (support < effective.getMinSupport()) {
                continue;
            }
            List<Embedding> all = new ArrayList<>();
            byGraph.values().forEach(all::addAll);
            matches.add(new MiningResult.PatternMatch(pattern, support, List.copyOf(all)));
            if (matches.size() >= effBudget.maxResultPatterns()) {
                truncated = true;
                reason = "maxResultPatterns reached";
                break;
            }
            if ((scanned & 1023) == 0 && elapsed(start) > effBudget.timeoutMillis()) {
                truncated = true;
                reason = "timeout reached";
                break;
            }
        }
        if (!truncated && scanned > effBudget.maxCandidates()) {
            truncated = true;
            reason = "maxCandidates reached";
        }

        if (!truncated && !current.truncatedGraphs().isEmpty()) {
            truncated = true;
            reason = "index maintenance truncated for graph(s): "
                    + String.join(",", new java.util.TreeSet<>(current.truncatedGraphs()))
                    + "; counts may be partial";
        }

        long elapsed = elapsed(start);
        return new MiningResult(List.copyOf(matches), current.graphs().size(), effective,
                truncated, reason, scanned, elapsed);
    }

    public Map<String, Graph> currentGraphs() {
        return snapshot.graphs();
    }

    public long currentVersion() {
        return snapshot.sequence();
    }

    // visible for tests / configuration
    void bindDurability(Path dir, long snapshotEvery) {
        this.dataDir = dir;
        this.snapshotEvery = snapshotEvery;
        this.store = new DurabilityStore(dir);
    }

    // ---------- internals ----------

    private static boolean patternAllowedByIndex(Pattern pattern,
                                                 PatternIndex index,
                                                 MiningConfig config) {
        if (config.getAllowedNodeLabels() == null || config.getAllowedNodeLabels().isEmpty()) {
            if (config.getAllowedEdgeLabels() == null || config.getAllowedEdgeLabels().isEmpty()) {
                return true;
            }
        }
        // Cheaper: inspect embeddings is not needed; decode labels from the key.
        return PatternKeyFilters.matches(pattern.getCanonicalKey(),
                config.getAllowedNodeLabels(), config.getAllowedEdgeLabels());
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static RebuiltIndex buildIndex(Map<String, Graph> graphs) {
        PatternIndex index = new PatternIndex();
        Set<String> truncated = new HashSet<>();
        SingleGraphPatterns enumerator = new SingleGraphPatterns();
        for (Map.Entry<String, Graph> e : new TreeMap<>(graphs).entrySet()) {
            Map<Pattern, List<Embedding>> enumerated =
                    enumerator.enumerate(e.getValue(), MiningConfig.unpruned(),
                            MAINTENANCE_BUDGET);
            index = index.withGraphReplaced(e.getValue(), enumerated);
            if (enumerator.limitReached()) {
                truncated.add(e.getKey());
            }
        }
        return new RebuiltIndex(index, Collections.unmodifiableSet(truncated));
    }

    private record RebuiltIndex(PatternIndex index, Set<String> truncated) {
    }

    /** Immutable committed state. */
    private record Snapshot(long sequence, Map<String, Graph> graphs, PatternIndex index,
                            Set<String> truncatedGraphs) {
        static Snapshot empty() {
            return new Snapshot(0, Map.of(), new PatternIndex(), Set.of());
        }
    }
}
