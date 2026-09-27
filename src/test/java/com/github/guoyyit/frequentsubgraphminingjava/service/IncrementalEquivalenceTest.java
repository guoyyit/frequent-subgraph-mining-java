package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.config.MiningProperties;
import com.github.guoyyit.frequentsubgraphminingjava.graph.GraphCollection;
import com.github.guoyyit.frequentsubgraphminingjava.mining.Embedding;
import com.github.guoyyit.frequentsubgraphminingjava.mining.FrequentSubgraphMiner;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternResult;
import com.github.guoyyit.frequentsubgraphminingjava.store.FilePersistenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 通过随机但固定种子的变更序列，验证增量服务结果始终等于
 * 对当前图集合直接运行 {@link FrequentSubgraphMiner} 的全量结果。
 */
class IncrementalEquivalenceTest {

    private final java.util.Random random = new java.util.Random(42);

    private GraphChangeRequest.NodeDto node(String id, String label) {
        GraphChangeRequest.NodeDto n = new GraphChangeRequest.NodeDto();
        n.setId(id);
        n.setLabel(label);
        return n;
    }

    private GraphChangeRequest.EdgeDto edge(String s, String t, String l) {
        GraphChangeRequest.EdgeDto e = new GraphChangeRequest.EdgeDto();
        e.setSource(s);
        e.setTarget(t);
        e.setLabel(l);
        return e;
    }

    private DefaultMiningService service(Path dir) {
        MiningProperties p = new MiningProperties();
        p.setCompactThreshold(3);
        return new DefaultMiningService(new FilePersistenceStore(dir, new ObjectMapper()), p);
    }

    private MiningConfig config() {
        MiningConfig c = new MiningConfig();
        c.setMinSupport(1);
        c.setMaxPatternEdges(2);
        c.setMaxResults(500);
        c.setMaxEmbeddingsPerPattern(50);
        return c;
    }

    private MiningQuery query() {
        MiningQuery q = new MiningQuery();
        q.setMinSupport(1);
        q.setMaxPatternEdges(2);
        q.setMaxResults(500);
        q.setMaxEmbeddingsPerPattern(50);
        return q;
    }

    /** 生成一张 3~4 个节点、2~4 条边的随机小图。 */
    private GraphChangeRequest randomGraph(String name) {
        String[] labels = {"A", "B", "C"};
        int nodeCount = 3 + random.nextInt(2);
        String[] edgeLabels = {"r", "s"};
        List<GraphChangeRequest.NodeDto> nodes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(node("n" + i, labels[random.nextInt(labels.length)]));
        }
        List<GraphChangeRequest.EdgeDto> edges = new ArrayList<>();
        int edgeCount = 2 + random.nextInt(3);
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < edgeCount; i++) {
            int a = random.nextInt(nodeCount);
            int b = random.nextInt(nodeCount);
            String l = edgeLabels[random.nextInt(edgeLabels.length)];
            String key = a + "->" + b + ":" + l;
            if (seen.add(key)) {
                edges.add(edge("n" + a, "n" + b, l));
            }
        }
        return change(name, nodes, edges);
    }

    private GraphChangeRequest change(String name,
                                      List<GraphChangeRequest.NodeDto> nodes,
                                      List<GraphChangeRequest.EdgeDto> edges) {
        GraphChangeRequest r = new GraphChangeRequest();
        r.setGraphName(name);
        r.setNodes(nodes);
        r.setEdges(edges);
        return r;
    }

    private void addToCollection(GraphCollection collection, GraphChangeRequest r) {
        com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph g =
                new com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph(r.getGraphName());
        for (var n : r.getNodes()) {
            g.addNode(new com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode(n.getId(), n.getLabel()));
        }
        for (var e : r.getEdges()) {
            g.addEdge(new com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge(
                    e.getSource(), e.getTarget(), e.getLabel()));
        }
        collection.getGraphs().put(r.getGraphName(), g);
    }

    /** 比较支持度与嵌入多重集合（嵌入内容规范化为字符串集合）。 */
    private void assertEquivalent(MiningOutcome incremental, MiningOutcome full) {
        Map<String, PatternResult> inc = index(incremental);
        Map<String, PatternResult> ful = index(full);
        assertEquals(new TreeMap<>(ful).keySet(), new TreeMap<>(inc).keySet(), "模式集合不一致");

        for (String code : ful.keySet()) {
            PatternResult a = inc.get(code);
            PatternResult b = ful.get(code);
            assertEquals(b.getSupport(), a.getSupport(), "支持度不一致: " + code);
            assertEquals(embedSet(b), embedSet(a), "嵌入集合不一致: " + code);
        }
    }

    private Map<String, PatternResult> index(MiningOutcome o) {
        Map<String, PatternResult> m = new LinkedHashMap<>();
        for (PatternResult pr : o.getPatterns()) {
            m.put(pr.getCanonicalCode(), pr);
        }
        return m;
    }

    private Set<String> embedSet(PatternResult pr) {
        Set<String> set = new java.util.TreeSet<>();
        for (Embedding e : pr.getEmbeddings()) {
            set.add(e.getGraphName() + "|" + new TreeMap<>(e.getNodeMapping()));
        }
        return set;
    }

    @Test
    void incrementalMatchesFullAcrossAddUpdateDeleteSequence(@TempDir Path dir) {
        DefaultMiningService svc = service(dir);
        GraphCollection reference = new GraphCollection();
        MiningConfig cfg = config();

        List<String> names = List.of("g0", "g1", "g2", "g3", "g4");
        // 初始批量新增
        for (String name : names) {
            GraphChangeRequest r = randomGraph(name);
            r.setOperation("ADD");
            svc.commitChange(r);
            addToCollection(reference, r);
        }
        assertEquivalent(svc.query(query()), new FrequentSubgraphMiner(cfg).mine(reference));

        // 多轮修改 / 删除 / 再新增
        for (int round = 0; round < 8; round++) {
            String name = names.get(random.nextInt(names.size()));
            int action = random.nextInt(3);
            GraphChangeRequest r = randomGraph(name);
            boolean exists = reference.get(name) != null;
            if (action == 0) {
                if (exists) {
                    r.setOperation("UPDATE");
                    svc.commitChange(r);
                    addToCollection(reference, r);
                }
            } else if (action == 1) {
                if (exists) {
                    r.setOperation("DELETE");
                    r.setNodes(List.of());
                    r.setEdges(List.of());
                    svc.commitChange(r);
                    reference.getGraphs().remove(name);
                }
            } else {
                if (!exists) {
                    r.setOperation("ADD");
                    svc.commitChange(r);
                    addToCollection(reference, r);
                }
            }
            assertEquivalent(svc.query(query()), new FrequentSubgraphMiner(cfg).mine(reference));
        }
    }
}
