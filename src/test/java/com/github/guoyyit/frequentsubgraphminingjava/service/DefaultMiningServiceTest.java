package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.config.MiningProperties;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningStatus;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternResult;
import com.github.guoyyit.frequentsubgraphminingjava.store.FilePersistenceStore;
import com.github.guoyyit.frequentsubgraphminingjava.store.PersistenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMiningServiceTest {

    private MiningProperties properties() {
        MiningProperties p = new MiningProperties();
        p.setCompactThreshold(2);
        return p;
    }

    private DefaultMiningService newService(Path dir, MiningProperties p) {
        PersistenceStore store = new FilePersistenceStore(dir, new ObjectMapper());
        return new DefaultMiningService(store, p);
    }

    private GraphChangeRequest.EdgeDto edge(String s, String t, String l) {
        GraphChangeRequest.EdgeDto e = new GraphChangeRequest.EdgeDto();
        e.setSource(s);
        e.setTarget(t);
        e.setLabel(l);
        return e;
    }

    private GraphChangeRequest.NodeDto node(String id, String label) {
        GraphChangeRequest.NodeDto n = new GraphChangeRequest.NodeDto();
        n.setId(id);
        n.setLabel(label);
        return n;
    }

    private GraphChangeRequest change(String op, String name,
                                      List<GraphChangeRequest.NodeDto> nodes,
                                      List<GraphChangeRequest.EdgeDto> edges) {
        GraphChangeRequest r = new GraphChangeRequest();
        r.setOperation(op);
        r.setGraphName(name);
        r.setNodes(nodes);
        r.setEdges(edges);
        return r;
    }

    private GraphChangeRequest abGraph(String op, String name) {
        return change(op, name,
                List.of(node("a", "A"), node("b", "B")),
                List.of(edge("a", "b", "rel")));
    }

    private Map<String, PatternResult> index(MiningOutcome outcome) {
        Map<String, PatternResult> map = new java.util.LinkedHashMap<>();
        for (PatternResult pr : outcome.getPatterns()) {
            map.put(pr.getCanonicalCode(), pr);
        }
        return map;
    }

    @Test
    void incrementalResultMatchesFullMiningAfterAdd(@TempDir Path dir) {
        MiningProperties p = properties();
        DefaultMiningService svc = newService(dir, p);

        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));
        MiningQuery q = new MiningQuery();
        q.setMinSupport(2);
        MiningOutcome incremental = svc.query(q);

        // 对照：全新服务 + 同样的两张图（全量路径）
        Path dir2 = dir.resolve("full");
        DefaultMiningService full = newService(dir2, p);
        full.commitChange(change("ADD", "g1",
                List.of(node("a", "A"), node("b", "B")),
                List.of(edge("a", "b", "rel"))));
        full.commitChange(change("ADD", "g2",
                List.of(node("a", "A"), node("b", "B")),
                List.of(edge("a", "b", "rel"))));
        MiningOutcome baseline = full.query(q);

        assertEquals(codes(baseline), codes(incremental));
        PatternResult edge = incremental.getPatterns().stream()
                .filter(x -> x.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(2, edge.getSupport());
    }

    @Test
    void updateAndDeleteKeepResultsConsistentWithFullRecompute(@TempDir Path dir) {
        MiningProperties p = properties();
        DefaultMiningService svc = newService(dir, p);
        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));

        // 先激活域
        MiningQuery q = new MiningQuery();
        q.setMinSupport(1);
        svc.query(q);

        // 修改 g2：A-B 变为 A-C，公共边模式应只剩 g1 支持
        svc.commitChange(change("UPDATE", "g2",
                List.of(node("a", "A"), node("c", "C")),
                List.of(edge("a", "c", "rel"))));

        MiningOutcome afterUpdate = svc.query(q);
        Map<String, PatternResult> idx = index(afterUpdate);
        PatternResult abEdge = idx.values().stream()
                .filter(x -> x.getEdgeCount() == 1
                        && x.getCanonicalCode().contains("A")
                        && x.getCanonicalCode().contains("B"))
                .findFirst().orElseThrow();
        assertEquals(1, abEdge.getSupport());

        // 删除 g1 后，A-B 边模式不再存在
        svc.commitChange(change("DELETE", "g1", List.of(), List.of()));
        MiningOutcome afterDelete = svc.query(q);
        assertTrue(afterDelete.getPatterns().stream().noneMatch(
                x -> x.getEdgeCount() == 1 && x.getCanonicalCode().contains("B")));
        assertEquals(1, afterDelete.getGraphCount());
    }

    @Test
    void minSupportAndLabelFiltersAreApplied(@TempDir Path dir) {
        DefaultMiningService svc = newService(dir, properties());
        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));

        MiningQuery q = new MiningQuery();
        q.setMinSupport(2);
        q.setAllowedEdgeLabels(Set.of("nonexistent"));
        MiningOutcome outcome = svc.query(q);
        // 边标签被过滤后没有边模式
        assertTrue(outcome.getPatterns().stream().noneMatch(x -> x.getEdgeCount() == 1));
    }

    @Test
    void maxPatternEdgesConstrainsPatternSpace(@TempDir Path dir) {
        DefaultMiningService svc = newService(dir, properties());
        svc.commitChange(change("ADD", "chain",
                List.of(node("a", "A"), node("b", "B"), node("c", "C")),
                List.of(edge("a", "b", "r"), edge("b", "c", "r"))));
        MiningQuery q = new MiningQuery();
        q.setMaxPatternEdges(1);
        MiningOutcome o1 = svc.query(q);
        assertTrue(o1.getPatterns().stream().noneMatch(x -> x.getEdgeCount() > 1));

        // 不同配置域：放宽到 2 条边后出现两条边模式
        MiningQuery q2 = new MiningQuery();
        q2.setMaxPatternEdges(2);
        MiningOutcome o2 = svc.query(q2);
        assertTrue(o2.getPatterns().stream().anyMatch(x -> x.getEdgeCount() == 2));
        // 原来的 1 条边域结果不受影响
        assertTrue(o1.getPatterns().stream().noneMatch(x -> x.getEdgeCount() > 1));
    }

    @Test
    void repeatedQueriesAreDeterministic(@TempDir Path dir) {
        DefaultMiningService svc = newService(dir, properties());
        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));
        svc.commitChange(change("ADD", "g3",
                List.of(node("a", "A"), node("c", "C")),
                List.of(edge("a", "c", "rel"))));
        MiningQuery q = new MiningQuery();
        List<String> first = codes(svc.query(q));
        List<String> second = codes(svc.query(q));
        assertEquals(first, second);
    }

    @Test
    void crashRecoveryProducesSameResult(@TempDir Path dir) {
        MiningProperties p = properties();
        DefaultMiningService svc = newService(dir, p);
        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));

        // 不做查询：模拟“域追赶尚未发生”时进程崩溃，重启后只从持久化目录恢复
        DefaultMiningService restarted = newService(dir, p);
        assertEquals(2, restarted.currentVersion());
        MiningOutcome outcome = restarted.query(new MiningQuery());
        PatternResult edge = outcome.getPatterns().stream()
                .filter(x -> x.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(2, edge.getSupport());
        assertEquals(2, edge.getEmbeddings().size());

        // 再次重启（此时域追赶已持久化）结果一致
        DefaultMiningService restarted2 = newService(dir, p);
        MiningOutcome outcome2 = restarted2.query(new MiningQuery());
        assertEquals(codes(outcome), codes(outcome2));
    }

    @Test
    void recoveredAfterMultipleChangesDoesNotDoubleCount(@TempDir Path dir) {
        MiningProperties p = properties();
        DefaultMiningService svc = newService(dir, p);
        svc.commitChange(abGraph("ADD", "g1"));
        svc.commitChange(abGraph("ADD", "g2"));
        svc.query(new MiningQuery()); // 激活并追赶域
        svc.commitChange(change("DELETE", "g1", List.of(), List.of()));
        // 模拟在域增量追赶持久化后、任何时刻崩溃
        DefaultMiningService restarted = newService(dir, p);
        MiningOutcome outcome = restarted.query(new MiningQuery());
        PatternResult edge = outcome.getPatterns().stream()
                .filter(x -> x.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(1, edge.getSupport());
    }

    @Test
    void invalidOperationsAreRejected(@TempDir Path dir) {
        DefaultMiningService svc = newService(dir, properties());
        svc.commitChange(abGraph("ADD", "g1"));
        assertThrows(IllegalArgumentException.class, () -> svc.commitChange(abGraph("ADD", "g1")));
        assertThrows(IllegalArgumentException.class,
                () -> svc.commitChange(change("UPDATE", "nope", List.of(), List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> svc.commitChange(change("DELETE", "nope", List.of(), List.of())));
    }

    @Test
    void resultLimitReturnsClearStatus(@TempDir Path dir) {
        DefaultMiningService svc = newService(dir, properties());
        svc.commitChange(change("ADD", "g",
                List.of(node("a", "A"), node("b", "B"), node("c", "C")),
                List.of(edge("a", "b", "r"), edge("b", "c", "r"))));
        MiningQuery q = new MiningQuery();
        q.setMaxResults(2);
        MiningOutcome outcome = svc.query(q);
        assertEquals(MiningStatus.RESULT_LIMIT_REACHED, outcome.getStatus());
        assertEquals(2, outcome.getPatterns().size());
    }

    @Test
    void labelsWithDelimiterCharactersGetDistinctDomainSignatures() {
        com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig c1 =
                new com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig();
        c1.setAllowedNodeLabels(Set.of("a,b"));
        com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig c2 =
                new com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig();
        c2.setAllowedNodeLabels(Set.of("a", "b"));
        com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig c3 =
                new com.github.guoyyit.frequentsubgraphminingjava.mining.MiningConfig();
        c3.setAllowedNodeLabels(Set.of("a|b"));
        assertNotEquals(c1.domainSignature(), c2.domainSignature());
        assertNotEquals(c1.domainSignature(), c3.domainSignature());
        assertNotEquals(c2.domainSignature(), c3.domainSignature());
    }

    @Test
    void oversizedGraphChangeIsRejectedAsLimitsExceeded(@TempDir Path dir) {
        MiningProperties p = properties();
        p.setMaxNodesPerGraph(2);
        DefaultMiningService svc = newService(dir, p);
        assertThrows(com.github.guoyyit.frequentsubgraphminingjava.mining.MiningLimitsExceededException.class,
                () -> svc.commitChange(change("ADD", "big",
                        List.of(node("a", "A"), node("b", "B"), node("c", "C")),
                        List.of(edge("a", "b", "r"), edge("b", "c", "r")))));
        // 被拒绝的变更不得部分生效
        assertEquals(0, svc.currentVersion());
    }

    private List<String> codes(MiningOutcome outcome) {
        List<String> list = new ArrayList<>();
        for (PatternResult pr : outcome.getPatterns()) {
            list.add(pr.getCanonicalCode());
        }
        return list;
    }
}
