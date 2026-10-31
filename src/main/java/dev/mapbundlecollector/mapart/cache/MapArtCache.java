package dev.mapbundlecollector.mapart.cache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// Persistent tile-presence + color cache under <gameDir>/mapbundlecollector/. Pure Java so it
// can run on any thread. The cache survives restarts until explicitly deleted; close() only
// flushes pending metas and stops the writer thread.
public final class MapArtCache implements AutoCloseable {
    public static final int COLOR_BYTES = 16384;
    private static final long SAVE_DEBOUNCE_MS = 2500;
    private static final int SAFE_ID_MAX_LEN = 80;
    private static final String WRITER_THREAD_NAME = "mapbundlecollector-cache-writer";

    private static volatile BiConsumer<String, Throwable> logger = (message, error) -> {};

    private final Path rootDir;
    private final Path cacheDir;
    // Guards projects/dirtyProjects/colorHashes/pendingFlush.
    private final Object lock = new Object();
    // Serializes meta.json writes between the writer thread and flushSync callers.
    private final Object flushLock = new Object();
    private final Map<String, ProjectCache> projects = new HashMap<>();
    // "projectId|r_c" -> crc32 of last written colors; prevents disk write churn.
    private final Map<String, Long> colorHashes = new HashMap<>();
    // mapId -> location of a tile whose colors are on disk (reverse index for "is this map
    // already cached?"), plus the inverse map so a location whose mapId changes can drop its
    // stale entry in O(1). A mapId listed in meta.json but without a tiles/r_c.bin file is
    // deliberately NOT indexed here.
    private final Map<Integer, TileLocation> mapIdIndex = new HashMap<>();
    private final Map<String, Integer> indexedLocations = new HashMap<>();
    private final Set<String> dirtyProjects = new HashSet<>();
    private final ScheduledExecutorService writer;
    private final AtomicReference<Thread> writerThread = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> pendingFlush;

    public static void setLogger(BiConsumer<String, Throwable> newLogger) {
        logger = newLogger == null ? (message, error) -> {} : newLogger;
    }

