package com.github.guoyyit.frequentsubgraphminingjava.tests;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningBudget;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningConfig;
import com.github.guoyyit.frequentsubgraphminingjava.model.MiningResult;
import com.github.guoyyit.frequentsubgraphminingjava.service.FrequentSubgraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent graph mutations and mining queries: every query result must be internally
 * consistent with some committed version — no partially mixed counts.
 */
class ConcurrencyIsolationTest {

    private static Graph graph(String id, int variant) {
        return new Graph(id,
                Map.of("a", "A", "b", "B"),
                List.of(new Graph.Edge("a", "b", variant % 2 == 0 ? "link" : "other")));
    }

    @Test
    void queriesNeverSeeMixedState(@TempDir Path dir) throws Exception {
        FrequentSubgraphService service = new FrequentSubgraphService(dir, 0);
        service.start();
        MiningConfig cfg = new MiningConfig(1, 2, Set.of(), Set.of());

        int writers = 4;
        int readers = 4;
        int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger inconsistent = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        CountDownLatch writersDone = new CountDownLatch(writers);

        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations && !stop.get(); i++) {
                        String id = "g" + (i % 10);
                        service.upsertGraph(graph(id, writerId * iterations + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        MiningResult result = service.query(cfg,
                                new MiningBudget(1000, 100_000, 5_000L));
                        queries.incrementAndGet();
                        // Consistency invariant: embeddings are grouped by whole committed
                        // graphs, so distinct embedding graph ids must equal support, and
                        // support cannot exceed the total graph count of the snapshot.
                        for (MiningResult.PatternMatch m : result.patterns()) {
                            if (m.support() > result.totalGraphs() || m.support() < 1) {
                                inconsistent.incrementAndGet();
                            }
                            long distinctGraphs = m.embeddings().stream()
                                    .map(e -> e.graphId())
                                    .distinct().count();
                            if (distinctGraphs != m.support()) {
                                inconsistent.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        boolean writersFinished = writersDone.await(30, TimeUnit.SECONDS);
        stop.set(true);
        pool.shutdown();
        boolean done = pool.awaitTermination(30, TimeUnit.SECONDS);
        assertTrue(writersFinished, "writers did not finish in time");
        assertTrue(done, "workload did not finish in time");
        assertTrue(queries.get() > 0, "expected concurrent queries to run");
        assertTrue(inconsistent.get() == 0,
                "found " + inconsistent.get() + " inconsistent mixed-state query results");
    }
}
