package com.github.guoyyit.frequentsubgraphminingjava.tests;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.service.FrequentSubgraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitAndDeterminismTest {

    /** Dense graph produces a combinatorial number of connected edge subsets. */
    private static Graph denseGraph(String id, int nodes, int edgesPerNode) {
        Map<String, String> nodeLabels = new LinkedHashMap<>();
        List<Graph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            nodeLabels.put("n" + i, "A");
        }
        for (int i = 0; i < nodes; i++) {
            for (int k = 1; k <= edgesPerNode; k++) {
                int j = (i + k) % nodes;
                edges.add(new Graph.Edge("n" + i, "n" + j, "e" + (k % 2)));
            }
        }
        return new Graph(id, nodeLabels, edges);
    }

    private String keys(MiningResult r) {
        StringBuilder sb = new StringBuilder();
        r.patterns().forEach(m -> sb.append(m.pattern().getCanonicalKey()).append('\n'));
        return sb.toString();
    }

    @Test
    void maxEdgesLimitsPatternSize() {
        FrequentSubgraphService svc = new FrequentSubgraphService();
        svc.start();
        svc.upsertGraph(denseGraph("g", 5, 2));
        MiningResult r = svc.query(new MiningConfig(1, 2, Set.of(), Set.of()),
                MiningBudget.unbounded());
        for (MiningResult.PatternMatch m : r.patterns()) {
            assertTrue(m.pattern().getEdgeCount() <= 2);
        }
        assertTrue(r.patterns().size() > 1);
    }

    @Test
    void resultCountLimitTruncatesWithStatus() {
        FrequentSubgraphService svc = new FrequentSubgraphService();
        svc.start();
        for (int i = 0; i < 3; i++) {
            svc.upsertGraph(denseGraph("g" + i, 6, 2));
        }
        MiningResult limited = svc.query(new MiningConfig(1, 4, Set.of(), Set.of()),
                new MiningBudget(3, 10_000_000, 60_000L));
        assertTrue(limited.truncated(), "expected truncation flag");
        assertEquals(3, limited.patterns().size());
        assertTrue(limited.limitReason() != null && !limited.limitReason().isBlank());
    }

    @Test
    void candidateLimitAndTimeoutTerminate(@TempDir Path dir) {
        FrequentSubgraphService svc = new FrequentSubgraphService(dir, 0);
        svc.start();
        svc.upsertGraph(denseGraph("big", 10, 4));
        // Very tight candidate budget during index maintenance would throw internally;
        // instead the query-side candidate cap must terminate cleanly.
        long t0 = System.currentTimeMillis();
        MiningResult r = svc.query(new MiningConfig(1, 8, Set.of(), Set.of()),
                new MiningBudget(50, 50, 200L));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(r.truncated());
        assertTrue(elapsed < 20_000, "mining did not terminate promptly: " + elapsed + "ms");
    }

    @Test
    void repeatedRunsAreDeterministic() {
        List<String> outputs = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            FrequentSubgraphService svc = new FrequentSubgraphService();
            svc.start();
            // Insert in different order on purpose.
            List<String> ids = new ArrayList<>(List.of("g0", "g1", "g2", "g3"));
            if (run % 2 == 1) {
                java.util.Collections.reverse(ids);
            }
            for (String id : ids) {
                svc.upsertGraph(denseGraph(id, 5, 2));
            }
            MiningResult r = svc.query(new MiningConfig(2, 3, Set.of(), Set.of()),
                    MiningBudget.unbounded());
            outputs.add(keys(r) + "//" + r.patterns().stream()
                    .map(m -> m.pattern().getCanonicalKey() + "=" + m.support())
                    .reduce("", (a, b) -> a + b + "|"));
        }
        assertEquals(outputs.get(0), outputs.get(1));
        assertEquals(outputs.get(1), outputs.get(2));
    }

    @Test
    void differentConfigsProduceDifferentResults() {
        FrequentSubgraphService svc = new FrequentSubgraphService();
        svc.start();
        svc.upsertGraph(new Graph("g1", Map.of("a", "A", "b", "B"),
                List.of(new Graph.Edge("a", "b", "x"))));
        svc.upsertGraph(new Graph("g2", Map.of("a", "A", "b", "B"),
                List.of(new Graph.Edge("a", "b", "y"))));

        MiningResult anyEdge = svc.query(new MiningConfig(1, 1, Set.of(), Set.of()),
                MiningBudget.unbounded());
        MiningResult onlyX = svc.query(new MiningConfig(1, 1, Set.of(), Set.of("x")),
                MiningBudget.unbounded());
        MiningResult frequent = svc.query(new MiningConfig(2, 1, Set.of(), Set.of()),
                MiningBudget.unbounded());

        // Node-label patterns A,B occur in both graphs; two distinct edge labels each once.
        assertEquals(4, anyEdge.patterns().size());
        assertEquals(3, onlyX.patterns().size());
        // minSupport=2: only the two node-label patterns survive.
        assertEquals(2, frequent.patterns().size());
        assertNotEquals(keys(anyEdge), keys(onlyX));
    }
}