    private MapArtCache(Path rootDir) throws IOException {
        this.rootDir = rootDir;
        this.cacheDir = rootDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Files.createDirectories(rootDir.resolve("missing"));
        this.writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, WRITER_THREAD_NAME);
            thread.setDaemon(true);
            writerThread.set(thread);
            return thread;
        });
        loadAll();
    }

    public static MapArtCache open(Path rootDir) throws IOException {
        return new MapArtCache(rootDir);
    }

    private void loadAll() {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(cacheDir)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path meta = dir.resolve("meta.json");
                if (!Files.isRegularFile(meta)) continue;
                try {
                    ProjectCache project = parseMeta(Files.readString(meta, StandardCharsets.UTF_8));
                    synchronized (lock) {
                        projects.put(project.getProjectId(), project);
                        indexLoadedProjectLocked(project, dir);
                    }
                } catch (Exception error) {
                    log("Corrupt cache meta " + meta + ", renaming", error);
                    try {
                        Files.move(meta, dir.resolve("meta.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException renameError) {
                        log("Failed renaming corrupt meta " + meta, renameError);
                    }
                }
            }
        } catch (IOException error) {
            log("Failed scanning cache dir " + cacheDir, error);
        }
    }

    public Path getRootDir() {
        return rootDir;
    }

    public ProjectCache getOrCreateProject(String projectId) {
        synchronized (lock) {
            return projectLocked(projectId);
        }
    }

    public Collection<ProjectCache> allProjects() {
        synchronized (lock) {
            return new ArrayList<>(projects.values());
        }
    }

    public ProjectCache getProject(String projectId) {
        synchronized (lock) {
            return projects.get(projectId);
        }
    }

    // True when any project tile with this mapId has its colors on disk (tiles/r_c.bin).
    public boolean hasColorsByMapId(Integer mapId) {
        if (mapId == null) return false;
        synchronized (lock) {
            return mapIdIndex.containsKey(mapId);
        }
    }

    public void putTile(String projectId, int row, int col, String name, Integer mapId) {
        if (projectId == null || row < 0 || col < 0) return;
        String safeName = name == null ? "" : name;
        synchronized (lock) {
            if (closed.get()) return;
            ProjectCache project = projectLocked(projectId);
            CachedTileInfo existing = project.getTile(row, col);
            if (existing != null && existing.name.equals(safeName) && Objects.equals(existing.mapId, mapId)) return;
            project.putTileInfo(new CachedTileInfo(row, col, safeName, mapId));
            markDirtyLocked(projectId);
            updateLocationIndexLocked(projectId, row, col, mapId, colorFileExists(projectId, row, col));
        }
    }

    public void setExpectedDims(String projectId, int rows, int cols) {
        if (projectId == null) return;
        synchronized (lock) {
            if (closed.get()) return;
            ProjectCache project = projectLocked(projectId);
            int newRows = Math.max(0, rows);
            int newCols = Math.max(0, cols);
            if (newRows == 0 && newCols == 0) return;
            boolean changed = false;
            if (newRows != 0 && newRows != project.getExpectedRows()) {
                project.setExpectedRows(newRows);
                changed = true;
            }
            if (newCols != 0 && newCols != project.getExpectedCols()) {
                project.setExpectedCols(newCols);
                changed = true;
            }
            if (changed) markDirtyLocked(projectId);
        }
    }

    public void putColors(String projectId, int row, int col, byte[] colors) {
        if (projectId == null || row < 0 || col < 0 || colors == null || colors.length != COLOR_BYTES) return;
        if (isBlank(colors)) return;
        CRC32 crc = new CRC32();
        crc.update(colors);
        long hash = crc.getValue();
        String hashKey = projectId + "|" + row + "_" + col;
        byte[] copy = colors.clone();
        synchronized (lock) {
            if (closed.get()) return;
            Long known = colorHashes.get(hashKey);
            if (known != null && known == hash) return;
            ProjectCache project = projectLocked(projectId);
            boolean newTile = project.getTile(row, col) == null;
            if (newTile) project.putTileInfo(new CachedTileInfo(row, col, "", null));
            writer.execute(() -> writeTileColors(projectId, row, col, copy, hash, hashKey, newTile));
        }
    }

    // Background-thread safe: tmp + atomic move means readers see old or new, never partial.
    public byte[] readColors(String projectId, int row, int col) {
        if (projectId == null || row < 0 || col < 0) return null;
        Path file = cacheDir.resolve(safeProjectId(projectId)).resolve("tiles").resolve(row + "_" + col + ".bin");
        if (!Files.isRegularFile(file)) return null;
        byte[] buf = new byte[COLOR_BYTES];
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 8192)) {
            int off = 0;
            while (off < COLOR_BYTES) {
                int n = in.read(buf, off, COLOR_BYTES - off);
                if (n < 0) break;
                off += n;
            }
            if (off != COLOR_BYTES || in.read() != -1) return null;
            return buf;
        } catch (IOException error) {
            return null;
        }
    }

    // A map state the client holds but has never received real pixels for reports a full
    // 16384-byte array of zeros (color 0 = unset). Caching or rendering that would poison
    // tiles as "blank forever", so it is treated as absent everywhere. Scanning stops at the
    // first non-zero byte, so real map art (which always has non-zero pixels) costs ~nothing.
    public static boolean isBlank(byte[] colors) {
        if (colors == null) return true;
        for (byte color : colors) {
            if (color != 0) return false;
        }
        return true;
    }

    public void requestSave() {
        synchronized (lock) {
            if (closed.get() || dirtyProjects.isEmpty()) return;
            scheduleSaveLocked();
        }
    }

    // Writes all dirty metas on the calling thread. Drains pending tile writes first so metas
    // never list tiles whose color files are not on disk yet. Safe to call repeatedly.
    public void flushSync() {
        if (Thread.currentThread() != writerThread.get()) {
            try {
                writer.submit(() -> {}).get(60, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // Writer already shut down; fall through and write directly.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Writer stalled; write metas on this thread anyway.
            }
        }
        writeDirtyMetas();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) return;
            if (pendingFlush != null) pendingFlush.cancel(false);
        }
        flushSync();
        writer.shutdown();
        try {
            writer.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public void deleteProject(String projectId) {
        Path dir = cacheDir.resolve(safeProjectId(projectId));
        synchronized (lock) {
            projects.remove(projectId);
            dirtyProjects.remove(projectId);
            colorHashes.keySet().removeIf(k -> k.startsWith(projectId + "|"));
            removeProjectIndexLocked(projectId);
        }
        runOnWriterOrHere(() -> deleteRecursively(dir, "project dir " + dir));
    }

    public void deleteAll() {
        synchronized (lock) {
            projects.clear();
            dirtyProjects.clear();
            colorHashes.clear();
            mapIdIndex.clear();
            indexedLocations.clear();
        }
        runOnWriterOrHere(() -> {
            deleteRecursively(cacheDir, "cache dir");
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException error) {
                log("Failed recreating cache dir " + cacheDir, error);
            }
        });
    }

    public boolean isDirty() {
        synchronized (lock) {
            return !dirtyProjects.isEmpty();
        }
    }

    private ProjectCache projectLocked(String projectId) {
        return projects.computeIfAbsent(projectId, ProjectCache::new);
    }

    private void markDirtyLocked(String projectId) {
        dirtyProjects.add(projectId);
        scheduleSaveLocked();
    }

    private void scheduleSaveLocked() {
        if (closed.get()) return;
        if (pendingFlush != null) pendingFlush.cancel(false);
        pendingFlush = writer.schedule(this::runFlush, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void runFlush() {
        synchronized (lock) {
            pendingFlush = null;
        }
        writeDirtyMetas();
    }

    // Serialized by flushLock: only one thread (writer or a flushSync caller) writes metas at a
    // time, so the shared meta.json.tmp name never collides.
    private void writeDirtyMetas() {
        synchronized (flushLock) {
            List<String> ids;
            List<String> payloads;
            synchronized (lock) {
                if (dirtyProjects.isEmpty()) return;
                ids = new ArrayList<>(dirtyProjects);
                payloads = new ArrayList<>(ids.size());
                for (String id : ids) {
                    ProjectCache project = projects.get(id);
                    if (project == null) {
                        dirtyProjects.remove(id);
                        payloads.add(null);
                        continue;
                    }
                    StringBuilder sb = new StringBuilder(64 + project.tileCount() * 48);
                    writeMetaJson(project, sb);
                    payloads.add(sb.toString());
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                if (payloads.get(i) == null) continue;
                String id = ids.get(i);
                try {
                    writeMetaFile(safeProjectId(id), payloads.get(i));
                    synchronized (lock) {
                        dirtyProjects.remove(id);
                    }
                } catch (IOException error) {
                    log("Failed writing meta for " + id, error); // stays dirty, retried next flush
                }
            }
        }
    }

    private static void writeMetaJson(ProjectCache project, StringBuilder sb) {
        sb.append("{\"projectId\":");
        Json.escape(sb, project.getProjectId());
        sb.append(",\"expectedRows\":").append(project.getExpectedRows());
        sb.append(",\"expectedCols\":").append(project.getExpectedCols());
        sb.append(",\"tiles\":[");
        boolean first = true;
        for (CachedTileInfo tile : project.getTiles().values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"r\":").append(tile.row)
                .append(",\"c\":").append(tile.col)
                .append(",\"name\":");
            Json.escape(sb, tile.name);
            sb.append(",\"mapId\":");
            if (tile.mapId == null) sb.append("null");
            else sb.append(tile.mapId.intValue());
            sb.append('}');
        }
        sb.append("]}");
    }

    private void writeMetaFile(String safeId, String json) throws IOException {
        Path dir = cacheDir.resolve(safeId);
        Files.createDirectories(dir);
        Path tmp = dir.resolve("meta.json.tmp");
        Path target = dir.resolve("meta.json");
        atomicWrite(tmp, target, json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeTileColors(String projectId, int row, int col, byte[] colors, long hash, String hashKey, boolean newTile) {
        synchronized (lock) {
            if (!projects.containsKey(projectId)) return; // project deleted meanwhile
        }
        Path dir = cacheDir.resolve(safeProjectId(projectId)).resolve("tiles");
        String base = row + "_" + col;
        Path tmp = dir.resolve(base + ".bin.tmp");
        Path target = dir.resolve(base + ".bin");
        try {
            Files.createDirectories(dir);
            try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
                out.write(colors);
            }
            atomicWriteMoveOnly(tmp, target);
            synchronized (lock) {
                colorHashes.put(hashKey, hash);
                if (newTile) markDirtyLocked(projectId);
                ProjectCache project = projects.get(projectId);
                CachedTileInfo tile = project == null ? null : project.getTile(row, col);
                updateLocationIndexLocked(projectId, row, col, tile == null ? null : tile.mapId, true);
            }
        } catch (IOException error) {
            log("Failed writing colors for " + projectId + " tile " + base, error);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {}
        }
    }

    // Reverse-index bootstrap for one loaded project: only meta tiles whose r_c.bin actually
    // exists count as "has colors" (a directory listing at open, per the fetcher contract).
    private void indexLoadedProjectLocked(ProjectCache project, Path projectDir) {
        Set<String> present = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(projectDir.resolve("tiles"), "*.bin")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String base = name.substring(0, name.length() - 4);
                int split = base.indexOf('_');
                if (split <= 0 || !isDigits(base, 0, split) || !isDigits(base, split + 1, base.length())) continue;
                present.add(base);
            }
        } catch (IOException error) {
            return; // no tiles dir (or unreadable): nothing with colors on disk
        }
        for (CachedTileInfo tile : project.getTiles().values()) {
            if (tile.mapId != null && present.contains(tile.row + "_" + tile.col)) {
                updateLocationIndexLocked(project.getProjectId(), tile.row, tile.col, tile.mapId, true);
            }
        }
    }

    private static boolean isDigits(String s, int from, int to) {
        if (from >= to) return false;
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private boolean colorFileExists(String projectId, int row, int col) {
        return Files.isRegularFile(colorFile(projectId, row, col));
    }

    private Path colorFile(String projectId, int row, int col) {
        return cacheDir.resolve(safeProjectId(projectId)).resolve("tiles").resolve(row + "_" + col + ".bin");
    }

    // Points mapId at this tile location, or clears the location's entry when it no longer
    // qualifies (no mapId / colors gone). Stale mapIds from a previous owner of the location
    // are dropped only while they still point here, so re-indexed entries survive.
    private void updateLocationIndexLocked(String projectId, int row, int col, Integer mapId, boolean colorFilePresent) {
        String locationKey = projectId + "|" + row + "_" + col;
        Integer previous = indexedLocations.get(locationKey);
        if (mapId == null || !colorFilePresent) {
            if (previous != null && pointsTo(mapIdIndex.get(previous), projectId, row, col)) {
                mapIdIndex.remove(previous);
            }
            indexedLocations.remove(locationKey);
            return;
        }
        if (previous != null && !previous.equals(mapId) && pointsTo(mapIdIndex.get(previous), projectId, row, col)) {
            mapIdIndex.remove(previous);
        }
        indexedLocations.put(locationKey, mapId);
        mapIdIndex.put(mapId, new TileLocation(projectId, row, col));
    }

    private void removeProjectIndexLocked(String projectId) {
        String prefix = projectId + "|";
        mapIdIndex.values().removeIf(location -> location.projectId().equals(projectId));
        indexedLocations.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static boolean pointsTo(TileLocation location, String projectId, int row, int col) {
        return location != null && location.projectId().equals(projectId) && location.row() == row && location.col() == col;
    }

    private static void atomicWrite(Path tmp, Path target, byte[] bytes) throws IOException {
        Files.write(tmp, bytes);
        atomicWriteMoveOnly(tmp, target);
    }

    private static void atomicWriteMoveOnly(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runOnWriterOrHere(Runnable task) {
        if (closed.get() || Thread.currentThread() == writerThread.get()) {
            task.run();
            return;
        }
        try {
            writer.execute(task);
        } catch (RejectedExecutionException error) {
            task.run();
        }
    }

    private static void deleteRecursively(Path path, String what) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) deleteRecursively(child, what);
        } catch (IOException ignored) {
            // Not a directory or unreadable; try plain delete below.
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            log("Failed deleting " + what + " entry " + path, error);
        }
    }

    // Public wrapper so the GUI chunk renderer can build texture ids with the exact same
    // sanitizing rules the cache uses for directories.
    public static String sanitizeProjectId(String projectId) {
        return safeProjectId(projectId);
    }

    // Filesystem-safe project id: lowercase, keep [a-z0-9_.-], others -> '_', collapse runs,
    // trim '_', fallback "unnamed", cap 80 chars.
    static String safeProjectId(String projectId) {
        String s = projectId == null ? "" : projectId.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (c == '_' || !keep) {
                if (!lastUnderscore) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            } else {
                sb.append(c);
                lastUnderscore = false;
            }
        }
        String result = sb.toString();
        int start = 0;
        int end = result.length();
        while (start < end && result.charAt(start) == '_') start++;
        while (end > start && result.charAt(end - 1) == '_') end--;
        result = result.substring(start, Math.min(end, start + SAFE_ID_MAX_LEN));
        while (result.endsWith("_")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? "unnamed" : result;
    }

    private static ProjectCache parseMeta(String text) {
        Json.Parser p = new Json.Parser(text);
        String projectId = null;
        int expectedRows = 0;
        int expectedCols = 0;
        List<CachedTileInfo> tiles = new ArrayList<>();
        p.ws();
        p.expect('{');
        p.ws();
        if (p.peek() != '}') {
            while (true) {
                String key = p.string();
                p.ws();
                p.expect(':');
                p.ws();
                switch (key) {
                    case "projectId" -> projectId = p.string();
                    case "expectedRows" -> expectedRows = (int) p.number();
                    case "expectedCols" -> expectedCols = (int) p.number();
                    case "tiles" -> parseTiles(p, tiles);
                    default -> p.skipValue();
                }
                p.ws();
                if (p.peek() == ',') {
                    p.next();
                    p.ws();
                    continue;
                }
                break;
            }
        }
        p.expect('}');
        if (p.hasMore()) throw new Json.JsonException("trailing content in meta.json");
        if (projectId == null || projectId.isBlank()) throw new Json.JsonException("missing projectId");
        ProjectCache project = new ProjectCache(projectId);
        project.setExpectedRows(expectedRows);
        project.setExpectedCols(expectedCols);
        for (CachedTileInfo tile : tiles) project.putTileInfo(tile);
        return project;
    }

    private static void parseTiles(Json.Parser p, List<CachedTileInfo> tiles) {
        p.expect('[');
        p.ws();
        if (p.peek() != ']') {
            while (true) {
                tiles.add(parseTile(p));
                p.ws();
                if (p.peek() == ',') {
                    p.next();
                    p.ws();
                    continue;
                }
                break;
            }
        }
        p.expect(']');
    }

    private static CachedTileInfo parseTile(Json.Parser p) {
        int row = 0;
        int col = 0;
        String name = "";
        Integer mapId = null;
        p.expect('{');
        p.ws();
        if (p.peek() != '}') {
            while (true) {
                String key = p.string();
                p.ws();
                p.expect(':');
                p.ws();
                switch (key) {
                    case "r" -> row = (int) p.number();
                    case "c" -> col = (int) p.number();
                    case "name" -> name = p.isNull() ? "" : p.string();
                    case "mapId" -> mapId = p.isNull() ? null : (int) p.number();
                    default -> p.skipValue();
                }
                p.ws();
                if (p.peek() == ',') {
                    p.next();
                    p.ws();
                    continue;
                }
                break;
            }
        }
        p.expect('}');
        return new CachedTileInfo(row, col, name, mapId);
    }

    private static void log(String message, Throwable error) {
        try {
            logger.accept(message, error);
        } catch (RuntimeException ignored) {
            // Never let logging break the cache.
        }
    }
}
