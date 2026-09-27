package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.graph.GraphCollection;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequentSubgraphMinerTest {

    private PropertyGraph edgeGraph(String name, String nl, String nr) {
        PropertyGraph g = new PropertyGraph(name);
        g.addNode(new LabeledNode("a", nl));
        g.addNode(new LabeledNode("b", nr));
        g.addEdge(new LabeledEdge("a", "b", "rel"));
        return g;
    }

    private GraphCollection twoGraphs() {
        GraphCollection c = new GraphCollection();
        c.put(edgeGraph("g1", "A", "B"));
        c.put(edgeGraph("g2", "A", "B"));
        c.put(edgeGraph("g3", "A", "C"));
        return c;
    }

    @Test
    void graphLevelSupportIsCountedCorrectly() {
        MiningConfig cfg = new MiningConfig();
        cfg.setMinSupport(2);
        MiningOutcome outcome = new FrequentSubgraphMiner(cfg).mine(twoGraphs());

        PatternResult edgeAB = outcome.getPatterns().stream()
                .filter(p -> p.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(2, edgeAB.getSupport());
        assertEquals(2, edgeAB.getEmbeddings().size());

        PatternResult nodeA = outcome.getPatterns().stream()
                .filter(p -> p.getEdgeCount() == 0
                        && p.getCanonicalCode().contains("A"))
                .findFirst().orElseThrow();
        assertEquals(3, nodeA.getSupport());
    }

    @Test
    void minSupportFiltersOutInfrequent() {
        MiningConfig cfg = new MiningConfig();
        cfg.setMinSupport(3);
        MiningOutcome outcome = new FrequentSubgraphMiner(cfg).mine(twoGraphs());
        assertTrue(outcome.getPatterns().stream().noneMatch(p -> p.getEdgeCount() == 1));
    }

    @Test
    void resultsAreSortedStably() {
        MiningConfig cfg = new MiningConfig();
        MiningOutcome o1 = new FrequentSubgraphMiner(cfg).mine(twoGraphs());
        MiningOutcome o2 = new FrequentSubgraphMiner(cfg).mine(twoGraphs());
        assertEquals(
                o1.getPatterns().stream().map(PatternResult::getCanonicalCode).toList(),
                o2.getPatterns().stream().map(PatternResult::getCanonicalCode).toList());
    }

    @Test
    void maxResultsTruncatesWithClearStatus() {
        GraphCollection c = new GraphCollection();
        c.put(edgeGraph("g1", "A", "B"));
        MiningConfig cfg = new MiningConfig();
        cfg.setMaxResults(2);
        MiningOutcome outcome = new FrequentSubgraphMiner(cfg).mine(c);
        assertEquals(MiningStatus.RESULT_LIMIT_REACHED, outcome.getStatus());
        assertTrue(outcome.getPatterns().size() <= 2);
    }

    @Test
    void graphSizeLimitIsEnforced() {
        GraphCollection c = new GraphCollection();
        PropertyGraph g = new PropertyGraph("big");
        g.addNode(new LabeledNode("a", "A"));
        g.addNode(new LabeledNode("b", "B"));
        g.addNode(new LabeledNode("c", "C"));
        g.addEdge(new LabeledEdge("a", "b", "r"));
        g.addEdge(new LabeledEdge("b", "c", "r"));
        c.put(g);
        MiningConfig cfg = new MiningConfig();
        cfg.setMaxNodesPerGraph(2);
        assertThrows(MiningLimitsExceededException.class,
                () -> new FrequentSubgraphMiner(cfg).mine(c));
    }

    @Test
    void timeoutStatusIsValid() {
        GraphCollection c = twoGraphs();
        MiningConfig cfg = new MiningConfig();
        cfg.setTimeoutMillis(1);
        MiningOutcome outcome = new FrequentSubgraphMiner(cfg).mine(c, Deadline.after(1));
        assertTrue(outcome.getStatus() == MiningStatus.COMPLETE
                || outcome.getStatus() == MiningStatus.TIMEOUT);
    }
}
