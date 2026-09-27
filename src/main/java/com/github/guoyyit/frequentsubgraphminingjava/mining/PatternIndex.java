package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.Pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable inverted index of pattern occurrences:
 * {@code pattern -> (graphId -> embeddings in that graph)}.
 *
 * <p>The support of a pattern is the number of distinct host graphs containing it.
 * Instances are immutable; {@link #withGraphReplaced} / {@link #withoutGraph} return
 * new copies, so a query can hold an immutable snapshot while writers build the next
 * version without any locking on the read side.
 */
public final class PatternIndex {

    /** graphId (sorted) -> embeddings of one pattern in that graph. */
    public static final class Postings {
        private final TreeMap<String, List<Embedding>> byGraph;

        Postings() {
            this.byGraph = new TreeMap<>();
        }

        Postings(TreeMap<String, List<Embedding>> byGraph) {
            this.byGraph = byGraph;
        }

        Postings copy() {
            TreeMap<String, List<Embedding>> clone = new TreeMap<>(byGraph);
            return new Postings(clone);
        }

        int size() {
            return byGraph.size();
        }
    }

    private final TreeMap<Pattern, Postings> index;

    public PatternIndex() {
        this.index = new TreeMap<>();
    }

    private PatternIndex(TreeMap<Pattern, Postings> index) {
        this.index = index;
    }

    /**
     * Returns a copy where all patterns contributed by {@code graph} are replaced by
     * the freshly enumerated {@code patternsInGraph}. This is the incremental update
     * primitive: only the changed graph is re-enumerated; every other graph's postings
     * are shared unchanged.
     */
    public PatternIndex withGraphReplaced(Graph graph,
                                          Map<Pattern, List<Embedding>> patternsInGraph) {
        TreeMap<Pattern, Postings> next = copyMutable();
        removeGraph(next, graph.getId());
        for (Map.Entry<Pattern, List<Embedding>> e : patternsInGraph.entrySet()) {
            Postings postings = next.computeIfAbsent(e.getKey(), k -> new Postings());
            postings.byGraph.put(graph.getId(), List.copyOf(e.getValue()));
        }
        return new PatternIndex(next);
    }

    /** Returns a copy with all postings of the given graph removed. */
    public PatternIndex withoutGraph(String graphId) {
        TreeMap<Pattern, Postings> next = copyMutable();
        removeGraph(next, graphId);
        return new PatternIndex(next);
    }

    /** graphId -> embeddings for a pattern; empty if the pattern is unknown. */
    public Map<String, List<Embedding>> embeddingsByGraph(Pattern pattern) {
        Postings postings = index.get(pattern);
        if (postings == null) {
            return Map.of();
        }
        return Collections.unmodifiableMap(postings.byGraph);
    }

    /** All embeddings of a pattern across all graphs (deterministic graph order). */
    public List<Embedding> allEmbeddings(Pattern pattern) {
        Postings postings = index.get(pattern);
        if (postings == null) {
            return List.of();
        }
        List<Embedding> out = new ArrayList<>();
        for (List<Embedding> list : postings.byGraph.values()) {
            out.addAll(list);
        }
        return List.copyOf(out);
    }

    public int support(Pattern pattern) {
        Postings postings = index.get(pattern);
        return postings == null ? 0 : postings.size();
    }

    public SortedSet<Pattern> allPatterns() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(index.keySet()));
    }

    public int patternCount() {
        return index.size();
    }

    private TreeMap<Pattern, Postings> copyMutable() {
        TreeMap<Pattern, Postings> clone = new TreeMap<>();
        for (Map.Entry<Pattern, Postings> e : index.entrySet()) {
            clone.put(e.getKey(), e.getValue().copy());
        }
        return clone;
    }

    private static void removeGraph(TreeMap<Pattern, Postings> target, String graphId) {
        var it = target.entrySet().iterator();
        while (it.hasNext()) {
            Postings postings = it.next().getValue();
            postings.byGraph.remove(graphId);
            if (postings.size() == 0) {
                it.remove();
            }
        }
    }
}
