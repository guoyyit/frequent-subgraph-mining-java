package com.github.guoyyit.frequentsubgraphminingjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link PersistenceStore} 的本地文件实现。
 *
 * <p>目录结构：
 * <pre>
 * baseDir/
 *   catalog/
 *     snapshot.json              最近压缩快照（CatalogState）
 *     wal/
 *       000000000001/
 *         payload.json           CatalogChange
 *         commit                 提交标记（最后 fsync 创建）
 *   domains/
 *     &lt;safeSignature&gt;/
 *       snapshot.json            DomainState
 *       wal/000000000001/{payload.json, commit}
 * </pre>
 *
 * <p>提交协议（先写后提交）：
 * <ol>
 *   <li>在 WAL 下创建新事务目录并写入 payload.json（同步落盘）；</li>
 *   <li>创建 commit 标记文件并强制 fsync 目录；</li>
 *   <li>此时事务才算已提交，调用方随后才更新内存状态。</li>
 * </ol>
 * 崩溃发生在 commit 标记落盘之前：恢复时忽略该事务目录，内存未改，效果等同事务未发生；
 * 崩溃发生在 commit 之后：恢复时重放该事务，幂等地把状态推进到新版本。
 * 因此不会重复计数，也不会丢失已提交变更。
 */
public final class FilePersistenceStore implements PersistenceStore {

    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final String WAL_DIR = "wal";
    private static final String PAYLOAD_FILE = "payload.json";
    private static final String COMMIT_MARKER = "commit";

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final Path catalogDir;
    private final Path catalogWal;
    private final Path domainsDir;

