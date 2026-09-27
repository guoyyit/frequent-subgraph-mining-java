package com.github.guoyyit.frequentsubgraphminingjava.tests;

import com.github.guoyyit.frequentsubgraphminingjava.durability.DurabilityStore;
import com.github.guoyyit.frequentsubgraphminingjava.durability.GraphCodec;
import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.model.MutationType;
import com.github.guoyyit.frequentsubgraphminingjava.service.FrequentSubgraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a simulated crash the service restores the last consistent committed state:
 * committed mutations survive, sequence numbers continue without reuse, a torn WAL
 * tail is ignored, and results equal a fresh full mining.
 */
class CrashRecoveryTest {

    private static Graph edgeGraph(String id, String label) {
        return new Graph(id, Map.of("a", "A", "b", "B"),
                List.of(new Graph.Edge("a", "b", label)));
    }

    private final MiningConfig config = new MiningConfig(2, 2, Set.of(), Set.of());

    @Test
    void restartRestoresCommittedState(@TempDir Path dir) {
        FrequentSubgraphService first = new FrequentSubgraphService(dir, 0);
        first.start();
        first.upsertGraph(edgeGraph("g1", "link"));
        first.upsertGraph(edgeGraph("g2", "link"));
        first.upsertGraph(edgeGraph("g3", "other"));
        first.deleteGraph("g3");
        long versionBefore = first.currentVersion();
        assertEquals(4, versionBefore);

        // Simulate process restart with a brand-new service over the same directory.
        FrequentSubgraphService restarted = new FrequentSubgraphService(dir, 0);
        restarted.start();

        assertEquals(versionBefore, restarted.currentVersion());
        assertEquals(Set.of("g1", "g2"), restarted.currentGraphs().keySet());

        MiningResult result = restarted.query(config, MiningBudget.unbounded());
        // Node labels A,B occur in both graphs plus the shared "link" edge: 3 patterns.
        assertEquals(3, result.patterns().size());
        assertEquals(2, supportOf(result, "link"));

        // Sequence continues monotonically — no reuse / double counting.
        restarted.upsertGraph(edgeGraph("g4", "link"));
        assertEquals(versionBefore + 1, restarted.currentVersion());
        MiningResult after = restarted.query(config, MiningBudget.unbounded());
        assertEquals(3, supportOf(after, "link"));
    }

    @Test
    void tornWalTailIsIgnored(@TempDir Path dir) throws Exception {
        DurabilityStore store = new DurabilityStore(dir);
        FrequentSubgraphService service = new FrequentSubgraphService(dir, 0);
        service.start();
        service.upsertGraph(edgeGraph("g1", "link"));
        service.upsertGraph(edgeGraph("g2", "link"));

        // Simulate a crash in the middle of flushing a third record: bytes present
        // without a terminating newline mean "not committed".
        Path wal = dir.resolve(DurabilityStore.WAL_NAME);
        String partial = "{\"seq\":3,\"op\":\"UPSERT\",\"id\":\"g3\",\"ts\":0,\"graph\":"
                + GraphCodec.graphToJson(edgeGraph("g3", "link"));
        Files.write(wal,
                (Files.readString(wal) + partial).getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        FrequentSubgraphService recovered = new FrequentSubgraphService(dir, 0);
        recovered.start();
        assertEquals(2, recovered.currentVersion());
        assertFalse(recovered.currentGraphs().containsKey("g3"));

        // Next committed event must reuse sequence 3 (the torn one never committed).
        recovered.upsertGraph(edgeGraph("g3", "link"));
        assertEquals(3, recovered.currentVersion());
        assertTrue(recovered.currentGraphs().containsKey("g3"));
    }

    @Test
    void snapshotAndWalTruncationStillRecovers(@TempDir Path dir) {
        // snapshotEvery=3 -> after seq 3 a snapshot is written and WAL truncated.
        FrequentSubgraphService service = new FrequentSubgraphService(dir, 3);
        service.start();
        service.upsertGraph(edgeGraph("g1", "link"));
        service.upsertGraph(edgeGraph("g2", "link"));
        service.upsertGraph(edgeGraph("g3", "link"));
        service.upsertGraph(edgeGraph("g4", "other"));

        FrequentSubgraphService recovered = new FrequentSubgraphService(dir, 3);
        recovered.start();
        assertEquals(4, recovered.currentVersion());
        assertEquals(Set.of("g1", "g2", "g3", "g4"), recovered.currentGraphs().keySet());
        MiningResult result = recovered.query(config, MiningBudget.unbounded());
        // A,B node patterns span all 4 graphs; the "link" edge spans 3.
        assertEquals(3, result.patterns().size());
        assertEquals(3, supportOf(result, "link"));
    }

    @Test
    void deletionIsDurable(@TempDir Path dir) {
        FrequentSubgraphService service = new FrequentSubgraphService(dir, 0);
        service.start();
        service.upsertGraph(edgeGraph("g1", "link"));
        service.upsertGraph(edgeGraph("g2", "link"));
        service.deleteGraph("g1");

        FrequentSubgraphService recovered = new FrequentSubgraphService(dir, 0);
        recovered.start();
        assertEquals(Set.of("g2"), recovered.currentGraphs().keySet());
        MiningResult result = recovered.query(
                new MiningConfig(1, 2, Set.of(), Set.of()), MiningBudget.unbounded());
        // A,B node patterns plus the "link" edge of the remaining graph.
        assertEquals(3, result.patterns().size());
        assertEquals(1, supportOf(result, "link"));
    }

    private static int supportOf(MiningResult result, String edgeLabel) {
        return result.patterns().stream()
                .filter(m -> m.pattern().getEdgeCount() == 1
                        && m.pattern().getCanonicalKey().endsWith(":" + edgeLabel))
                .findFirst().orElseThrow().support();
    }
}
