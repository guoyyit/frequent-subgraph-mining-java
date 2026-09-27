package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.Pattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Enumerates every distinct connected directed labeled pattern contained in a single
 * host graph (patterns with 1..{@code maxEdges} edges), together with all distinct
 * node-mapping embeddings.
 *
 * <p>The enumeration performs a breadth-first growth over connected edge subsets:
 * every state is a connected set of edge indices; a child adds one edge incident to
 * the current node set. Each connected edge subset is therefore generated exactly
 * once (the global visited-set guarantees no duplicates); canonicalization collapses
 * isomorphic edge subsets into the same {@link Pattern}. Extending from every
 * embedding of a pattern (rather than a single representative) guarantees
 * completeness of the growth.
 *
 * <p>Hard budgets (candidate count, result pattern count, wall time) terminate
 * enumeration early; {@link #limitReached()} then reports truncation.
 */
public final class SingleGraphPatterns {

    private boolean limitReached;

    public Map<Pattern, List<Embedding>> enumerate(Graph graph,
                                                   MiningConfig config,
                                                   MiningBudget budget) {
        this.limitReached = false;
        long deadlineNanos = budget.timeoutMillis() == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + budget.timeoutMillis() * 1_000_000L;

        Map<Pattern, Set<Map<Integer, String>>> embeddingsByPattern = new TreeMap<>();
        Map<String, String> nodeLabels = graph.getNodeLabels();

        // Eligible edges: label whitelist and endpoint node-label whitelist.
        List<Graph.Edge> eligible = new ArrayList<>();
        for (Graph.Edge edge : graph.getEdges()) {
            if (labelAllowed(config.getAllowedEdgeLabels(), edge.label())
                    && nodeAllowed(config, nodeLabels, edge.source())
                    && nodeAllowed(config, nodeLabels, edge.target())) {
                eligible.add(edge);
            }
        }

        // Adjacency: host node id -> indices of eligible edges incident to it.
        Map<String, List<Integer>> incident = new java.util.HashMap<>();
        for (int i = 0; i < eligible.size(); i++) {
            Graph.Edge e = eligible.get(i);
            incident.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(i);
            incident.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(i);
        }

        Set<BitSet> visitedEdgeSets = new HashSet<>();
        Deque<State> queue = new ArrayDeque<>();
        long candidates = 0;

        // Seeds of size 0 edges: one pattern per eligible node label, one embedding per
        // distinct node. Connected multi-node patterns are grown from edge seeds below.
        java.util.SortedSet<String> allNodes = new java.util.TreeSet<>(nodeLabels.keySet());
        for (Graph.Edge e : eligible) {
            allNodes.add(e.source());
            allNodes.add(e.target());
        }
        for (String nodeId : allNodes) {
            if (!nodeAllowed(config, nodeLabels, nodeId)) {
                continue;
            }
            PatternCodec.Canonical c = PatternCodec.encode(
                    List.of(nodeId), List.of(), nodeLabels);
            Pattern nodePattern = new Pattern(c.getKey(), 1, 0);
            embeddingsByPattern.computeIfAbsent(nodePattern, k -> new LinkedHashSet<>())
                    .add(c.getMapping());
        }

        // Seeds: one connected edge set per eligible edge.
        for (int i = 0; i < eligible.size(); i++) {
            BitSet seed = new BitSet().copyWith(i);
            if (!visitedEdgeSets.add(seed)) {
                continue;
            }
            State state = buildState(graph, seed, eligible);
            queue.add(state);
            if (++candidates > budget.maxCandidates() || budgetExceeded(candidates, deadlineNanos)) {
                this.limitReached = true;
                break;
            }
        }

        while (!queue.isEmpty()) {
            State state = queue.poll();
            PatternCanonical pc = canonicalize(state, nodeLabels);
            Set<Map<Integer, String>> embeddings =
                    embeddingsByPattern.computeIfAbsent(pc.pattern(), k -> new LinkedHashSet<>());
            embeddings.add(pc.mapping());
            if (embeddingsByPattern.size() > budget.maxResultPatterns()) {
                this.limitReached = true;
                break;
            }

            if (state.edgeSet().size() >= config.getMaxEdges()) {
                continue;
            }
            Set<Integer> frontier = new HashSet<>();
            for (String node : state.nodes()) {
                List<Integer> ids = incident.get(node);
                if (ids != null) {
                    frontier.addAll(ids);
                }
            }
            for (int edgeIdx : frontier) {
                if (state.edgeSet().contains(edgeIdx)) {
                    continue;
                }
                BitSet childSet = state.edgeSet().copyWith(edgeIdx);
                if (!visitedEdgeSets.add(childSet)) {
                    continue;
                }
                queue.add(buildState(graph, childSet, eligible));
                if (++candidates > budget.maxCandidates() || budgetExceeded(candidates, deadlineNanos)) {
                    this.limitReached = true;
                    return freeze(embeddingsByPattern, graph);
                }
            }
        }
        return freeze(embeddingsByPattern, graph);
    }

    public boolean limitReached() {
        return limitReached;
    }

    private static boolean budgetExceeded(long candidates, long deadlineNanos) {
        return (candidates & 1023L) == 0L && System.nanoTime() > deadlineNanos;
    }

    private static Map<Pattern, List<Embedding>> freeze(
            Map<Pattern, Set<Map<Integer, String>>> embeddingsByPattern, Graph graph) {
        Map<Pattern, List<Embedding>> out = new TreeMap<>();
        for (Map.Entry<Pattern, Set<Map<Integer, String>>> e : embeddingsByPattern.entrySet()) {
            List<Map<Integer, String>> maps = new ArrayList<>(e.getValue());
            // Deterministic embedding order.
            maps.sort((a, b) -> mapKey(a).compareTo(mapKey(b)));
            List<Embedding> embeddings = new ArrayList<>(maps.size());
            for (Map<Integer, String> map : maps) {
                embeddings.add(new Embedding(graph.getId(), map));
            }
            out.put(e.getKey(), List.copyOf(embeddings));
        }
        return Map.copyOf(out);
    }

    private static String mapKey(Map<Integer, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : new TreeMap<>(map).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private static PatternCanonical canonicalize(State state, Map<String, String> nodeLabels) {
        List<PatternCodec.RawEdge> raw = new ArrayList<>(state.edges().size());
        for (Graph.Edge e : state.edges()) {
            raw.add(new PatternCodec.RawEdge(e.source(), e.target(), e.label()));
        }
        PatternCodec.Canonical canonical = PatternCodec.encode(state.nodes(), raw, nodeLabels);
        Pattern pattern = new Pattern(canonical.getKey(), state.nodes().size(), state.edges().size());
        return new PatternCanonical(pattern, canonical.getMapping());
    }

    private static State buildState(Graph graph, BitSet edgeSet, List<Graph.Edge> eligible) {
        Set<String> nodeSet = new LinkedHashSet<>();
        List<Graph.Edge> edges = new ArrayList<>();
        for (int idx : edgeSet.sortedValues()) {
            Graph.Edge e = eligible.get(idx);
            edges.add(e);
            nodeSet.add(e.source());
            nodeSet.add(e.target());
        }
        return new State(edgeSet, List.copyOf(nodeSet), List.copyOf(edges));
    }

    private static boolean labelAllowed(Set<String> allowed, String label) {
        return allowed == null || allowed.isEmpty() || allowed.contains(label);
    }

    private static boolean nodeAllowed(MiningConfig config, Map<String, String> nodeLabels, String nodeId) {
        return labelAllowed(config.getAllowedNodeLabels(), nodeLabels.getOrDefault(nodeId, ""));
    }

    private record State(BitSet edgeSet, List<String> nodes, List<Graph.Edge> edges) {
    }

    private record PatternCanonical(Pattern pattern, Map<Integer, String> mapping) {
    }

    /** Small immutable sorted integer set used as identity of an edge subset. */
    static final class BitSet {
        private final int[] values;

        BitSet() {
            this.values = new int[0];
        }

        private BitSet(int[] values) {
            this.values = values;
        }

        BitSet copyWith(int v) {
            int[] next = new int[values.length + 1];
            int k = 0;
            boolean inserted = false;
            for (int value : values) {
                if (!inserted && value >= v) {
                    next[k++] = v;
                    inserted = true;
                }
                next[k++] = value;
            }
            if (!inserted) {
                next[k] = v;
            }
            return new BitSet(next);
        }

        boolean contains(int v) {
            return java.util.Arrays.binarySearch(values, v) >= 0;
        }

        int size() {
            return values.length;
        }

        int[] sortedValues() {
            return values;
        }

        BitSet copy() {
            return new BitSet(values.clone());
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof BitSet b) && java.util.Arrays.equals(values, b.values);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(values);
        }
    }
}