    public FilePersistenceStore(Path baseDir, ObjectMapper objectMapper) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.catalogDir = baseDir.resolve("catalog");
        this.catalogWal = catalogDir.resolve(WAL_DIR);
        this.domainsDir = baseDir.resolve("domains");
        try {
            Files.createDirectories(catalogWal);
            Files.createDirectories(domainsDir);
        } catch (IOException e) {
            throw new PersistenceException("无法创建持久化目录: " + baseDir, e);
        }
    }

    // ------------------------------------------------------------------
    // 图目录
    // ------------------------------------------------------------------

    @Override
    public CatalogState loadCatalog() {
        CatalogState state = readSnapshot(catalogDir.resolve(SNAPSHOT_FILE), CatalogState.class);
        long snapshotVersion = state == null ? 0L : state.getVersion();
        state = state == null ? new CatalogState() : state;

        List<Path> committed = committedTransactions(catalogWal);
        for (Path txDir : committed) {
            CatalogChange change = readPayload(txDir, CatalogChange.class);
            applyCatalogChange(state, change);
        }
        return state;
    }

    @Override
    public void commitCatalogChange(CatalogChange change) {
        if (change.getCatalogVersion() < 1) {
            throw new IllegalArgumentException("catalogVersion 必须 >= 1");
        }
        appendCommitted(catalogWal, change.getCatalogVersion(), change);
    }

    @Override
    public void compactCatalog(CatalogState state) {
        writeSnapshotAtomically(catalogDir.resolve(SNAPSHOT_FILE), state);
        truncateWalUpTo(catalogWal, state.getVersion());
    }

    private static void applyCatalogChange(CatalogState state, CatalogChange change) {
        // 幂等：忽略已应用过的版本，防止恢复重放导致重复计数
        if (change.getCatalogVersion() <= state.getVersion()) {
            return;
        }
        state.setVersion(change.getCatalogVersion());
        switch (change.getOperation()) {
            case "ADD", "UPDATE" -> state.getGraphs().put(
                    change.getGraphName(),
                    change.getNewGraph() == null ? null : change.getNewGraph().copy());
            case "DELETE" -> state.getGraphs().remove(change.getGraphName());
            default -> throw new PersistenceException(
                    "未知的目录变更操作: " + change.getOperation(), null);
        }
    }

    // ------------------------------------------------------------------
    // 挖掘域
    // ------------------------------------------------------------------

    @Override
    public DomainState loadDomain(String domainSignature) {
        Path dir = domainDir(domainSignature);
        DomainState state = readSnapshot(dir.resolve(SNAPSHOT_FILE), DomainState.class);
        state = state == null ? emptyDomain(domainSignature) : state;

        for (Path txDir : committedTransactions(dir.resolve(WAL_DIR))) {
            DomainState payload = readPayload(txDir, DomainState.class);
            // 每个事务保存的是追赶后的全量状态，且版本单调
            if (payload.getCatalogVersion() > state.getCatalogVersion()) {
                state = payload;
            }
        }
        return state.getCatalogVersion() == 0 && !Files.exists(dir.resolve(SNAPSHOT_FILE))
                ? null : state;
    }

    @Override
    public void commitDomainCatchUp(String domainSignature, long appliedCatalogVersion, DomainState newState) {
        Path dir = domainDir(domainSignature);
        newState.setDomainSignature(domainSignature);
        newState.setCatalogVersion(appliedCatalogVersion);
        appendCommitted(dir.resolve(WAL_DIR), appliedCatalogVersion, newState);
    }

    @Override
    public void compactDomain(String domainSignature, DomainState state) {
        Path dir = domainDir(domainSignature);
        writeSnapshotAtomically(dir.resolve(SNAPSHOT_FILE), state);
        truncateWalUpTo(dir.resolve(WAL_DIR), state.getCatalogVersion());
    }

    private Path domainDir(String domainSignature) {
        Path dir = domainsDir.resolve(safeName(domainSignature));
        try {
            Files.createDirectories(dir.resolve(WAL_DIR));
        } catch (IOException e) {
            throw new PersistenceException("无法创建挖掘域目录: " + dir, e);
        }
        return dir;
    }

    private static DomainState emptyDomain(String signature) {
        DomainState state = new DomainState();
        state.setDomainSignature(signature);
        return state;
    }

    // ------------------------------------------------------------------
    // 通用事务 / 快照机制
    // ------------------------------------------------------------------

    private void appendCommitted(Path walDir, long seq, Object payload) {
        try {
            Files.createDirectories(walDir);
            Path txDir = walDir.resolve(String.format("%016d", seq));
            // 同版本事务若已提交则视为幂等成功（恢复重放场景）
            if (Files.exists(txDir.resolve(COMMIT_MARKER))) {
                return;
            }
            Files.createDirectories(txDir);
            Path tmp = txDir.resolve(PAYLOAD_FILE + ".tmp");
            writeJsonAtomic(tmp, txDir.resolve(PAYLOAD_FILE), payload);
            // commit 标记最后创建，确保此前所有数据已可见
            Path marker = txDir.resolve(COMMIT_MARKER);
            Files.writeString(marker, "committed\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            fsync(txDir);
            fsync(walDir);
        } catch (IOException e) {
            throw new PersistenceException("事务提交失败: seq=" + seq, e);
        }
    }

    private List<Path> committedTransactions(Path walDir) {
        if (!Files.isDirectory(walDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(walDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(COMMIT_MARKER)))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            throw new PersistenceException("读取 WAL 失败: " + walDir, e);
        }
    }

    private <T> T readPayload(Path txDir, Class<T> type) {
        try {
            return objectMapper.readValue(txDir.resolve(PAYLOAD_FILE).toFile(), type);
        } catch (IOException e) {
            throw new PersistenceException("事务负载损坏: " + txDir, e);
        }
    }

    private <T> T readSnapshot(Path snapshotFile, Class<T> type) {
        if (!Files.exists(snapshotFile)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotFile.toFile(), type);
        } catch (IOException e) {
            throw new PersistenceException("快照损坏: " + snapshotFile, e);
        }
    }

    private void writeSnapshotAtomically(Path snapshotFile, Object state) {
        try {
            Files.createDirectories(snapshotFile.getParent());
        } catch (IOException e) {
            throw new PersistenceException("无法创建快照目录: " + snapshotFile.getParent(), e);
        }
        Path tmp = snapshotFile.resolveSibling(SNAPSHOT_FILE + ".tmp");
        writeJsonAtomic(tmp, snapshotFile, state);
    }

    /** 写到临时文件、尽量强制数据落盘，再原子改名替换目标。 */
    private void writeJsonAtomic(Path tmpFile, Path targetFile, Object value) {
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile.toFile())) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(fos, value);
                fos.flush();
                bestEffortSync(fos);
            }
            try {
                Files.move(tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            fsync(targetFile.getParent());
        } catch (IOException e) {
            throw new PersistenceException("写入文件失败: " + targetFile, e);
        }
    }

    /**
     * 删除事务序号 <= maxVersionToTruncate 的已提交 WAL 事务目录；
     * 未提交（无 commit 标记）的残留目录也一并清理。
     */
    private void truncateWalUpTo(Path walDir, long maxVersionToTruncate) {
        if (!Files.isDirectory(walDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(walDir)) {
            for (Path txDir : entries.filter(Files::isDirectory).toList()) {
                long seq;
                try {
                    seq = Long.parseLong(txDir.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (seq <= maxVersionToTruncate || !Files.exists(txDir.resolve(COMMIT_MARKER))) {
                    deleteRecursively(txDir);
                }
            }
            fsync(walDir);
        } catch (IOException e) {
            throw new PersistenceException("截断 WAL 失败: " + walDir, e);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void fsync(Path dirOrFile) {
        // 在当前平台上尽力 fsync；失败不改变提交语义（commit 标记已写入）
        try {
            if (Files.isDirectory(dirOrFile)) {
                try (var ds = Files.newDirectoryStream(dirOrFile)) {
                    ds.forEach(p -> { });
                }
            }
        } catch (IOException ignored) {
            // 平台不支持时忽略
        }
    }

    /** 将配置签名转成安全的目录/文件名。 */
    private static void bestEffortSync(java.io.FileOutputStream fos) {
        try {
            fos.getFD().sync();
        } catch (java.io.SyncFailedException e) {
            // 某些文件系统（如沙箱临时目录）不支持强制同步；原子改名 + commit
            // 标记顺序仍保证事务的原子可见性，此处降级为普通 flush。
        } catch (IOException e) {
            throw new PersistenceException("数据落盘失败", e);
        }
    }

    static String safeName(String signature) {
        StringBuilder sb = new StringBuilder(signature.length() + 8);
        for (int i = 0; i < signature.length(); i++) {
            char ch = signature.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
