package dev.mapbundlecollector.mapart;

import dev.mapbundlecollector.mapart.cache.CachedTileInfo;
import dev.mapbundlecollector.mapart.cache.MapArtCache;
import dev.mapbundlecollector.mapart.cache.MissingTileExporter;
import dev.mapbundlecollector.mapart.cache.ProjectCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

// Thin orchestrator holding map-art state only: parsed projects, the active project, and the
// persistent cache. It contains NO gui/rendering code; the chunk renderer pulls data from here
// and auto-detects changes via getDataVersion()/getActiveSet().
public final class MapArtManager {
    private static final MapArtManager INSTANCE = new MapArtManager();

    private final List<MapArtSet> projectSets = new ArrayList<>();
    private int currentProjectIndex = 0;
    private MapArtSet activeSet;

    // Nullable when the cache could not be opened; everything degrades gracefully without it.
    private MapArtCache cache;
    // Incremented on ANY content change; the chunk renderer uses it as its invalidation signal.
    private int dataVersion = 0;
    // Cheap change gate: skip reparsing while the inventory signature is unchanged. A
    // periodic bypass (~every 10 scans) still re-reads map data because map packets can
    // fill in a tile's colors AFTER the item enters the inventory.
    private static final int PERIODIC_REFRESH_SCANS = 10;
    private int lastInventorySignature;
    private int scansSinceRefresh;
    private int lastStructuralSignature;
    private boolean scannedOnce = false;

    // Map-packet capture (fed by ClientPacketListenerMixin): mapId -> full 16384-byte color
    // snapshot. Values are private copies; entries are removed once persisted to the disk
    // cache, and the store is bounded so a packet storm cannot grow the heap uncontrolled.
    private static final int CAPTURE_LIMIT = 4096;
    private final ConcurrentHashMap<Integer, byte[]> capturedColors = new ConcurrentHashMap<>();

    // Bundled maps often have no client-side data because the server never sends it. If
    // Ev's Mod is installed, its per-server map cache can inject previously-seen map states
    // into the client world; retry occasionally while live tiles are still missing data.
    private static final long BRIDGE_RETRY_MS = 30_000;
    private long lastBridgeAttemptMs;

    public static MapArtManager get() {
        return INSTANCE;
    }

    private MapArtManager() {}

    // Opens <gameDir>/mapbundlecollector and loads every cached project as a lightweight
    // (cache-only) set. Safe to call during addon init; reads no module settings.
    public void initialize(Path gameDir) {
        if (cache != null) return;
        try {
            cache = MapArtCache.open(gameDir.resolve("mapbundlecollector"));
        } catch (IOException error) {
            cache = null;
            return;
        }
        for (ProjectCache project : cache.allProjects()) {
            projectSets.add(cacheOnlySet(project));
        }
        if (!projectSets.isEmpty()) {
            dataVersion++;
        }
    }

    public void forceUpdate(LocalPlayer player) {
        scannedOnce = false;
        update(player);
    }

    public void update(LocalPlayer player) {
        MapArtViewerSettings settings = lookupSettings();
        boolean persist = settings.persistentCache() && cache != null;

        if (player == null || Minecraft.getInstance().level == null) {
            if (!settings.keepLoaded() && !persist) {
                clear();
            }
            return;
        }

        InventoryScanner.ScanResult scan = InventoryScanner.scan(player);
        boolean periodicDue = ++scansSinceRefresh >= PERIODIC_REFRESH_SCANS;
        if (scannedOnce && scan.signature() == lastInventorySignature && !periodicDue) return;
        scannedOnce = true;
        scansSinceRefresh = 0;
        lastInventorySignature = scan.signature();

        rebuildSets(scan.maps(), settings, persist);
        // Bump the renderer's invalidation version only when something actually changed
        // (late map data flips hasData and is caught here too).
        int signature = structuralSignature();
        if (signature != lastStructuralSignature) {
            lastStructuralSignature = signature;
            dataVersion++;
        }
        maybeBridgeEvMod(settings);
    }

    // Called on the client thread after vanilla applied a map packet: snapshot the merged
    // colors for this map id so bundled tiles can render/persist them later.
    public void onMapDataReceived(int mapId, byte[] mergedColors) {
        if (mergedColors == null || mergedColors.length < MapArtCache.COLOR_BYTES) return;
        byte[] copy = mergedColors.clone();
        if (MapArtCache.isBlank(copy)) return;
        if (capturedColors.size() >= CAPTURE_LIMIT) capturedColors.clear();
        capturedColors.put(mapId, copy);
    }

    // Background-thread safe lookup used by the chunk renderer's color source.
    public byte[] getCapturedColors(Integer mapId) {
        return mapId == null ? null : capturedColors.get(mapId);
    }

