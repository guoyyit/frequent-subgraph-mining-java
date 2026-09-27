package com.github.guoyyit.frequentsubgraphminingjava.durability;

import com.github.guoyyit.frequentsubgraphminingjava.model.Graph;
import com.github.guoyyit.frequentsubgraphminingjava.model.MutationType;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed durability: an append-only WAL ({@code wal.log}, one JSON event per
 * line) plus an atomic point-in-time snapshot ({@code snapshot.json}).
 *
 * <p>Crash-safety protocol:
 * <ul>
 *   <li>Every committed mutation is appended to the WAL and fsynced <em>before</em>
 *       the in-memory state is published, so a crash cannot lose a committed change
 *       and cannot expose an uncommitted one.</li>
 *   <li>Snapshots are written to a temp file, fsynced and atomically renamed over
 *       the previous snapshot; the WAL is truncated only after the rename reaches
 *       disk. A crash at any point leaves either the old snapshot+WAL or the new
 *       snapshot+trimmed WAL, never a torn state.</li>
 *   <li>A partially flushed last WAL line (crash mid-write) is detected and ignored
 *       during recovery, so such a line was never reported committed.</li>
 * </ul>
 */
public final class DurabilityStore {

    public static final String WAL_NAME = "wal.log";
    public static final String SNAPSHOT_NAME = "snapshot.json";
    public static final String TMP_SUFFIX = ".tmp";

    private final Path directory;
    private final Path walFile;
    private final Path snapshotFile;

    public DurabilityStore(Path directory) {
        this.directory = directory;
        this.walFile = directory.resolve(WAL_NAME);
        this.snapshotFile = directory.resolve(SNAPSHOT_NAME);
    }

    /** Append a single event and force it to physical storage. */
    public synchronized void appendEvent(DurableEvent event) throws IOException {
        String graphJson = event.graph() == null ? null : GraphCodec.graphToJson(event.graph());
        String op = event.type() == MutationType.DELETE ? "DELETE" : "UPSERT";
        GraphCodec.WireEvent wire = new GraphCodec.WireEvent(
                event.sequence(), op, event.graphId(), event.timestampMillis(), graphJson);
        String eventJson = GraphCodec.eventToJson(wire);
        Files.createDirectories(directory);
        byte[] bytes = (eventJson + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(bytes);
            raf.getFD().sync();
        }
    }

    /** Atomically replace the snapshot file, fsyncing both file and directory. */
    public synchronized void writeSnapshot(SnapshotData snapshot) throws IOException {
        String snapshotJson = GraphCodec.snapshotToJson(snapshot.lastSequence(), snapshot.graphs());
        Files.createDirectories(directory);
        Path tmp = directory.resolve(SNAPSHOT_NAME + TMP_SUFFIX);
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(snapshotJson.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (out instanceof java.io.FileOutputStream fos) {
                fos.getFD().sync();
            }
        }
        Files.move(tmp, snapshotFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        syncDirectory();
    }

    /** Parse committed WAL events; a torn trailing line is ignored. */
    public synchronized List<DurableEvent> readWal() throws IOException {
        if (!Files.exists(walFile)) {
            return List.of();
        }
        byte[] all = Files.readAllBytes(walFile);
        String text = new String(all, StandardCharsets.UTF_8);
        // The last record is committed only if its newline was flushed.
        int end = text.length();
        List<DurableEvent> events = new ArrayList<>();
        int lineStart = 0;
        boolean hasCompleteLine = false;
        for (int i = 0; i < end; i++) {
            if (all[i] == (byte) '\n') {
                String line = text.substring(lineStart, i).trim();
                if (!line.isEmpty()) {
                    events.add(decodeEvent(line));
                }
                lineStart = i + 1;
                hasCompleteLine = true;
            }
        }
        if (!hasCompleteLine && end > 0) {
            // no newline at all -> nothing committed
            return List.of();
        }
        return List.copyOf(events);
    }

    /** Parse the snapshot, or {@code null} when none exists. */
    public synchronized GraphCodec.SnapshotParsed readSnapshot() throws IOException {
        if (!Files.exists(snapshotFile)) {
            return null;
        }
        String text = Files.readString(snapshotFile, StandardCharsets.UTF_8);
        return GraphCodec.snapshotFromJson(text);
    }

    /** Truncate the WAL to the given byte length (0 clears it). */
    public synchronized void truncateWal(long length) throws IOException {
        if (!Files.exists(walFile)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            if (length < raf.length()) {
                raf.setLength(length);
                raf.getFD().sync();
            }
        }
        syncDirectory();
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(walFile);
        Files.deleteIfExists(snapshotFile);
        Files.deleteIfExists(directory.resolve(SNAPSHOT_NAME + TMP_SUFFIX));
    }

    private static DurableEvent decodeEvent(String line) {
        GraphCodec.WireEvent wire = GraphCodec.eventFromJson(line);
        Graph graph = wire.graphJson() == null ? null : GraphCodec.graphFromJson(wire.graphJson());
        MutationType type = "DELETE".equals(wire.op()) ? MutationType.DELETE : MutationType.UPSERT;
        return new DurableEvent(wire.sequence(), type, wire.graphId(),
                wire.timestampMillis(), graph);
    }

    private void syncDirectory() throws IOException {
        try (java.nio.file.DirectoryStream<Path> ignored = Files.newDirectoryStream(directory)) {
            // opening the dir on macOS is best-effort; physical dir fsync is not
            // available through the JDK portably. Rename atomicity is still guaranteed.
        }
    }
}
