package dev.mapbundlecollector.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import dev.mapbundlecollector.mapart.MapTile;
import dev.mapbundlecollector.mapart.cache.MapArtCache;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

// Chunked texture renderer for huge map art. Tiles are grouped into 16x16-tile chunks
// (2048x2048 px full-res, plus an 8px-per-tile mini tier for zoomed-out views), built
// asynchronously on background threads and uploaded on the render thread. Never stitches
// one giant texture, so a 204x124 mapart (~1.6 GB as a single image) renders in ~16 MB chunks.
//
// The renderer holds no screen/module knowledge: every render() call auto-binds to
// MapArtManager's active set and dataVersion (project switch releases everything, a version
// bump dirties all chunks coarsely and visible chunks rebuild + re-register in place).
//
// All mutable state below is confined to the render thread; workers only touch the immutable
// BuildJob snapshot and the thread-safe `completed` queue.
public final class MapArtChunkRenderer {
    private static final MapArtChunkRenderer INSTANCE = new MapArtChunkRenderer();

    public static final int CHUNK_TILES = 16;
    private static final int MINI_TILE_PX = 8;
    private static final int FULL_TIER_MIN_TILE_PX = 16;
    private static final int PLACEHOLDER_COLOR = 0xFF14161C;
    private static final String NAMESPACE = "mapbundlecollector";

    // Resolves one tile's colors on a background thread: live mapState first, then the
    // persistent cache; null means "paint the checkerboard placeholder".
    @FunctionalInterface
    public interface ColorSource {
        byte[] colors(int row, int col);
    }

    // needFull=false builds only the mini tier: at zoomed-out views that skips 104 x 16MB
    // full-res builds for a 204x124 mapart (the mini tier alone covers the whole canvas).
    private record BuildJob(
        long key, int chunkRow, int chunkCol, int tilesW, int tilesH,
        Set<Long> presentTileKeys, ColorSource colorSource,
        String safeProject, int version, boolean needFull, int generation) {}

    private record BuildResult(
        long key, int chunkRow, int chunkCol, String safeProject, int version,
        NativeImage full, NativeImage mini, boolean failed) {}

    private static final class ChunkEntry {
        Identifier fullId;
        Identifier miniId;
        int fullW;
        int fullH;
        int miniW;
        int miniH;
        // Per-tier freshness: a mini-only refresh must not mark a stale full texture fresh.
        int fullVersion = -1;
        int miniVersion = -1;
        long lastUsedFrame;
    }

    private final Map<Long, ChunkEntry> chunks = new HashMap<>();
    // Chunks with a build submitted but not yet drained (includes in-flight ones).
    private final Set<Long> queued = new HashSet<>();
    private final Queue<BuildResult> completed = new ConcurrentLinkedQueue<>();
    private ExecutorService executor;

    private MapArtSet boundSet;
    private String boundSafeProject = "";
    // Bumped on every project switch; queued/in-flight jobs from the previous project
    // check it and abort cheaply instead of burning disk reads nobody will display.
    private int buildGeneration;
    private int boundVersion = -1;
    private long frameCounter;
    private int registeredFullCount = 0;
    private int maxFullResChunks = 12;

    public static MapArtChunkRenderer get() {
        return INSTANCE;
    }

    private MapArtChunkRenderer() {}

    public void setMaxFullResChunks(int max) {
        this.maxFullResChunks = Math.max(1, max);
    }

    public int pendingBuilds() {
        return queued.size();
    }

