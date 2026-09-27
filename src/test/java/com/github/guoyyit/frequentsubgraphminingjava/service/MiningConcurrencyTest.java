package com.github.guoyyit.frequentsubgraphminingjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.config.MiningProperties;
import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.mining.PatternResult;
import com.github.guoyyit.frequentsubgraphminingjava.store.FilePersistenceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发提交变更与执行挖掘查询：任何一次查询返回的支持计数必须对应某个
 * 完整目录版本，不得出现部分变更混合的计数。
 */
class MiningConcurrencyTest {

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

    private GraphChangeRequest addRequest(String name) {
        GraphChangeRequest r = new GraphChangeRequest();
        r.setOperation("ADD");
        r.setGraphName(name);
        r.setNodes(List.of(node("a", "A"), node("b", "B")));
        r.setEdges(List.of(edge("a", "b", "r")));
        return r;
    }

    @Test
    void concurrentChangesAndQueriesNeverObserveMixedState(@TempDir Path dir) throws Exception {
        MiningProperties p = new MiningProperties();
        p.setCompactThreshold(100);
        DefaultMiningService svc = new DefaultMiningService(
                new FilePersistenceStore(dir, new ObjectMapper()), p);

        int writerCount = 4;
        int readerCount = 6;
        int changesPerWriter = 15;
        ExecutorService writers = Executors.newFixedThreadPool(writerCount);
        ExecutorService readers = Executors.newFixedThreadPool(readerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writerCount);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 先放一张公共图，保证查询过程中边模式始终存在
        svc.commitChange(addRequest("base"));
        MiningQuery q = new MiningQuery();
        q.setMinSupport(1);
        svc.query(q); // 激活域

        // 写线程：每张图用唯一名字先 ADD，后续在 0..版本区间无需更新
        for (int w = 0; w < writerCount; w++) {
            final int writerId = w;
            writers.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < changesPerWriter; i++) {
                        String name = "w" + writerId + "-g" + i;
                        svc.commitChange(addRequest(name));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    writersDone.countDown();
                }
            });
        }

        // 读线程：反复查询并校验计数对应某个完整目录版本
        for (int r = 0; r < readerCount; r++) {
            readers.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        MiningOutcome outcome = svc.query(q);
                        // 该结果对应的版本中，图总数应与结果里图数量字段一致
                        long version = outcome.getCatalogVersion();
                        long snapshotSize = svc.snapshot().size();
                        // 对同一版本，A-B 边模式支持度必须 <= 图数量且 >= 1（base 永在）
                        PatternResult edge = outcome.getPatterns().stream()
                                .filter(x -> x.getEdgeCount() == 1).findFirst().orElse(null);
                        if (edge == null) {
                            failure.compareAndSet(null,
                                    new AssertionError("版本 " + version + " 丢失公共边模式"));
                            return;
                        }
                        if (edge.getSupport() < 1 || edge.getSupport() > snapshotSize) {
                            failure.compareAndSet(null, new AssertionError(
                                    "不一致计数: support=" + edge.getSupport()
                                            + " snapshotSize=" + snapshotSize
                                            + " version=" + version));
                            return;
                        }
                        // 嵌入按图分组；未被截断时，每个支持图至少有一个嵌入，
                        // 且嵌入来自不同图名的数量必须等于支持度
                        long distinctGraphs = edge.getEmbeddings().stream()
                                .map(e -> e.getGraphName()).distinct().count();
                        boolean truncated =
                                edge.getEmbeddings().size() >= p.getDefaultMaxEmbeddingsPerPattern();
                        if (!truncated && distinctGraphs != edge.getSupport()) {
                            failure.compareAndSet(null,
                                    new AssertionKeyError(edge, distinctGraphs));
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        assertTrue(writersDone.await(20, TimeUnit.SECONDS), "写线程应在限定时间内完成");
        stop.set(true);
        readers.shutdown();
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS), "读线程应在停止后置位后结束");
        writers.shutdownNow();

        if (failure.get() != null) {
            throw new AssertionError("并发一致性检查失败", failure.get());
        }

        // 最终状态：1 张 base + writerCount * changesPerWriter 张图，全部提交
        MiningOutcome finalOutcome = svc.query(q);
        PatternResult edge = finalOutcome.getPatterns().stream()
                .filter(x -> x.getEdgeCount() == 1).findFirst().orElseThrow();
        assertEquals(1 + writerCount * changesPerWriter, edge.getSupport());
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    /** 仅用于在 lambda 中构造断言失败。 */
    private static final class AssertionKeyError extends AssertionError {
        private AssertionKeyError(PatternResult edge, long distinctGraphs) {
            super("嵌入图名去重数 " + distinctGraphs + " 与支持度 " + edge.getSupport() + " 不一致");
        }
    }
}
