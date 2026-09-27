package com.github.guoyyit.frequentsubgraphminingjava.mining;

import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternEnumeratorTest {

    private PropertyGraph chainGraph() {
        // a:A -> b:B -> c:C
        PropertyGraph g = new PropertyGraph("g");
        g.addNode(new LabeledNode("a", "A"));
        g.addNode(new LabeledNode("b", "B"));
        g.addNode(new LabeledNode("c", "C"));
        g.addEdge(new LabeledEdge("a", "b", "x"));
        g.addEdge(new LabeledEdge("b", "c", "x"));
        return g;
    }

    @Test
    void chain3ProducesSixDistinctConnectedPatterns() {
        PropertyGraph g = chainGraph();
        MiningConfig cfg = new MiningConfig();
        cfg.setMaxPatternEdges(3);
        PatternEnumerator enumerator = new PatternEnumerator(g, cfg);
        Map<String, EnumeratedPattern> patterns = enumerator.enumerate(Deadline.after(5000));

        // 3 个单节点模式 + 2 条单边模式 + 1 个两条边模式
        assertEquals(6, patterns.size());

        long twoEdge = patterns.values().stream()
                .filter(p -> p.getCode().getEdgeCount() == 2).count();
        assertEquals(1, twoEdge);
    }

    @Test
    void singleNodePatternsCarryEmbedding() {
        PropertyGraph g = chainGraph();
        MiningConfig cfg = new MiningConfig();
        Map<String, EnumeratedPattern> patterns =
                new PatternEnumerator(g, cfg).enumerate(Deadline.after(5000));

        EnumeratedPattern nodeA = patterns.values().stream()
                .filter(p -> p.getCode().getEdgeCount() == 0
                        && p.getCode().getNodeLabels().equals(List.of("A")))
                .findFirst().orElseThrow();
        assertEquals(1, nodeA.getMappings().size());
        assertEquals("a", nodeA.getMappings().get(0).get("p0"));
    }

    @Test
    void labelFilterExcludesPatterns() {
        PropertyGraph g = chainGraph();
        MiningConfig cfg = new MiningConfig();
        cfg.getAllowedEdgeLabels().add("z");
        Map<String, EnumeratedPattern> patterns =
                new PatternEnumerator(g, cfg).enumerate(Deadline.after(5000));
        // 边标签 z 不存在 -> 只剩 3 个单节点模式
        assertEquals(3, patterns.size());
    }

    @Test
    void automorphicEmbeddingsAreDeduplicated() {
        // 两个相同标签节点之间一条边：对调节点是自同构，嵌入只算一次
        PropertyGraph g = new PropertyGraph("g2");
        g.addNode(new LabeledNode("u", "X"));
        g.addNode(new LabeledNode("v", "X"));
        g.addEdge(new LabeledEdge("u", "v", "r"));
        MiningConfig cfg = new MiningConfig();
        Map<String, EnumeratedPattern> patterns =
                new PatternEnumerator(g, cfg).enumerate(Deadline.after(5000));
        EnumeratedPattern edgePattern = patterns.values().stream()
                .filter(p -> p.getCode().getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(1, edgePattern.getMappings().size());
        assertTrue(edgePattern.getMappings().get(0).containsValue("u"));
    }

    @Test
    void parallelEdgesAreSupported() {
        // 同一对节点上的两条不同标签平行边，构成一个两条边的模式
        PropertyGraph g = new PropertyGraph("g3");
        g.addNode(new LabeledNode("u", "X"));
        g.addNode(new LabeledNode("v", "Y"));
        g.addEdge(new LabeledEdge("u", "v", "p"));
        g.addEdge(new LabeledEdge("u", "v", "q"));
        MiningConfig cfg = new MiningConfig();
        cfg.setMaxPatternEdges(2);
        Map<String, EnumeratedPattern> patterns =
                new PatternEnumerator(g, cfg).enumerate(Deadline.after(5000));
        long twoEdgeCount = patterns.values().stream()
                .filter(p -> p.getCode().getEdgeCount() == 2).count();
        assertEquals(1, twoEdgeCount);
    }
}