    // Draws the active project's map art. Map pixel (mx,my) lands at (panX + mx*zoom,
    // panY + my*zoom); only chunks intersecting the clip rect are drawn. Safe to call from
    // several widgets in one frame - each call drains uploads at most once.
    public void render(GuiGraphicsExtractor ctx, float panX, float panY, float zoom,
        int clipX1, int clipY1, int clipX2, int clipY2) {
        if (zoom <= 0f || clipX2 <= clipX1 || clipY2 <= clipY1) return;
        long frame = ++frameCounter;
        drainResults();

        MapArtManager manager = MapArtManager.get();
        MapArtSet set = manager.getActiveSet();
        if (set == null || set.getPresentCount() == 0) {
            // Active project gone (clear()/world leave): free every chunk texture.
            if (boundSet != null) {
                releaseAll();
                boundSet = null;
                boundSafeProject = "";
                boundVersion = -1;
            }
            return;
        }

        bind(set, manager.getDataVersion());

        int gridRows = set.getRows();
        int gridCols = set.getCols();
        if (gridRows <= 0 || gridCols <= 0) return;

        int chunkPx = CHUNK_TILES * MapArtSet.TILE_PIXELS;
        int chunkRows = (gridRows + CHUNK_TILES - 1) / CHUNK_TILES;
        int chunkCols = (gridCols + CHUNK_TILES - 1) / CHUNK_TILES;

        // Visible chunk window derived from the clip rect (map-pixel space / chunk size).
        float invZoom = 1f / zoom;
        int firstCol = clampChunk((int) Math.floor((clipX1 - panX) * invZoom / chunkPx), chunkCols);
        int lastCol = clampChunk((int) Math.ceil((clipX2 - panX) * invZoom / chunkPx), chunkCols);
        int firstRow = clampChunk((int) Math.floor((clipY1 - panY) * invZoom / chunkPx), chunkRows);
        int lastRow = clampChunk((int) Math.ceil((clipY2 - panY) * invZoom / chunkPx), chunkRows);

        // Full-res tier only while the visible window fits the VRAM budget; otherwise the
        // mini tier covers the whole canvas. Without this, a mid-zoom showing more chunks
        // than the budget would evict + rebuild them endlessly (churn).
        int visibleChunks = (lastRow - firstRow + 1) * (lastCol - firstCol + 1);
        boolean fullTier = MapArtSet.TILE_PIXELS * zoom >= FULL_TIER_MIN_TILE_PX
            && visibleChunks <= maxFullResChunks;

        for (int cr = firstRow; cr <= lastRow; cr++) {
            for (int cc = firstCol; cc <= lastCol; cc++) {
                drawChunk(ctx, set, cr, cc, panX, panY, zoom, fullTier, frame,
                    clipX1, clipY1, clipX2, clipY2);
            }
        }
        evictBeyondBudget();
    }

    private void drawChunk(GuiGraphicsExtractor ctx, MapArtSet set, int cr, int cc,
        float panX, float panY, float zoom, boolean fullTier, long frame,
        int clipX1, int clipY1, int clipX2, int clipY2) {
        long key = chunkKey(cr, cc);
        ChunkEntry entry = chunks.computeIfAbsent(key, k -> new ChunkEntry());

        int tilesW = Math.min(CHUNK_TILES, set.getCols() - cc * CHUNK_TILES);
        int tilesH = Math.min(CHUNK_TILES, set.getRows() - cr * CHUNK_TILES);
        if (tilesW <= 0 || tilesH <= 0) return;

        float sx = panX + (cc * CHUNK_TILES * MapArtSet.TILE_PIXELS) * zoom;
        float sy = panY + (cr * CHUNK_TILES * MapArtSet.TILE_PIXELS) * zoom;
        float sw = tilesW * MapArtSet.TILE_PIXELS * zoom;
        float sh = tilesH * MapArtSet.TILE_PIXELS * zoom;
        if (sx >= clipX2 || sy >= clipY2 || sx + sw <= clipX1 || sy + sh <= clipY1) return;

        boolean fullStale = fullTier && (entry.fullId == null || entry.fullVersion != boundVersion);
        boolean miniStale = entry.miniId == null || entry.miniVersion != boundVersion;
        if (fullStale || (miniStale && !fullTier)) {
            queueBuild(set, cr, cc, key, tilesW, tilesH, fullTier);
        }

        Identifier id;
        int srcW;
        int srcH;
        if (fullTier && entry.fullId != null) {
            id = entry.fullId;
            srcW = entry.fullW;
            srcH = entry.fullH;
            entry.lastUsedFrame = frame;
        } else if (entry.miniId != null) {
            // Full tier still building (or evicted): fall back to the mini texture.
            id = entry.miniId;
            srcW = entry.miniW;
            srcH = entry.miniH;
        } else {
            ctx.fill((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh), PLACEHOLDER_COLOR);
            return;
        }

        ctx.blit(RenderPipelines.GUI_TEXTURED, id,
            (int) sx, (int) sy, 0f, 0f,
            (int) Math.ceil(sw), (int) Math.ceil(sh),
            srcW, srcH, srcW, srcH);
    }

