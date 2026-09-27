package com.github.guoyyit.frequentsubgraphminingjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledEdge;
import com.github.guoyyit.frequentsubgraphminingjava.graph.LabeledNode;
import com.github.guoyyit.frequentsubgraphminingjava.graph.PropertyGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePersistenceStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PropertyGraph graph(String name) {
        PropertyGraph g = new PropertyGraph(name);
        g.addNode(new LabeledNode("a", "A"));
        g.addNode(new LabeledNode("b", "B"));
        g.addEdge(new LabeledEdge("a", "b", "r"));
        return g;
    }

    private CatalogChange change(long v, String op, String name, PropertyGraph n, PropertyGraph o) {
        CatalogChange c = new CatalogChange();
        c.setCatalogVersion(v);
        c.setOperation(op);
        c.setGraphName(name);
        c.setNewGraph(n);
        c.setOldGraph(o);
        return c;
    }

    @Test
    void committedChangesAreReplayedOnReopen(@TempDir Path dir) {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        store.commitCatalogChange(change(1, "ADD", "g1", graph("g1"), null));
        store.commitCatalogChange(change(2, "ADD", "g2", graph("g2"), null));

        FilePersistenceStore reopened = new FilePersistenceStore(dir, mapper);
        CatalogState state = reopened.loadCatalog();
        assertEquals(2, state.getVersion());
        assertEquals(2, state.getGraphs().size());
        assertTrue(state.getGraphs().containsKey("g1"));
        assertTrue(state.getGraphs().containsKey("g2"));
    }

    @Test
    void uncommittedTransactionIsDiscardedOnRecovery(@TempDir Path dir) throws IOException {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        store.commitCatalogChange(change(1, "ADD", "g1", graph("g1"), null));

        // 模拟在 commit 标记落盘前崩溃：只留下 payload，没有 commit
        Path wal = dir.resolve("catalog/wal");
        Path tx = wal.resolve("0000000000000002");
        Files.createDirectories(tx);
        Files.writeString(tx.resolve("payload.json"),
                mapper.writeValueAsString(change(2, "DELETE", "g1", null, graph("g1"))),
                StandardCharsets.UTF_8);

        CatalogState state = new FilePersistenceStore(dir, mapper).loadCatalog();
        assertEquals(1, state.getVersion());
        assertTrue(state.getGraphs().containsKey("g1"));
    }

    @Test
    void replayIsIdempotent(@TempDir Path dir) {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        store.commitCatalogChange(change(1, "ADD", "g1", graph("g1"), null));
        // 再次加载不会把版本 1 重复应用
        CatalogState first = new FilePersistenceStore(dir, mapper).loadCatalog();
        CatalogState second = new FilePersistenceStore(dir, mapper).loadCatalog();
        assertEquals(1, first.getVersion());
        assertEquals(1, second.getVersion());
        assertEquals(1, second.getGraphs().size());
    }

    @Test
    void snapshotCompactionTruncatesWal(@TempDir Path dir) {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        store.commitCatalogChange(change(1, "ADD", "g1", graph("g1"), null));
        store.commitCatalogChange(change(2, "ADD", "g2", graph("g2"), null));
        CatalogState state = store.loadCatalog();
        store.compactCatalog(state);

        Path wal = dir.resolve("catalog/wal");
        try (var s = Files.list(wal)) {
            assertEquals(0, s.count());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CatalogState recovered = new FilePersistenceStore(dir, mapper).loadCatalog();
        assertEquals(2, recovered.getVersion());
        assertEquals(2, recovered.getGraphs().size());
    }

    @Test
    void domainStateCanBeCommittedAndRecovered(@TempDir Path dir) {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        DomainState ds = new DomainState();
        PatternEntry entry = new PatternEntry();
        entry.setCanonicalCode("v[A]e[]");
        entry.setEdgeCount(0);
        entry.setSupportingGraphs(new java.util.ArrayList<>(List.of("g1", "g2")));
        ds.getPatterns().put(entry.getCanonicalCode(), entry);
        store.commitDomainCatchUp("sig", 3, ds);

        DomainState loaded = new FilePersistenceStore(dir, mapper).loadDomain("sig");
        assertEquals(3, loaded.getCatalogVersion());
        assertEquals(1, loaded.getPatterns().size());
        assertEquals(List.of("g1", "g2"), loaded.getPatterns().get("v[A]e[]").getSupportingGraphs());
    }

    @Test
    void missingDomainLoadsAsNull(@TempDir Path dir) {
        FilePersistenceStore store = new FilePersistenceStore(dir, mapper);
        assertNull(store.loadDomain("never-seen"));
    }
}
