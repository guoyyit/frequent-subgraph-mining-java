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

/**
 * Incremental updates (upsert/modify/delete) must yield exactly the same frequent
 * patterns and supports as a full mining over the current collection.
 */
class IncrementalConsistencyTest {

    private final MiningConfig config = new MiningConfig(2, 3, Set.of(), Set.of());

    private Graph randomishGraph(String id, int variant) {
        // Deterministic graph families sharing repeated motifs.
        int n = 4 + variant % 3;
        Map<String, String> nodes = new LinkedHashMap<>();
        List<Graph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.put("n" + i, (i + variant) % 2 == 0 ? "A" : "B");
        }
        for (int i = 0; i < n - 1; i++) {
            String l = (i + variant) % 3 == 0 ? "e1" : "e2";
            edges.add(new Graph.Edge("n" + i, "n" + (i + 1), l));
        }
        if (variant % 2 == 0) {
            edges.add(new Graph.Edge("n0", "n2", "e1"));
        }
        return new Graph(id, nodes, edges);
    }

    private String signature(MiningResult r) {
        StringBuilder sb = new StringBuilder();
        for (MiningResult.PatternMatch m : r.patterns()) {
            sb.append(m.pattern().getCanonicalKey())
                    .append('#').append(m.support())
                    .append('#').append(m.embeddings().size())
                    .append('\n');
        }
        return sb.toString();
    }

    @Test
    @SuppressWarnings("checkstyle:RegexpMultiline")
    void incrementalMatchesFullRecompute(@TempDir Path dir) {
        FrequentSubgraphService service = new FrequentSubgraphService(dir, 0);
        service.start();

        Map<String, Graph> reference = new LinkedHashMap<>();
        for (int round = 0; round < 20; round++) {
            String id = "g" + (round % 7);
            if (round % 5 == 4) {
                service.deleteGraph(id);
                reference.remove(id);
            } else {
                Graph g = randomishGraph(id, round);
                service.upsertGraph(g);
                reference.put(id, g);
            }
            assertEquivalent(service, reference);
        }
    }

    private void assertEquivalent(FrequentSubgraphService service, Map<String, Graph> reference) {
        MiningResult incremental = service.query(config, MiningBudget.unbounded());
        // Full re-mining bypasses the maintained index.
        MiningResult full = new com.github.guoyyit.frequentsubgraphminingjava.mining.SubgraphMiner()
                .mine(reference, config, MiningBudget.unbounded());
        assertEquals(signature(full), signature(incremental),
                "incremental result must equal full re-mining");
    }
}