    // --- Binding / invalidation -----------------------------------------------

    private void bind(MapArtSet set, int version) {
        if (set != boundSet) {
            // Same project, new set object (every scan rebuilds sets): keep the chunk
            // textures - their stable ids re-register in place, so content refreshes
            // without a placeholder flash. Only a real project switch releases everything
            // and invalidates queued builds of the old project.
            String safe = MapArtCache.sanitizeProjectId(set.getProjectId());
            if (!safe.equals(boundSafeProject)) {
                buildGeneration++;
                queued.clear();
                releaseAll();
            }
            boundSet = set;
            boundSafeProject = safe;
        }
        boundVersion = version;
        pruneOffGrid(set);
    }

    private void pruneOffGrid(MapArtSet set) {
        int chunkRows = (set.getRows() + CHUNK_TILES - 1) / CHUNK_TILES;
        int chunkCols = (set.getCols() + CHUNK_TILES - 1) / CHUNK_TILES;
        chunks.entrySet().removeIf(e -> {
            long key = e.getKey();
            int cr = (int) (key >>> 32);
            int cc = (int) key;
            if (cr < chunkRows && cc < chunkCols) return false;
            releaseEntry(e.getValue());
            return true;
        });
    }

    private void releaseAll() {
        for (ChunkEntry entry : chunks.values()) {
            releaseEntry(entry);
        }
        chunks.clear();
    }

    private void releaseEntry(ChunkEntry entry) {
        Minecraft mc = Minecraft.getInstance();
        if (entry.fullId != null) {
            mc.getTextureManager().release(entry.fullId);
            entry.fullId = null;
            registeredFullCount--;
        }
        if (entry.miniId != null) {
            mc.getTextureManager().release(entry.miniId);
            entry.miniId = null;
        }
    }

    private void evictBeyondBudget() {
        while (registeredFullCount > maxFullResChunks) {
            ChunkEntry victim = null;
            for (ChunkEntry entry : chunks.values()) {
                if (entry.fullId == null) continue;
                if (victim == null || entry.lastUsedFrame < victim.lastUsedFrame) {
                    victim = entry;
                }
            }
            if (victim == null) break;
            Minecraft.getInstance().getTextureManager().release(victim.fullId);
            victim.fullId = null;
            registeredFullCount--;
        }
    }

    // --- Async building --------------------------------------------------------

