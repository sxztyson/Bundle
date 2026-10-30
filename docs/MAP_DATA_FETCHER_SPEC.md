# Map Data Fetcher — Design Contract

Goal: automatically obtain map colors for bundled maps the server never sends data for, by
making each unknown map loose in the player's inventory for a moment (vanilla's
`MapItem.inventoryTick` then sends its full data once per player per map), capturing the
packet, and persisting it. Target scale: 5-6 chests/shulkers full of bundles (~10k+ maps).

## Hard design rules (agreed with the user)

1. **Event-driven completion.** A map is "fetched" the moment the packet capture
   (`MapArtManager.getCapturedColors(mapId)`) observes its map id. NO fixed data timeout,
   NO retry-on-slow. Early/late arrival both simply advance the wave.
2. **Server-confirmed state is the only truth — verified at wave boundaries.** Within a
   wave, clicks are STREAMED back-to-back (Ev's Mod `ClickUtils` model): each click is
   preceded by a cheap precondition check against the current menu state (client
   prediction + server sync are the same view; stateId self-heal corrects rejected
   clicks), and every wave boundary (after the eject batch, after the reinsert batch,
   before RETURN_BUNDLE) performs HARD verification of the full expected state: bundle
   mapId multiset, loose-slot contents, carried stack. Any mismatch aborts — never a
   compensating click. A click is a request; only the boundary-confirmed state counts.
3. **Abort, never blind-re-click.** Abort immediately when the precondition observably
   breaks: container/screen closed, carried item != expected, a wave map confirmed no
   longer loose while uncaptured, player/world gone. Long-silence stall detector
   (default 30s with zero captures AND zero container syncs) is a dead-man's switch that
   STOPS and reports - it never re-clicks.
4. **No drops, ever.** Only inventory-screen slot clicks. The cursor is always resolved
   (picked-up item is placed back) before any state transition. Aborts must leave every
   bundle whole (waves are reinserted before advancing; abort mid-wave first returns
   carried + loose items into the bundle when state is confirmed, otherwise stops without
   further clicks and reports).
5. **Skip cached work.** A map whose colors are already known (packet-captured this
   session OR present as a color file in the disk cache) costs nothing.
6. **Server-plausible pacing.** Configurable click rate (`clicks-per-second`, default 40,
   enforced evenly; bursts above one click per tick are never sent). P1 keeps the bundle
   ON THE CURSOR across all waves of a bundle (park only when switching to P2 or at
   RETURN_BUNDLE): eject = 1 right-click per empty slot, reinsert = 1 left-click per loose
   map slot ("suck up" with carried bundle — the proven Ev's Mod
   `KeybindMapMoveBundle` pattern). Target: <= 2 clicks per map per wave total.

## User flow

1. Enable module; open a chest/shulker containing bundles.
2. Fetcher scans the OPEN container (server-synced menu): finds bundles, parses map names
   via `MapNameParser` (project/row/col), computes uncached map ids.
3. Per bundle with unknown maps: take bundle into inventory -> extract waves of maps
   loose into free inventory slots (batch-size, default 24) -> wait for captures ->
   reinsert into bundle -> verify bundle contents -> return bundle to its
   original chest slot. Waves loop on the same bundle until all its unknown maps are
   captured (P1 eject-prefix for shallow entries, P2 select-protocol for deep ones), then
   the next bundle.
4. Chat summary per chest: fetched / already-cached / aborted-reason. User opens the next
   chest manually.
5. On capture, colors are persisted IMMEDIATELY to the disk cache at the map's parsed
   (projectId, row, col) via `MapArtCache.putColors` (also `putTile`), so the viewer can
   render them from cache even before the bundles are ever loaded into the player
   inventory.

## Cache additions needed

- Reverse index + color-file presence, maintained by the cache engine at open/load:
  `boolean hasColorsByMapId(Integer mapId)` (a mapId is "known" if any project's tile with
  that mapId has a `tiles/r_c.bin` on disk). Implement by indexing existing color files
  (directory listing) and meta tiles at open; keep updated on putColors/putTile.

## State machine (class `MapDataFetcher`, no Minecraft-free logic mixed in)

IDLE -> SCAN -> [per bundle] TAKE_BUNDLE -> EXTRACT_WAVE -> WAIT_DATA -> REINSERT_WAVE ->
VERIFY_BUNDLE -> RETURN_BUNDLE -> [next] -> DONE. Every transition asserts its
precondition from server-synced state; any mismatch -> ABORT(reason) with chat report.

- WAIT_DATA exits when: all wave mapIds captured (success) OR abort condition OR stall.
- Track wave mapIds -> parsed locations for immediate persistence.
- Player inventory must have >= batch-size free slots (+ space to hold the bundle); if
  not, chat-report and stay idle (do not click).

## Module (`modules/MapDataFetcherModule`, "map-data-fetcher")

Settings: clicks-per-second (5..100, def 40), batch-size (1..36, def 24),
stall-timeout-s (5..300, def 30), auto-start (bool, def true: start when a container with
fetchable bundles opens). Toggle off = safe stop. All feedback via `MapArtUi`.
Registration in `MapBundleCollectorAddon`.

## Reuse (do not duplicate)

- Click machinery + delay/cursor-recovery patterns: whatever `MapChestSorter` /
  `MapBundleCollector` already use (follow the same approach and helpers).
- `MapNameParser`, `MapArtCache`, `MapArtManager.getCapturedColors`, `MapArtUi`,
  `MapArtSet.TILE_PIXELS` (n/a here), module setting builder conventions.
- Dependency direction stays: cache <- mapart <- gui <- modules; the fetcher state machine
  lives in `modules` (or `mapart` if Minecraft-free) accordingly.