    private void maybeBridgeEvMod(MapArtViewerSettings settings) {
        if (!settings.evmodBridge() || System.currentTimeMillis() - lastBridgeAttemptMs < BRIDGE_RETRY_MS) return;
        if (!liveTilesMissingData()) return;
        lastBridgeAttemptMs = System.currentTimeMillis();
        if (EvModBridge.injectCachedMapStates()) {
            scannedOnce = false; // re-resolve map states the injection just made available
        }
    }

    private boolean liveTilesMissingData() {
        for (MapArtSet set : projectSets) {
            for (MapTile tile : set.getTiles().values()) {
                if (tile.isLive()
                    && (!tile.hasData() || MapArtCache.isBlank(tile.getColors()))
                    && getCapturedColors(tile.mapId()) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    // Structural fingerprint over all projects - never hashes full color payloads. Detects
    // tile additions, dimension changes, live/cache transitions, hasData flips, and (via a
    // fixed byte sample) in-place color updates on live tiles, for O(present) per scan.
    private static final int[] COLOR_SAMPLE_OFFSETS = {0, 2048, 4096, 6144, 8192, 10240, 12288, 14336, 16383};

    private int structuralSignature() {
        int hash = projectSets.size();
        for (MapArtSet set : projectSets) {
            hash = 31 * hash + set.getProjectId().toLowerCase(Locale.ROOT).hashCode();
            hash = 31 * hash + set.getRows();
            hash = 31 * hash + set.getCols();
            hash = 31 * hash + set.getPresentCount();
            for (MapTile tile : set.getTiles().values()) {
                hash = 31 * hash + tile.row() * 1000 + tile.col();
                hash = 31 * hash + (tile.isLive() ? 1 : 0);
                hash = 31 * hash + (tile.hasData() ? 1 : 0);
                hash = 31 * hash + (tile.mapId() == null ? 0 : tile.mapId());
                if (tile.isLive() && tile.hasData()) {
                    byte[] colors = tile.getColors();
                    for (int offset : COLOR_SAMPLE_OFFSETS) {
                        hash = 31 * hash + colors[offset];
                    }
                }
            }
        }
        return hash;
    }

    private void rebuildSets(List<InventoryScanner.ScannedMap> scanned, MapArtViewerSettings settings, boolean persist) {
        boolean keepLoaded = settings.keepLoaded();
        Map<String, List<PendingTile>> groups = new HashMap<>();
        for (InventoryScanner.ScannedMap map : scanned) {
            MapNameParser.ParsedTile parsed = MapNameParser.parse(map.rawName(), settings.xyFirst());
            if (parsed == null) continue;
            groups.computeIfAbsent(parsed.projectId(), k -> new ArrayList<>()).add(new PendingTile(parsed, map));
        }

        Map<String, MapArtSet> previousById = new HashMap<>();
        for (MapArtSet set : projectSets) {
            previousById.putIfAbsent(set.getProjectId().toLowerCase(Locale.ROOT), set);
        }

        List<MapArtSet> next = new ArrayList<>();
        Set<String> rescanned = new HashSet<>();
        for (Map.Entry<String, List<PendingTile>> entry : groups.entrySet()) {
            String projectId = entry.getKey();
            next.add(buildSet(projectId, entry.getValue(),
                previousById.get(projectId.toLowerCase(Locale.ROOT)), settings, persist));
            rescanned.add(projectId.toLowerCase(Locale.ROOT));
        }
        for (MapArtSet existing : projectSets) {
            if (rescanned.contains(existing.getProjectId().toLowerCase(Locale.ROOT))) continue;
            if (persist) {
                // Disk-backed sets survive with an empty inventory (as cache-only views).
                ProjectCache project = cache.getProject(existing.getProjectId());
                next.add(project != null && project.tileCount() > 0
                    ? cacheOnlySet(project)
                    : existing);
            } else if (keepLoaded && !existing.isCacheOrigin()) {
                next.add(existing);
            }
            // Both flags off: drop the set (today's behavior).
        }

        projectSets.clear();
        projectSets.addAll(next);
        projectSets.sort((a, b) -> Integer.compare(b.getPresentCount(), a.getPresentCount()));
        refreshActiveSet();
    }

    // Builds a fresh set for a scanned project: disk presence floor, optional memory carry-over
    // from earlier batches, then the live inventory tiles. Rebuilding from scratch each changed
    // scan releases stale ItemStack references from tiles that left the inventory.
    private MapArtSet buildSet(String projectId, List<PendingTile> pending, MapArtSet previous, MapArtViewerSettings settings, boolean persist) {
        int maxRow = 0;
        int maxCol = 0;
        for (PendingTile tile : pending) {
            maxRow = Math.max(maxRow, tile.parsed().row());
            maxCol = Math.max(maxCol, tile.parsed().col());
        }
        MapArtSet set = new MapArtSet(projectId, maxRow + 1, maxCol + 1);

        // The disk floor only applies when persistence is enabled; with it off, the session
        // must behave like a fresh start even though the cache dir still exists.
        ProjectCache projectCache = persist && cache != null ? cache.getProject(projectId) : null;

        // Expected dims: explicit module settings win; otherwise adopt the cached ones.
        int expRows = settings.mapHeight();
        int expCols = settings.mapWidth();
        if (projectCache != null) {
            if (expRows <= 0) expRows = projectCache.getExpectedRows();
            if (expCols <= 0) expCols = projectCache.getExpectedCols();
        }
        if (expRows > 0 || expCols > 0) {
            if (cache != null) cache.setExpectedDims(projectId, expRows, expCols);
            set.setExpectedDims(expRows, expCols);
        }

        // Disk-backed presence floor (covers tiles seen in earlier batches/sessions).
        boolean diskBacked = projectCache != null && projectCache.tileCount() > 0;
        if (diskBacked) {
            for (CachedTileInfo info : projectCache.getTiles().values()) {
                set.addTile(new MapTile(info.row, info.col, info.name, info.mapId, null, null));
            }
        }

        // Without a disk floor, keepLoaded still preserves earlier batches from memory.
        // Cache-origin previous sets are ignored when persistence is off.
        if (!diskBacked && settings.keepLoaded() && previous != null && !(previous.isCacheOrigin() && !persist)) {
            Set<String> liveKeys = new HashSet<>();
            for (PendingTile tile : pending) {
                liveKeys.add(tile.parsed().row() + "_" + tile.parsed().col());
            }
            for (MapTile tile : previous.getTiles().values()) {
                if (!liveKeys.contains(tile.row() + "_" + tile.col())) {
                    set.addTile(tile);
                }
            }
        }

        // Resolve live map data once; live tiles replace cache-only entries via addTile.
        Level level = Minecraft.getInstance().level;
        List<LiveTile> liveTiles = new ArrayList<>(pending.size());
        for (PendingTile tile : pending) {
            MapId mapId = tile.scanned().stack().get(DataComponents.MAP_ID);
            MapItemSavedData mapState = (level != null && mapId != null) ? level.getMapData(mapId) : null;
            liveTiles.add(new LiveTile(tile.parsed(), tile.scanned(), mapState));
        }
        for (LiveTile tile : liveTiles) {
            set.addTile(new MapTile(tile.parsed().row(), tile.parsed().col(), tile.parsed().rawName(),
                tile.scanned().mapId(), tile.scanned().stack(), tile.mapState()));
        }
        set.calculateMissing();

        if (persist && cache != null) {
            boolean cacheColors = settings.cacheTileColors();
            for (LiveTile tile : liveTiles) {
                cache.putTile(projectId, tile.parsed().row(), tile.parsed().col(),
                    tile.parsed().rawName(), tile.scanned().mapId());
                if (!cacheColors) continue;
                byte[] colors = resolveTileColors(tile, true);
                if (colors == null) continue;
                // Live mapState colors keep mutating as packets land; write a snapshot.
                // Captured arrays are already private copies and transfer ownership here.
                byte[] snapshot = (tile.mapState() != null && colors == tile.mapState().colors)
                    ? colors.clone() : colors;
                cache.putColors(projectId, tile.parsed().row(), tile.parsed().col(), snapshot);
            }
        }
        return set;
    }

    // Usable = full-length and not an all-zero (never-rendered) map state.
    private static boolean usableColors(byte[] colors) {
        return colors != null && colors.length >= MapArtCache.COLOR_BYTES && !MapArtCache.isBlank(colors);
    }

    // Colors for a live tile: live mapState first, then a packet-captured array.
    // transferOwnership=true removes the captured entry (the async cache writer owns it).
    private byte[] resolveTileColors(LiveTile tile, boolean transferOwnership) {
        byte[] colors = tile.mapState() != null ? tile.mapState().colors : null;
        if (usableColors(colors)) return colors;
        Integer mapId = tile.scanned().mapId();
        byte[] captured = mapId == null ? null
            : transferOwnership ? capturedColors.remove(mapId) : capturedColors.get(mapId);
        return usableColors(captured) ? captured : null;
    }

    private MapArtSet cacheOnlySet(ProjectCache project) {
        int maxRow = 0;
        int maxCol = 0;
        for (CachedTileInfo info : project.getTiles().values()) {
            maxRow = Math.max(maxRow, info.row);
            maxCol = Math.max(maxCol, info.col);
        }
        MapArtSet set = new MapArtSet(project.getProjectId(), maxRow + 1, maxCol + 1);
        set.markCacheOrigin();
        for (CachedTileInfo info : project.getTiles().values()) {
            set.addTile(new MapTile(info.row, info.col, info.name, info.mapId, null, null));
        }
        if (project.getExpectedRows() > 0 || project.getExpectedCols() > 0) {
            set.setExpectedDims(project.getExpectedRows(), project.getExpectedCols());
        }
        set.calculateMissing();
        return set;
    }

    private void refreshActiveSet() {
        if (currentProjectIndex >= projectSets.size()) currentProjectIndex = 0;
        if (currentProjectIndex < 0) currentProjectIndex = 0;
        activeSet = projectSets.isEmpty() ? null : projectSets.get(currentProjectIndex);
    }

    public void nextProject() {
        if (projectSets.isEmpty()) return;
        currentProjectIndex = (currentProjectIndex + 1) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        dataVersion++;
    }

    public void prevProject() {
        if (projectSets.isEmpty()) return;
        currentProjectIndex = (currentProjectIndex - 1 + projectSets.size()) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        dataVersion++;
    }

    public int getProjectCount() {
        return projectSets.size();
    }

    public int getCurrentProjectIndex() {
        return currentProjectIndex;
    }

    public List<MapArtSet> getProjectSets() {
        return Collections.unmodifiableList(projectSets);
    }

    public MapArtSet getActiveSet() {
        return activeSet;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    // Canvas pixel dimensions of the active project (NOT a texture size; the renderer chunks it).
    public int getTextureWidth() {
        return activeSet == null ? 0 : activeSet.getCols() * MapArtSet.TILE_PIXELS;
    }

    public int getTextureHeight() {
        return activeSet == null ? 0 : activeSet.getRows() * MapArtSet.TILE_PIXELS;
    }

    public boolean hasValidMapArt() {
        return activeSet != null && activeSet.getPresentCount() > 0;
    }

    // Writes <cacheRoot>/missing/<project>.json; returns null when there is nothing to export.
    public Path exportMissingJson() {
        MapArtSet set = activeSet;
        if (set == null || cache == null) return null;
        try {
            return MissingTileExporter.exportJson(cache.getRootDir().resolve("missing"),
                set.getProjectId(), set.getRows(), set.getCols(), set.getMissingTiles(), set.getPresentCount());
        } catch (IOException error) {
            return null;
        }
    }

    // Deletes the active project's persistent cache and drops its in-memory set immediately;
    // tiles still physically in the inventory return on the next scan as fresh live tiles.
    public boolean deleteActiveProjectCache() {
        if (cache == null || activeSet == null) return false;
        cache.deleteProject(activeSet.getProjectId());
        projectSets.remove(activeSet);
        refreshActiveSet();
        scannedOnce = false; // force a rescan so disk-backed tiles disappear immediately
        dataVersion++;
        return true;
    }

    public void flushCache() {
        if (cache != null) cache.flushSync();
    }

    public int getCachedTileCount(String projectId) {
        if (cache == null || projectId == null) return 0;
        ProjectCache project = cache.getProject(projectId);
        return project == null ? 0 : project.tileCount();
    }

    public MapArtCache getCache() {
        return cache;
    }

    // Memory only; the disk cache is never touched here.
    public void clear() {
        activeSet = null;
        projectSets.clear();
        currentProjectIndex = 0;
        scannedOnce = false;
        scansSinceRefresh = 0;
        lastStructuralSignature = 0;
        dataVersion++;
    }

    // Module settings lookup via Meteor's registry (existing pattern). Null-safe: defaults apply
    // when the module is not registered yet.
    private MapArtViewerSettings lookupSettings() {
        dev.mapbundlecollector.modules.MapArtViewerModule module =
            meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(dev.mapbundlecollector.modules.MapArtViewerModule.class);
        return new MapArtViewerSettings(
            module != null && module.keepLoaded.get(),
            module != null && module.persistentCache.get(),
            module == null || module.cacheTileColors.get(),
            module != null ? module.mapWidth.get() : 0,
            module != null ? module.mapHeight.get() : 0,
            module == null || module.coordinateFormat.get() == dev.mapbundlecollector.modules.MapArtViewerModule.CoordinateFormat.X_Y,
            module != null && module.evmodBridge.get());
    }

    // Snapshot of the module settings the manager needs, so lookups stay in one place.
    private record MapArtViewerSettings(
        boolean keepLoaded,
        boolean persistentCache,
        boolean cacheTileColors,
        int mapWidth,
        int mapHeight,
        boolean xyFirst,
        boolean evmodBridge
    ) {}

    private record PendingTile(MapNameParser.ParsedTile parsed, InventoryScanner.ScannedMap scanned) {}

    private record LiveTile(MapNameParser.ParsedTile parsed, InventoryScanner.ScannedMap scanned, MapItemSavedData mapState) {}
}
