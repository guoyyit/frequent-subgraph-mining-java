package com.github.guoyyit.frequentsubgraphminingjava.tests;

import com.github.guoyyit.frequentsubgraphminingjava.mining.SingleGraphPatterns;
import com.github.guoyyit.frequentsubgraphminingjava.mining.SubgraphMiner;
import com.github.guoyyit.frequentsubgraphminingjava.model.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.model.Pattern;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningCoreTest {

    private Graph edgeGraph(String id, String a, String b, String label) {
        return new Graph(id, Map.of(a, "X", b, "Y"),
                List.of(new Graph.Edge(a, b, label)));
    }

    @Test
    void enumeratesSingleEdgePatternWithEmbedding() {
        Graph g = edgeGraph("g1", "a", "b", "knows");
        Map<Pattern, List<Embedding>> patterns = new SingleGraphPatterns()
                .enumerate(g, new MiningConfig(1, 2, Set.of(), Set.of()),
                        MiningBudget.unbounded());

        // 2 distinct node-label patterns + the 1-edge pattern.
        assertEquals(3, patterns.size());
        Pattern edgePattern = patterns.keySet().stream()
                .filter(x -> x.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(2, edgePattern.getNodeCount());
        assertEquals(1, patterns.get(edgePattern).size());
        assertEquals("a", patterns.get(edgePattern).get(0).nodeMapping().get(0));
        assertEquals("b", patterns.get(edgePattern).get(0).nodeMapping().get(1));
    }

    @Test
    void isomorphicEdgesShareCanonicalKey() {
        Map<Pattern, List<Embedding>> p1 = new SingleGraphPatterns().enumerate(
                edgeGraph("g1", "a", "b", "knows"),
                new MiningConfig(1, 1, Set.of(), Set.of()), MiningBudget.unbounded());
        Map<Pattern, List<Embedding>> p2 = new SingleGraphPatterns().enumerate(
                edgeGraph("g2", "x", "y", "knows"),
                new MiningConfig(1, 1, Set.of(), Set.of()), MiningBudget.unbounded());
        assertEquals(p1.keySet(), p2.keySet());
    }

    @Test
    void directionAndLabelMatter() {
        MiningConfig cfg = new MiningConfig(1, 1, Set.of(), Set.of());
        Map<Pattern, List<Embedding>> forward = new SingleGraphPatterns().enumerate(
                new Graph("g1", Map.of("a", "X", "b", "Y"),
                        List.of(new Graph.Edge("a", "b", "knows"))),
                cfg, MiningBudget.unbounded());
        Map<Pattern, List<Embedding>> backward = new SingleGraphPatterns().enumerate(
                new Graph("g2", Map.of("a", "X", "b", "Y"),
                        List.of(new Graph.Edge("b", "a", "knows"))),
                cfg, MiningBudget.unbounded());
        Map<Pattern, List<Embedding>> otherLabel = new SingleGraphPatterns().enumerate(
                edgeGraph("g3", "a", "b", "likes"), cfg, MiningBudget.unbounded());
        // X->Y vs Y->X: node labels at the endpoints are asymmetric, so keys differ.
        assertFalse(forward.keySet().equals(backward.keySet()));
        assertFalse(forward.keySet().equals(otherLabel.keySet()));
    }

    @Test
    void fullMiningCountsSupportAcrossGraphs() {
        Map<String, Graph> graphs = new TreeMap<>();
        graphs.put("g1", edgeGraph("g1", "a", "b", "knows"));
        graphs.put("g2", edgeGraph("g2", "c", "d", "knows"));
        graphs.put("g3", new Graph("g3",
                Map.of("a", "X", "b", "Y"),
                List.of(new Graph.Edge("a", "b", "likes"))));

        MiningResult result = new SubgraphMiner().mine(graphs,
                new MiningConfig(2, 2, Set.of(), Set.of()), MiningBudget.unbounded());

        assertFalse(result.truncated());
        // Node labels X and Y are in all 3 graphs; edge "knows" in 2; edge "likes" in 1.
        MiningResult.PatternMatch knows = result.patterns().stream()
                .filter(m -> m.pattern().getEdgeCount() == 1
                        && "knows".equals(edgeLabel(m.pattern().getCanonicalKey())))
                .findFirst().orElseThrow();
        assertEquals(2, knows.support());
        assertEquals(3, result.patterns().size());
    }

    @Test
    void respectsMaxEdgesAndLabelWhitelist() {
        Graph chain = new Graph("g",
                Map.of("a", "X", "b", "X", "c", "X"),
                List.of(new Graph.Edge("a", "b", "e1"),
                        new Graph.Edge("b", "c", "e2")));
        MiningConfig restricted = new MiningConfig(1, 1, Set.of(), Set.of("e1"));
        Map<Pattern, List<Embedding>> patterns = new SingleGraphPatterns()
                .enumerate(chain, restricted, MiningBudget.unbounded());
        for (Pattern p : patterns.keySet()) {
            assertTrue(p.getEdgeCount() <= 1);
            if (p.getEdgeCount() == 1) {
                assertTrue(p.getCanonicalKey().contains(":e1"));
            }
        }
    }

    private static String edgeLabel(String canonicalKey) {
        int colon = canonicalKey.lastIndexOf(':');
        return canonicalKey.substring(colon + 1);
    }
}