    private void queueBuild(MapArtSet set, int cr, int cc, long key, int tilesW, int tilesH, boolean needFull) {
        if (!queued.add(key)) return;
        if (executor == null) {
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "mapbundlecollector-chunk-builder");
                thread.setDaemon(true);
                return thread;
            });
        }

        // Immutable snapshot captured on the render thread; the closure only reads
        // background-safe APIs (published, effectively immutable set + disk cache).
        int row0 = cr * CHUNK_TILES;
        int col0 = cc * CHUNK_TILES;
        Set<Long> present = new HashSet<>();
        for (int r = 0; r < tilesH; r++) {
            for (int c = 0; c < tilesW; c++) {
                if (set.getTile(row0 + r, col0 + c) != null) {
                    present.add(tileKey(row0 + r, col0 + c));
                }
            }
        }
        String projectId = set.getProjectId();
        MapArtCache cache = MapArtManager.get().getCache();
        ColorSource colorSource = (row, col) -> {
            MapTile tile = set.getTile(row, col);
            byte[] colors = tile != null ? tile.getColors() : null;
            if (usableColors(colors)) return colors;
            if (cache != null) {
                byte[] disk = cache.readColors(projectId, row, col);
                if (usableColors(disk)) return disk;
            }
            // Last resort: colors captured from a raw map packet for this tile's map id
            // (covers bundled maps whose data the server never sends as map state).
            byte[] captured = MapArtManager.get().getCapturedColors(tile != null ? tile.mapId() : null);
            return usableColors(captured) ? captured : null;
        };

        BuildJob job = new BuildJob(key, cr, cc, tilesW, tilesH, present, colorSource,
            boundSafeProject, boundVersion, needFull, buildGeneration);
        executor.submit(() -> {
            // A project switch while this job sat queued: drop it instead of doing the
            // (expensive, disk-reading) work for a project nobody is viewing.
            if (job.generation() != buildGeneration) {
                completed.offer(new BuildResult(job.key(), job.chunkRow(), job.chunkCol(),
                    job.safeProject(), job.version(), null, null, true));
                return;
            }
            try {
                completed.offer(buildChunk(job));
            } catch (Throwable error) {
                completed.offer(new BuildResult(job.key(), job.chunkRow(), job.chunkCol(),
                    job.safeProject(), job.version(), null, null, true));
            }
        });
    }

    private BuildResult buildChunk(BuildJob job) {
        int row0 = job.chunkRow() * CHUNK_TILES;
        int col0 = job.chunkCol() * CHUNK_TILES;
        NativeImage full = null;

        if (job.needFull()) {
            int fullW = job.tilesW() * MapArtSet.TILE_PIXELS;
            int fullH = job.tilesH() * MapArtSet.TILE_PIXELS;
            full = new NativeImage(fullW, fullH, false);
            for (int r = 0; r < job.tilesH(); r++) {
                for (int c = 0; c < job.tilesW(); c++) {
                    int originX = c * MapArtSet.TILE_PIXELS;
                    int originY = r * MapArtSet.TILE_PIXELS;
                    byte[] colors = tileColors(job, row0 + r, col0 + c);
                    if (colors != null) {
                        paintMapCell(full, originX, originY, colors);
                    } else {
                        paintCheckerCell(full, originX, originY);
                    }
                }
            }
        }

        NativeImage mini = new NativeImage(job.tilesW() * MINI_TILE_PX, job.tilesH() * MINI_TILE_PX, false);
        if (full != null) {
            // Full tier available: downsample from it (single color-source pass).
            int stride = MapArtSet.TILE_PIXELS / MINI_TILE_PX;
            for (int y = 0; y < mini.getHeight(); y++) {
                for (int x = 0; x < mini.getWidth(); x++) {
                    mini.setPixel(x, y, full.getPixel(x * stride, y * stride));
                }
            }
        } else {
            paintMiniChunk(mini, job);
        }
        return new BuildResult(job.key(), job.chunkRow(), job.chunkCol(),
            job.safeProject(), job.version(), full, mini, false);
    }

    private static byte[] tileColors(BuildJob job, int row, int col) {
        if (!job.presentTileKeys().contains(tileKey(row, col))) return null;
        byte[] colors = job.colorSource().colors(row, col);
        return usableColors(colors) ? colors : null;
    }

    // Full-length and not an all-zero (never-rendered) state; blanks must fall through to
    // the checkerboard so a poisoned entry never displays as permanent black.
    private static boolean usableColors(byte[] colors) {
        return colors != null && colors.length >= MapArtCache.COLOR_BYTES && !MapArtCache.isBlank(colors);
    }

    // Mini tier without the full image: sample one color per 16x16 block straight from
    // the color source (8x8 px per tile; absent tiles get a dithered checker).
    private static void paintMiniChunk(NativeImage mini, BuildJob job) {
        int row0 = job.chunkRow() * CHUNK_TILES;
        int col0 = job.chunkCol() * CHUNK_TILES;
        int stride = MapArtSet.TILE_PIXELS / MINI_TILE_PX;
        for (int y = 0; y < mini.getHeight(); y++) {
            for (int x = 0; x < mini.getWidth(); x++) {
                int argb;
                byte[] colors = tileColors(job, row0 + y / MINI_TILE_PX, col0 + x / MINI_TILE_PX);
                if (colors != null) {
                    int px = (x % MINI_TILE_PX) * stride;
                    int py = (y % MINI_TILE_PX) * stride;
                    argb = MapColor.getColorFromPackedId(colors[px + py * MapArtSet.TILE_PIXELS] & 0xFF);
                } else {
                    argb = ((x + y) % 2 == 0) ? 0xFF1E2128 : 0xFF272B34;
                }
                mini.setPixel(x, y, argb);
            }
        }
    }

    private static void paintMapCell(NativeImage image, int originX, int originY, byte[] colors) {
        for (int py = 0; py < MapArtSet.TILE_PIXELS; py++) {
            for (int px = 0; px < MapArtSet.TILE_PIXELS; px++) {
                image.setPixel(originX + px, originY + py,
                    MapColor.getColorFromPackedId(colors[px + py * MapArtSet.TILE_PIXELS] & 0xFF));
            }
        }
    }

    // The classic placeholder for missing/unknown tiles: 16px checkerboard + darker border.
    private static void paintCheckerCell(NativeImage image, int originX, int originY) {
        int size = MapArtSet.TILE_PIXELS;
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                int color = ((bx + by) % 2 == 0) ? 0xFF1E2128 : 0xFF272B34;
                image.fillRect(originX + bx * 16, originY + by * 16, 16, 16, color);
            }
        }
        image.fillRect(originX, originY, size, 1, 0xFF3E434D);
        image.fillRect(originX, originY + size - 1, size, 1, 0xFF3E434D);
        image.fillRect(originX, originY, 1, size, 0xFF3E434D);
        image.fillRect(originX + size - 1, originY, 1, size, 0xFF3E434D);
    }

    // --- Upload (render thread only) -------------------------------------------

    private void drainResults() {
        BuildResult result;
        while ((result = completed.poll()) != null) {
            applyResult(result);
        }
    }

    private void applyResult(BuildResult result) {
        queued.remove(result.key());
        if (result.failed()) return; // build failed; visible chunks re-queue next frame
        if (!result.safeProject().equals(boundSafeProject)) {
            if (result.full() != null) result.full().close();
            if (result.mini() != null) result.mini().close();
            return;
        }

        ChunkEntry entry = chunks.computeIfAbsent(result.key(), k -> new ChunkEntry());
        Minecraft mc = Minecraft.getInstance();

        // DynamicTexture uploads to the GPU in its constructor -> render thread only.
        // register() under a stable id auto-closes the previous texture, so re-registering
        // is the whole refresh path.
        if (result.mini() != null) {
            Identifier miniId = chunkId(result.safeProject(), result.chunkRow(), result.chunkCol(), true);
            DynamicTexture miniTexture = new DynamicTexture(
                () -> "mbc_chunk_mini_" + result.safeProject() + "_" + result.chunkRow() + "_" + result.chunkCol(),
                result.mini());
            mc.getTextureManager().register(miniId, miniTexture);
            entry.miniId = miniId;
            entry.miniW = result.mini().getWidth();
            entry.miniH = result.mini().getHeight();
            entry.miniVersion = result.version();
        }
        if (result.full() != null) {
            Identifier fullId = chunkId(result.safeProject(), result.chunkRow(), result.chunkCol(), false);
            DynamicTexture fullTexture = new DynamicTexture(
                () -> "mbc_chunk_" + result.safeProject() + "_" + result.chunkRow() + "_" + result.chunkCol(),
                result.full());
            mc.getTextureManager().register(fullId, fullTexture);
            if (entry.fullId == null) registeredFullCount++;
            entry.fullId = fullId;
            entry.fullW = result.full().getWidth();
            entry.fullH = result.full().getHeight();
            entry.fullVersion = result.version();
        }
    }

    // Releases every texture and stops the build pool. Call on shutdown only.
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        BuildResult result;
        while ((result = completed.poll()) != null) {
            if (result.full() != null) result.full().close();
            if (result.mini() != null) result.mini().close();
        }
        queued.clear();
        releaseAll();
        boundSet = null;
        boundSafeProject = "";
        boundVersion = -1;
    }

    private static Identifier chunkId(String safeProject, int chunkRow, int chunkCol, boolean mini) {
        String path = "chunk/" + safeProject + "_" + chunkRow + "_" + chunkCol + (mini ? "_mini" : "");
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static int clampChunk(int value, int maxExclusive) {
        return Math.max(0, Math.min(value, maxExclusive - 1));
    }

    private static long chunkKey(int chunkRow, int chunkCol) {
        return ((long) chunkRow << 32) | (chunkCol & 0xFFFFFFFFL);
    }

    private static long tileKey(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }
}
