# Mapart 204x124 Optimization Spec

Goal: make the map-art viewer handle a **204x124 mapart (25,296 tiles)** loaded in
**batches**, verify **missing tiles**, run on a **low-end PC**, support **JSON export**
of missing tiles, and keep a **persistent cache** that survives restarts until the user
deletes it.

## Problems in the current code (why it fails at 204x124)

1. `MapArtManager.rebuildTexture()` stitches ONE `NativeImage` of `cols*128 x rows*128`.
   At 204x124 that is 26112 x 15872 = ~414M pixels = **~1.6 GB**, which exceeds GPU max
   texture size (16384 or 32767) and OOMs. Also `setPixel` per pixel = minutes per rebuild.
2. `MapArtManager.calculateSignature()` hashes EVERY tile's full 16 KB color array on
   every scan (2x per second) => hundreds of MB hashed per tick => freeze.
3. `MapTile` clones 16 KB colors per tile; merged sets across batches would hold up to
   ~400 MB on the Java heap.
4. Missing-tile detection only knows `max seen row/col + 1`. Loading batch 1 of a
   204-wide mapart reports a tiny "complete" grid instead of the true 204x124 target.
5. No persistence: restart loses all scanned tiles.
6. No missing-tiles JSON export.

## Target architecture

### 1. Chunked renderer (`gui/MapArtChunkRenderer`, NEW)

- Never build one giant texture. Tiles are grouped into **chunks of 16x16 tiles**
  (2048x2048 px full-res). A 204x124 mapart = 13x8 = 104 chunks.
- **Two quality tiers:**
  - **mini tier**: each chunk downscaled to 128x128 px (each map tile = 8x8 px).
    All chunks of a project fit in ~7 MB VRAM. Used when `128 * zoom < 16` px.
  - **full tier**: 2048x2048 px (16.7 MB each), built **lazily only for chunks visible
    on screen**, kept in an **LRU cache** with a configurable budget
    (`max-full-res-chunks`, default 12 => ~200 MB VRAM max).
- **Viewport culling**: only chunks intersecting the visible screen rect are drawn.
- **Async build**: `NativeImage` is assembled on a background thread (max 2 concurrent
  builds, queue the rest). Upload/registration happens on the render thread via
  `Minecraft.getInstance().execute(...)`. Until a chunk texture is ready, draw a dark
  placeholder rect.
- Chunk color source resolution (background thread): live `MapItemSavedData.colors`
  for tiles currently in inventory -> else persistent cache file -> else checkerboard
  placeholder.
- Edge chunks (right/bottom) are sized to the remaining tiles (not padded to 2048).
- Invalidate single chunks when their tiles change (batch loaded => only affected
  chunks rebuild).
- Reading `mapState.colors` from the background thread is accepted (map art is static;
  worst case a stale frame that a later rebuild fixes).

### 2. Persistent cache (`mapart/cache`, NEW, PURE JAVA - no Minecraft imports)

Disk layout under `<gameDir>/mapbundlecollector/`:

```
mapbundlecollector/
  cache/<safeProjectId>/meta.json     # presence index + expected dims
  cache/<safeProjectId>/tiles/r_c.bin # gzip of the 16384 color bytes, 1 file per tile
  missing/<safeProjectId>.json        # exported missing-tiles report (latest per project)
```

- `meta.json`: `{ projectId, expectedRows, expectedCols (0=auto), tiles: [{r, c, name, mapId|null}] }`
- Tile color files are written **once** when a tile is first seen with full colors,
  rewritten only if the content hash changes (hash-dedup prevents rewrite churn).
- Loading: all `meta.json` files are read at startup into memory (presence index only,
  a few MB for 25k tiles). Colors are read **lazily per tile** during chunk builds.
- Saving: debounced (~2.5 s after last change) on a single background writer thread;
  `meta.json` written atomically (tmp file + move). `flushSync()` for shutdown/deactivate.
- Deleting: `deleteProject(id)` removes the project dir; explicit user action only.
  The cache NEVER expires on its own.
- Corrupt files: skip and rename `.corrupt`, never crash.

### 3. Missing-tile verification

- New module settings `map-width` (expected columns) and `map-height` (expected rows),
  0 = auto. For the user's project: width=204, height=124.
- `MapArtSet` gets `expectedRows/expectedCols`; canvas dims = max(expected, seen max).
  `calculateMissing()` runs over the canvas dims => correct missing list from batch 1.
- Expected dims are persisted per project in `meta.json` (first non-zero setting wins,
  later changed settings override all projects on next scan).

### 4. JSON export (`mapart/cache/MissingTileExporter`, NEW, PURE JAVA)

- `exportJson(Path outDir, String projectId, int rows, int cols, List<int[]> missing,
  int presentCount)` writes (manual JSON writing, properly escaped, no external deps):

```json
{
  "project": "HSiRN5MaQAEEEvk",
  "expectedColumns": 204,
  "expectedRows": 124,
  "expectedTotal": 25296,
  "presentTiles": 21000,
  "missingCount": 4296,
  "generatedAt": "2026-10-29T12:00:00",
  "missingTiles": [ { "row": 3, "col": 17 }, ... ]
}
```

- Button [Export JSON] in `MapArtRenderScreen` (+ key E) and a header-card status.
  Chat message confirms path + count.

### 5. Performance rules (the "shitty PC" contract)

- No per-tick full-color hashing. Structural signature only
  (projectId, dims, tile count, per-tile `row*1000+col` + mapId hash). Texture rebuilds
  happen per-chunk, incrementally, async.
- Scan inventory at most every 10 ticks (existing) and skip the whole parse when a
  cheap inventory signature (names + mapIds + counts) is unchanged.
- Heap: no 16 KB clones retained for tiles that left inventory; their colors live on
  disk + in chunk textures only. Live tiles reference `mapState` directly.
- Grid lines / coordinate labels / missing-tile overlay: draw only for the visible
  row/col range; overlay fills capped (skip red overlay when `128*zoom < 6`).

### 6. UI changes

- `MapArtRenderScreen`: render via chunk renderer; new buttons [Export JSON] and
  [Del Cache] (deletes active project's persistent cache after standard click);
  header shows `present/total (expected) • missing count • cached N`; missing tiles
  highlighted red when zoomed in (toggle key M).
- `MapArtSideWidget`: preview box renders via the chunk renderer inside its scissor
  (nearly always mini tier; automatically full tier when zoomed in far enough).
- `MapArtViewerModule`: new settings `map-width`, `map-height`, `persistent-cache`
  (default true), `cache-tile-colors` (default true), `max-full-res-chunks` (4..64,
  default 12). `onDeactivate` flushes the cache instead of wiping it.
- `MapBundleCollectorAddon.onInitialize`: `MapArtManager.get().initialize(gameDir)`.

## Environment facts

- Build: `gradlew.bat build` in `D:\Project\Bundle` (Fabric Loom, MC 26.2,
  Meteor 26.2-SNAPSHOT, Java 25 release). JDK 25 at
  `<user-home>\.gradle\jdks\temurin-25` (PATH java is 21 - too old).
- Deploy: jar goes to
  `<user-home>\AppData\Roaming\PrismLauncher\instances\26.2\minecraft\mods`.
- Package root: `dev.mapbundlecollector`.
