package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.mapart.EvModBridge;
import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapNameParser;
import dev.mapbundlecollector.mapart.cache.MapArtCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

// State machine that fetches map colors the server never sends for bundled maps: waves of
// maps are made loose in the player inventory (vanilla MapItem.inventoryTick then pushes the
// full 128x128 data once per map), the packet capture is awaited, and the maps are reinserted.
// Two extraction protocols per FETCHER_INTEGRATION_NOTES §7: P1 (carried-bundle eject) while
// unknown maps sit inside the ejectable prefix, then P2 (select/extract/place, the repo-proven
// per-index path) for unknown maps deeper in the bundle; waves loop until the bundle has no
// unknown maps left.
//
// P1 keeps the bundle ON THE CURSOR for the whole bundle (parked only to enter P2 or at
// RETURN_BUNDLE), so each map costs exactly 2 clicks per wave: eject = PICKUP/1 on a
// confirmed-empty slot; reinsert = PICKUP/0 on the loose map's slot ("suck up" — the notes'
// §2.3 mirror method: carried bundle + PRIMARY + slot-with-items = tryTransfer into the
// bundle; the proven Ev's Mod KeybindMapMoveBundle stow pattern). Within a wave, P1 clicks
// are STREAMED (spec rule 2): each click is preceded by a cheap precondition read (cursor is
// the bundle, target slot holds the expected state) and HARD verification (bundle mapId
// multiset, loose slots, carried stack) happens at wave boundaries only — after the eject
// batch, after the reinsert batch, and before RETURN_BUNDLE. Any mismatch aborts (never a
// compensating guess-click); aborts resolve the cursor when the state is confirmed, otherwise
// stop clicking entirely and report. P2 keeps per-step verification (rare path, correctness
// over speed). Lives in modules (imports client Minecraft classes); feedback goes through
// the injected sink, so mapart/gui stay fetcher-free.
public final class MapDataFetcher {
    // Verified pacing from the repo's proven modules: 200 ms open delay for the initial full
    // container sync, 40 ms boundary polling, per-step deadline max(1500, 4x interval).
    private static final long OPEN_DELAY_MS = 200;
    private static final long VERIFY_POLL_MS = 40;

    // Snapshot of the module settings the engine needs per tick. clickIntervalMs is the even
    // spacing 1000/clicks-per-second; the tick-driven engine structurally sends at most ONE
    // click per game tick, so configured rates above 20/s are effectively capped there.
    public record Params(int clickIntervalMs, int batchSize, long stallTimeoutMs, boolean xyFirst,
                         boolean evmodBridge, boolean debugLog) {}

    private enum State {
        IDLE,
        WAITING,
        OPEN_DELAY,
        SCAN,
        WAVE_PLAN,
        TAKE_BUNDLE,
        EXTRACT_WAVE,
        EXTRACT_VERIFY,
        WAIT_DATA,
        PARK_BUNDLE,
        P2_EXTRACT,
        REINSERT_WAVE,
        REINSERT_VERIFY,
        VERIFY_BUNDLE,
        RESOLVE_CURSOR
    }

    // Why the bundle is being parked back on its chest slot.
    private enum ParkPurpose {
        P2,     // selection packets need the bundle IN a slot
        DONE,   // bundle finished, returning it home
        RESUME, // P2 reinsert done; park, then continue waves on the same bundle
        ABORT   // unwinding a wave; VERIFY_BUNDLE reports the actual contents
    }

    private record BundlePlan(int chestSlot, Map<String, Integer> contents) {}

    // One planned ejection, predicted from the server-synced bundle contents. The P1 ejection
    // order is fixed (entry 0 first) so the prefix IS the plan; the slot the map lands in is
    // NOT part of it because that is read back from synced state at the boundary.
    private record Prediction(String key, String name, Integer mapId, int count, boolean fetchable,
                              MapNameParser.ParsedTile parsed) {}

    private static final class LooseMap {
        int slot;
        String key;
        String name;
        Integer mapId;
        int count;
        boolean fetchable;
        MapNameParser.ParsedTile parsed;
        boolean captured;
        // True once the map is back in the bundle OR its slot failed verification and it was
        // deliberately skipped (reported); either way it is never clicked again.
        boolean settled;

        LooseMap(int slot, Prediction prediction) {
            this.slot = slot;
            retarget(prediction);
        }

        // Ejections land in plan ORDER, but which physical entry popped is only known for
        // certain from the landed stack itself; matching re-binds the prediction the landed
        // stack actually corresponds to (same content key).
        void retarget(Prediction prediction) {
            this.key = prediction.key();
            this.name = prediction.name();
            this.mapId = prediction.mapId();
            this.count = prediction.count();
            this.fetchable = prediction.fetchable();
            this.parsed = prediction.parsed();
        }

        // Another mod/module (or the player) may MOVE a loose map to a different inventory
        // slot mid-wave; the wave follows the map instead of unwinding.
        void retargetSlot(int newSlot) {
            this.slot = newSlot;
        }
    }

    private final MapArtManager manager;
    private final Consumer<String> feedback;

    private State state = State.IDLE;
    private AbstractContainerScreen<?> activeScreen;
    private AbstractContainerMenu menu;

    private final List<BundlePlan> bundles = new ArrayList<>();
    private int bundleIndex;
    private int chestBundleCount;
    private int fetchedCount;
    private int cachedSkippedCount;
    private int abortedCount;

    private Map<String, Integer> preContents;
    // Expected bundle multiset/entry-count while a wave is in flight; committed only at wave
    // boundaries (P1) or on server-confirmed steps (P2).
    private Map<String, Integer> remaining;
    private int remainingEntries;
    private int bundleSlot = -1;
    // P1 lifecycle: the bundle stays on the cursor across ALL waves of a bundle.
    private boolean bundleCarried;

    private final List<Prediction> wavePlan = new ArrayList<>();
    private final List<LooseMap> ejected = new ArrayList<>();
    // Slots this wave has already targeted with an ejection click. Two in-flight ejections
    // must never share a slot: the later server-processed click would find it non-empty and
    // PICKUP/1 with a carried bundle on a non-empty slot SWAPS stacks.
    private final Set<Integer> usedWaveSlots = new HashSet<>();
    private int ejectIndex;
    private int reinsertIndex;
    private int insertedBefore;
    private int pendingSlot = -1;
    private int phase;
    private ParkPurpose parkPurpose;

    // P2 per-extraction state (select/extract/place). The entry index and prediction are
    // re-read from synced state for EVERY extraction because removing an entry shifts the
    // indices of everything behind it.
    private boolean p2Wave;
    private int p2Target;
    private int p2Placed;
    private int p2Index = -1;
    private Prediction p2Prediction;
    private Map<String, Integer> p2PreExtract;
    private int p2PreEntries;

    private boolean aborting;
    private String abortReason = "";
    // A stall skip unwinds the wave but CONTINUES the chest (the stalled ids are simply
    // given up for this session); every other abort ends the chest.
    private boolean continueAfterRewind;

    // Map ids the server will not send data for this session: the server only sends a
    // map's full data ONCE per player (first time it is loose in inventory); if the player
    // already held these maps earlier in the session, no packet will EVER come. They are
    // skipped for the whole client session and reported per chest.
    private final Set<Integer> unfetchableIds = new HashSet<>();
    private int unfetchableCount;

    // Wave maps that left loose state without being reinserted into OUR bundle - typically
    // vacuumed into another bundle by a sibling module mid-wave. The wave's boundary checks
    // subtract them from the expected bundle contents instead of failing; ones vacuumed
    // before their data arrived are retried automatically on the next chest open.
    private final List<LooseMap> stolen = new ArrayList<>();
    private int vacuumedCount;

    private long nextActionAt;
    private long verifyDeadline;
    private long lastActivityAt;
    private long lastSyncAt;

    // No-progress wave guard (see planWave) + the params of the current tick so abort paths
    // can log without threading Params through every call.
    private int stalledWaves;
    private int lastProgressTotal = -1;
    private Params lastParams;
    // Wall-clock of the previous tick, to detect background/pause gaps (see tick()).
    private long lastTickAt;

    // Where TAKE_BUNDLE routes after a successful pickup (extraction start, reinsert after
    // a wait, or a recovery re-pickup after an external module took the carried bundle).
    private State afterPickup = State.EXTRACT_WAVE;
    // Recovery attempts per wave after external bundle theft; a module that keeps stealing
    // the bundle aborts with a clear message instead of thrashing.
    private int bundleAdoptions;

    public MapDataFetcher(MapArtManager manager, Consumer<String> feedback) {
        this.manager = manager;
        this.feedback = feedback;
    }

    // Client-thread only. Params must be re-read each tick (settings can change mid-run).
    public void tick(Params params) {
        this.lastParams = params;
        long tickNow = System.currentTimeMillis();
        if (lastTickAt > 0 && tickNow - lastTickAt > 600) {
            // The game did not tick for a while (Minecraft in the background with FPS/tick
            // throttling — e.g. Dynamic FPS — a pause, or a lag spike). Shift every deadline
            // and activity clock forward by the gap so resuming CONTINUES the run instead of
            // failing in-flight verifications and aborting.
            long shift = (tickNow - lastTickAt) - 200;
            nextActionAt += shift;
            verifyDeadline += shift;
            lastActivityAt += shift;
            lastSyncAt += shift;
            debug("tick gap " + (tickNow - lastTickAt) + "ms (background/pause) — clocks rebased, continuing");
        }
        lastTickAt = tickNow;
        if (state == State.IDLE || state == State.WAITING) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            abortNoClicks("player or connection gone");
            return;
        }
        if (!contextOpen()) {
            abortNoClicks("container closed or screen changed");
            return;
        }

        long now = System.currentTimeMillis();
        if (state == State.WAIT_DATA) {
            waitData(now, params);
            return;
        }
        if (now < nextActionAt) return;

        switch (state) {
            case OPEN_DELAY -> {
                state = State.SCAN;
                nextActionAt = now;
            }
            case SCAN -> scanChest(params, now);
            case WAVE_PLAN -> planWave(params, now);
            case TAKE_BUNDLE -> takeBundle(params, now);
            case EXTRACT_WAVE -> extractWave(params, now);
            case EXTRACT_VERIFY -> extractVerify(params, now);
            case PARK_BUNDLE -> parkBundle(params, now);
            case P2_EXTRACT -> p2Extract(params, now);
            case REINSERT_WAVE -> reinsertWave(params, now);
            case REINSERT_VERIFY -> reinsertVerify(params, now);
            case VERIFY_BUNDLE -> verifyBundle(now);
            case RESOLVE_CURSOR -> resolveCursor(params, now);
            default -> { }
        }
    }

    // A supported container screen opened (module forwards OpenScreenEvent / onActivate).
    public void begin(AbstractContainerScreen<?> screen) {
        resetChest();
        activeScreen = screen;
        menu = screen.getMenu();
        state = State.OPEN_DELAY;
        nextActionAt = System.currentTimeMillis() + OPEN_DELAY_MS;
    }

    // The screen went away (closed or replaced by anything unsupported). No clicks are
    // possible after close, so the server's own dropOrPlaceInInventory is the only cursor
    // resolution left; we only report what was in flight.
    public void onClosed() {
        boolean midRun = state != State.IDLE && state != State.WAITING && state != State.OPEN_DELAY;
        if (midRun) {
            int loose = 0;
            int capturedLoose = 0;
            for (LooseMap map : ejected) {
                loose++;
                if (map.captured) capturedLoose++;
            }
            feedback.accept("§cContainer closed mid-run: " + describeBundleLocation()
                + ", " + loose + " loose maps (" + capturedLoose + " captured), fetched "
                + fetchedCount + " before the close. Check the chest and your inventory.");
        }
        resetChest();
        state = State.WAITING;
    }

    // Module toggled off mid-run: synchronous safe stop. At most ONE verified placement (a
    // slot confirmed empty in the server-synced state) is SENT to resolve the cursor — its
    // effect cannot be verified anymore because no ticks follow; anything that cannot be
    // placed confidently stays put and is reported.
    public void stopNow() {
        if (state == State.IDLE || state == State.WAITING) {
            resetChest();
            state = State.IDLE;
            return;
        }
        String report;
        if (contextOpen()) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                report = "cursor was empty";
            } else {
                int slot = verifiedPlacementSlot(carried);
                if (slot >= 0) {
                    click(slot, 0);
                    report = "SENT placement of carried " + describeStack(carried)
                        + " into slot " + slot + " (unverified — the module is now off)";
                } else {
                    report = "could not place carried " + describeStack(carried)
                        + " (no confirmed empty slot) — it is still on your cursor";
                }
            }
        } else {
            report = "container already closed";
        }
        int loose = 0;
        for (LooseMap map : ejected) {
            if (!map.settled) loose++;
        }
        feedback.accept("§eStopped: " + report + "; " + loose + " wave maps were still loose"
            + " (fetched " + fetchedCount + " maps before stopping). Verify the chest and inventory.");
        resetChest();
        state = State.IDLE;
    }

    public void reset() {
        resetChest();
        state = State.IDLE;
    }

    // Container-sync heartbeat for the stall detector (rule 3). Scoped to the fetch's own
    // container: unrelated syncs must not suppress the dead-man's switch. Decisions
    // themselves stay tick-driven re-reads of the server-synced menu.
    public void noteContainerSync(int containerId) {
        if (menu != null && containerId == menu.containerId) {
            lastSyncAt = System.currentTimeMillis();
        }
    }

    // One-time hint when auto-start is off: counts fetchable maps in the freshly opened
    // container without touching run state or clicking anything.
    public void hintFetchable(AbstractContainerScreen<?> screen) {
        if (state != State.IDLE && state != State.WAITING) return;
        MapArtCache cache = manager.getCache();
        if (cache == null) return;
        AbstractContainerMenu opened = screen.getMenu();
        int fetchable = 0;
        for (int i = 0; i < opened.slots.size(); i++) {
            Slot slot = opened.getSlot(i);
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            for (ItemStack item : contents(stack).itemCopyStream().toList()) {
                Integer mapId = mapIdOf(item);
                if (item.is(Items.FILLED_MAP) && mapId != null && !isKnown(mapId, cache)) fetchable++;
            }
        }
        if (fetchable > 0) {
            feedback.accept("§e" + fetchable + " fetchable maps here — auto-start is off; toggle the module to begin.");
        }
    }

    // ------------------------------------------------------------------ chest scan

    // Waves are sized by the ACTUAL free-slot count; the scan gate only requires this
    // minimum so a partly-filled inventory does not disable the module outright.
    private static final int MIN_FREE_SLOTS = 8;

    private void scanChest(Params params, long now) {
        MapArtCache cache = manager.getCache();
        if (cache == null) {
            feedback.accept("§eMap cache unavailable — not fetching this chest.");
            toWaiting();
            return;
        }

        bundles.clear();
        chestBundleCount = 0;
        unfetchableCount = 0;
        int toFetch = 0;
        int alreadyCached = 0;

        // The server sends a map's full data only ONCE per player per session. Maps the
        // player already held earlier arrive as client map data (or via Ev's Mod's cache,
        // injected here) — harvest those into OUR disk cache with zero clicks before
        // planning anything.
        if (params.evmodBridge()) EvModBridge.injectCachedMapStates();
        int harvested = harvestClientKnownMaps(params, cache);
        if (harvested > 0) {
            feedback.accept("§aHarvested " + harvested + " map(s) from client/Ev's-Mod data — no clicks needed.");
        }

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory) continue; // chest side only
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;

            BundleContents bundleContents = contents(stack);
            int maps = 0;
            for (ItemStack item : bundleContents.itemCopyStream().toList()) {
                if (!item.is(Items.FILLED_MAP)) continue;
                maps++;
                Integer mapId = mapIdOf(item);
                if (mapId == null) continue;
                if (isKnown(mapId, cache)) {
                    alreadyCached++;
                } else {
                    toFetch++;
                }
            }
            if (maps == 0) continue;
            chestBundleCount++;
            if (toFetchPerBundle(stack, cache) > 0) {
                bundles.add(new BundlePlan(i, contentMultiset(stack)));
            }
        }

        debug(params, "scan: bundles=" + chestBundleCount + " toFetch=" + toFetch
            + " alreadyCached=" + alreadyCached + " harvested=" + harvested
            + " unfetchableSession=" + unfetchableIds.size());

        if (chestBundleCount == 0) {
            feedback.accept("§eNo map bundles in this container.");
            toWaiting();
            return;
        }

        if (toFetch == 0) {
            feedback.accept("Chest: " + chestBundleCount + " bundles, 0 maps to fetch, "
                + alreadyCached + " already cached.");
            toWaiting();
            return;
        }

        // Waves are capped by the ACTUAL free-slot count anyway; the gate only guards a
        // sane minimum so a partly-filled inventory does not disable the module.
        int free = freeInventorySlots().size();
        int needed = Math.min(params.batchSize(), MIN_FREE_SLOTS);
        if (free < needed) {
            feedback.accept("Chest: " + chestBundleCount + " bundles, " + toFetch + " maps to fetch, "
                + alreadyCached + " already cached — need at least " + needed
                + " free inventory slots, found " + free + ". Staying idle.");
            toWaiting();
            return;
        }

        feedback.accept("Chest: " + chestBundleCount + " bundles, " + toFetch + " maps to fetch, "
            + alreadyCached + " already cached.");
        bundleIndex = 0;
        fetchedCount = 0;
        cachedSkippedCount = alreadyCached;
        abortedCount = 0;
        startNextBundle(now);
    }

    // Persists colors the client ALREADY holds (live map data for maps seen this session,
    // including EvMod-injected states) for every chest-bundle map that has a parseable
    // name and is not yet on disk. Zero clicks — this is the only way to obtain maps the
    // server considers "already sent".
    private int harvestClientKnownMaps(Params params, MapArtCache cache) {
        if (menu == null) return 0;
        int harvested = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory) continue;
            ItemStack bundleStack = slot.getItem();
            if (!(bundleStack.getItem() instanceof BundleItem)) continue;
            for (ItemStack item : contents(bundleStack).itemCopyStream().toList()) {
                if (!item.is(Items.FILLED_MAP)) continue;
                Integer mapId = mapIdOf(item);
                if (mapId == null || cache.hasColorsByMapId(mapId)) continue;
                byte[] colors = clientMapDataColors(mapId);
                if (colors == null) continue;
                String name = ChatFormatting.stripFormatting(item.getHoverName().getString()).trim();
                MapNameParser.ParsedTile parsed = MapNameParser.parse(name, params.xyFirst());
                if (parsed == null) continue;
                cache.putTile(parsed.projectId(), parsed.row(), parsed.col(), name, mapId);
                cache.putColors(parsed.projectId(), parsed.row(), parsed.col(), colors.clone());
                harvested++;
            }
        }
        return harvested;
    }

    // Live client map data for a map id: non-null only when full-length and not blank
    // (blank = the client holds a placeholder state, not real pixels).
    private byte[] clientMapDataColors(Integer mapId) {
        if (mapId == null) return null;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        MapItemSavedData state = level.getMapData(new MapId(mapId));
        if (state == null || state.colors == null || state.colors.length < MapArtCache.COLOR_BYTES) return null;
        return MapArtCache.isBlank(state.colors) ? null : state.colors;
    }

    private int toFetchPerBundle(ItemStack bundleStack, MapArtCache cache) {
        int count = 0;
        for (ItemStack item : contents(bundleStack).itemCopyStream().toList()) {
            Integer mapId = mapIdOf(item);
            if (item.is(Items.FILLED_MAP) && mapId != null && !isKnown(mapId, cache)) count++;
        }
        return count;
    }

    // "Known" = colors already observed this session (packet capture), already on disk, or
    // already present as live client map data (maps the player held earlier this session —
    // the server will never resend those, but the client may still hold them).
    private boolean isKnown(Integer mapId, MapArtCache cache) {
        return manager.getCapturedColors(mapId) != null
            || cache.hasColorsByMapId(mapId)
            || clientMapDataColors(mapId) != null;
    }

    private void startNextBundle(long now) {
        if (bundleIndex >= bundles.size()) {
            finishChest();
            return;
        }
        BundlePlan plan = bundles.get(bundleIndex);
        bundleSlot = plan.chestSlot();
        preContents = plan.contents();
        bundleCarried = false;
        startWave(now);
    }

    // One wave on the current bundle. WAVE_PLAN re-runs after EVERY verified wave, so a
    // bundle is processed with as many waves (P1 and/or P2) as it takes until no unknown
    // maps remain; the chest summary accumulates across all of them. Note the P1 bundle
    // stays carried across waves, so bundleCarried must NOT be reset here.
    private void startWave(long now) {
        wavePlan.clear();
        ejected.clear();
        usedWaveSlots.clear();
        ejectIndex = 0;
        reinsertIndex = 0;
        pendingSlot = -1;
        phase = 0;
        p2Wave = false;
        p2Target = 0;
        p2Placed = 0;
        p2Index = -1;
        p2Prediction = null;
        p2PreExtract = null;
        stolen.clear();
        bundleAdoptions = 0;
        aborting = false;
        abortReason = "";
        continueAfterRewind = false;
        state = State.WAVE_PLAN;
        nextActionAt = now;
    }

    private void finishChest() {
        String note = abortedCount == 0 ? "" : ", " + abortedCount + " bundle(s) skipped/aborted — see above";
        String unfetchable = unfetchableCount == 0 ? ""
            : ", " + unfetchableCount + " unfetchable this session (server already sent them — hold once or retry after a server restart)";
        String vacuumed = vacuumedCount == 0 ? ""
            : ", " + vacuumedCount + " vacuumed mid-wave (auto-retried on the next chest open)";
        feedback.accept("§aChest done: fetched " + fetchedCount + ", already cached " + cachedSkippedCount
            + vacuumed + unfetchable + note + ".");
        toWaiting();
    }

    private void toWaiting() {
        resetChest();
        state = State.WAITING;
    }

    private void resetChest() {
        activeScreen = null;
        menu = null;
        bundles.clear();
        bundleIndex = 0;
        chestBundleCount = 0;
        fetchedCount = 0;
        cachedSkippedCount = 0;
        abortedCount = 0;
        unfetchableCount = 0;
        vacuumedCount = 0;
        preContents = null;
        remaining = null;
        remainingEntries = 0;
        bundleSlot = -1;
        bundleCarried = false;
        wavePlan.clear();
        ejected.clear();
        usedWaveSlots.clear();
        ejectIndex = 0;
        reinsertIndex = 0;
        insertedBefore = 0;
        pendingSlot = -1;
        phase = 0;
        parkPurpose = null;
        p2Wave = false;
        p2Target = 0;
        p2Placed = 0;
        p2Index = -1;
        p2Prediction = null;
        p2PreExtract = null;
        aborting = false;
        abortReason = "";
    }

    // ------------------------------------------------------------------ wave planning

    // Decides, from the server-synced bundle (carried in P1, chest slot in P2), how to reach
    // its unknown maps: P1 while at least one unknown sits inside the ejectable prefix (the
    // first free-slot-count entries), otherwise P2 (select any index). Advances to the next
    // bundle once no unknowns are left (parking first when the bundle is still carried).
    // No clicks happen here.
    private void planWave(Params params, long now) {
        MapArtCache cache = manager.getCache();
        ItemStack bundleStack = currentBundleStack();
        if (!(bundleStack.getItem() instanceof BundleItem) || !contentMultiset(bundleStack).equals(preContents)) {
            if (bundleCarried) {
                startResolve("carried bundle contents changed between waves");
                return;
            }
            feedback.accept("§eBundle in slot " + bundleSlot + " changed since the scan — skipped.");
            abortedCount++;
            bundleIndex++;
            startNextBundle(now);
            return;
        }

        List<ItemStack> entries = contents(bundleStack).itemCopyStream().toList();
        int unknownTotal = 0;
        int firstUnknown = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (predict(entries.get(i), cache, params).fetchable()) {
                if (firstUnknown < 0) firstUnknown = i;
                unknownTotal++;
            }
        }

        if (unknownTotal == 0) {
            if (bundleCarried) {
                parkPurpose = ParkPurpose.DONE;
                state = State.PARK_BUNDLE;
                phase = 0;
                nextActionAt = now;
                return;
            }
            bundleIndex++;
            startNextBundle(now);
            return;
        }

        List<Integer> free = freeInventorySlots();
        if (free.isEmpty()) {
            // Nothing can be ejected or placed; unwind (parks the carried bundle via the
            // abort path) and stop this chest.
            startRewind("no free inventory slots left — stopping this chest");
            return;
        }

        // Wave bookkeeping starts from the (verified) bundle state for both protocols;
        // P2 never passes through TAKE_BUNDLE, so this is the single init point.
        remaining = new HashMap<>(preContents);
        remainingEntries = contents(bundleStack).size();

        // Termination guard: three consecutive waves with zero progress (no captures, no
        // give-ups) means planning is stuck; the remaining unknowns are marked unfetchable
        // with a loud report instead of looping forever. Correct paths never trip this.
        int progress = fetchedCount + unfetchableCount;
        if (progress == lastProgressTotal) {
            if (++stalledWaves >= 3) {
                List<Integer> stuck = new ArrayList<>();
                for (ItemStack entry : entries) {
                    Integer mapId = mapIdOf(entry);
                    if (entry.is(Items.FILLED_MAP) && mapId != null
                        && predict(entry, cache, params).fetchable()) {
                        stuck.add(mapId);
                    }
                }
                unfetchableIds.addAll(stuck);
                unfetchableCount += stuck.size();
                debug(params, "no progress for 3 waves — marking " + stuck.size()
                    + " map(s) unfetchable: " + joinIds(stuck));
                feedback.accept("§cNo progress for 3 waves on bundle slot " + bundleSlot
                    + " — giving up on " + stuck.size() + " map(s) for this session (ids: "
                    + joinIds(stuck) + ") and moving on. THIS IS A BUG if it repeats — send fetcher.log.");
                stalledWaves = 0;
                lastProgressTotal = fetchedCount + unfetchableCount;
            }
        } else {
            stalledWaves = 0;
            lastProgressTotal = progress;
        }

        // Protocol choice. P1 (carried bundle, 1-click eject/reinsert) is used ONLY while the
        // bundle's front is entirely unknown, so the plan is exactly leading-unknown entries —
        // every planned entry is in `remaining` and the boundary math holds by construction.
        // As soon as known maps sit in front (firstUnknown > 0), P2 selects unknown entries
        // BY POSITION instead: the known prefix is never re-ejected (re-ejecting it both
        // wastes a wave per batch and breaks the boundary arithmetic).
        if (firstUnknown == 0) {
            // CRITICAL: removeOne() is SELECTION-AWARE — the first ejection pops the
            // SELECTED entry when one lingers (user scrolled the bundle tooltip earlier),
            // then entry 0 upward, and the selection self-clears after each removal. The
            // selected entry must therefore LEAD the plan (it pops first no matter what).
            int selected = BundleItem.getSelectedItemIndex(bundleStack);
            if (selected >= entries.size()) selected = -1;
            int target = Math.min(params.batchSize(), free.size());
            int unknownsInWave = 0;
            if (selected >= 0) {
                Prediction selectedPrediction = predict(entries.get(selected), cache, params);
                if (selectedPrediction.fetchable()) {
                    wavePlan.add(selectedPrediction);
                    unknownsInWave++;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                if (i == selected) continue;
                if (wavePlan.size() >= free.size() || unknownsInWave >= target) break;
                Prediction prediction = predict(entries.get(i), cache, params);
                if (!prediction.fetchable()) break; // pure-unknown front only
                wavePlan.add(prediction);
                unknownsInWave++;
            }
            debug(params, "plan: slot " + bundleSlot + " entries=" + entries.size() + " unknownTotal="
                + unknownTotal + " free=" + free.size() + " selected=" + selected
                + " -> P1 wave=" + wavePlan.size() + " unknownsInWave=" + unknownsInWave);
            if (wavePlan.isEmpty()) {
                // Cannot happen with firstUnknown == 0 and free slots available; guard anyway.
                p2Wave = true;
                p2Target = Math.min(params.batchSize(), free.size());
                p2Placed = 0;
                state = bundleCarried ? State.PARK_BUNDLE : State.P2_EXTRACT;
                if (bundleCarried) parkPurpose = ParkPurpose.P2;
                phase = 0;
                nextActionAt = now;
                return;
            }
            if (bundleCarried) {
                state = State.EXTRACT_WAVE;
                phase = 0;
                nextActionAt = now;
            } else {
                afterPickup = State.EXTRACT_WAVE;
                state = State.TAKE_BUNDLE;
                phase = 0;
                nextActionAt = now;
            }
            return;
        }

        // P2 (position-based): select each unknown entry by index and extract exactly it.
        // Selection packets need the bundle IN a slot, so park it first if carried.
        debug(params, "plan: slot " + bundleSlot + " entries=" + entries.size() + " unknownTotal="
            + unknownTotal + " firstUnknown=" + firstUnknown + " free=" + free.size() + " -> P2");
        p2Wave = true;
        p2Target = Math.min(params.batchSize(), free.size());
        p2Placed = 0;
        if (bundleCarried) {
            parkPurpose = ParkPurpose.P2;
            state = State.PARK_BUNDLE;
            phase = 0;
            nextActionAt = now;
        } else {
            state = State.P2_EXTRACT;
            phase = 0;
            nextActionAt = now;
        }
    }

    private Prediction predict(ItemStack entry, MapArtCache cache, Params params) {
        String name = ChatFormatting.stripFormatting(entry.getHoverName().getString()).trim();
        Integer mapId = mapIdOf(entry);
        MapNameParser.ParsedTile parsed = entry.is(Items.FILLED_MAP) ? MapNameParser.parse(name, params.xyFirst()) : null;
        // Session-unfetchable ids (stalled earlier: the server already sent their data
        // before this run) are not planned again, so bundles terminate instead of looping.
        boolean fetchable = entry.is(Items.FILLED_MAP) && mapId != null
            && !unfetchableIds.contains(mapId) && !isKnown(mapId, cache);
        return new Prediction(contentKey(entry), name, mapId, entry.getCount(), fetchable, parsed);
    }

    // ------------------------------------------------------------------ P1 take / eject

    // P1: PICKUP/0 on the bundle slot with an empty cursor picks the WHOLE bundle up (and
    // clears its bundle selection, which is why P1 ejection order is entry 0, 1, 2, ...).
    // Once per bundle for P1 — the bundle then stays on the cursor for every P1 wave.
    // Also reused as the P2 post-wait PICKUP: the parked P2 bundle is short by the wave's
    // extracted maps, hence the comparison against `remaining` (for P1's initial take
    // remaining == preContents, set by planWave, so the check is identical).
    private void takeBundle(Params params, long now) {
        if (phase == 0) {
            if (!menu.getCarried().isEmpty()) {
                startResolve("cursor was not empty before taking the bundle");
                return;
            }
            ItemStack chestStack = menu.getSlot(bundleSlot).getItem();
            if (!(chestStack.getItem() instanceof BundleItem) || !contentMultiset(chestStack).equals(remaining)) {
                if (p2Wave && !ejected.isEmpty()) {
                    startResolve("bundle in slot " + bundleSlot + " changed before the reinsert pickup");
                    return;
                }
                feedback.accept("§eBundle in slot " + bundleSlot + " changed — skipped.");
                abortedCount++;
                bundleIndex++;
                startNextBundle(now);
                return;
            }
            click(bundleSlot, 0);
            phase = 1;
            verifyDeadline = now + verifyTimeout(params);
            nextActionAt = now + params.clickIntervalMs();
            return;
        }

        ItemStack carried = menu.getCarried();
        if (carried.getItem() instanceof BundleItem
            && menu.getSlot(bundleSlot).getItem().isEmpty()
            && contents(carried).size() == expectedCarriedEntriesDuringReinsert()
            && contentMultiset(carried).equals(expectedCarriedDuringReinsert())) {
            bundleCarried = true;
            state = afterPickup;
            phase = 0;
            nextActionAt = now;
            return;
        }
        if (now < verifyDeadline) {
            nextActionAt = now + VERIFY_POLL_MS;
            return;
        }
        startResolve("taking the bundle from slot " + bundleSlot + " was not confirmed");
    }

    // True if a wave map's content key is present inside any chest-side bundle (it was
    // vacuumed into a bundle mid-wave).
    private boolean findEntryInChestBundles(LooseMap map) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            for (ItemStack entry : contents(stack).itemCopyStream().toList()) {
                if (contentKey(entry).equals(map.key)) return true;
            }
        }
        return false;
    }

    // The bundle's expected contents while being carried mid-reinsert: `remaining` plus
    // every wave map already sucked back in (settled) that was not stolen.
    private Map<String, Integer> expectedCarriedDuringReinsert() {
        Map<String, Integer> expected = new HashMap<>(remaining);
        for (LooseMap map : ejected) {
            if (map.settled && !stolen.contains(map)) {
                expected.merge(map.key, 1, Integer::sum);
            }
        }
        return expected;
    }

    private int expectedCarriedEntriesDuringReinsert() {
        int settled = 0;
        for (LooseMap map : ejected) {
            if (map.settled && !stolen.contains(map)) settled++;
        }
        return remainingEntries + settled;
    }

    // An external module took the carried bundle and parked it somewhere (usually a chest
    // slot). True if a chest-side bundle matching our expected contents was found and
    // bundleSlot was updated to its location; the caller re-picks it up and continues.
    private boolean tryAdoptParkedBundle() {
        if (++bundleAdoptions > 5) {
            feedback.accept("\u00a7cAnother module keeps taking the bundle mid-wave (5 recoveries) - disable it while fetching. Stopping this chest.");
            debug("bundle theft recovery limit hit - aborting chest");
            return false;
        }
        Map<String, Integer> expected = expectedCarriedDuringReinsert();
        int expectedSize = expectedCarriedEntriesDuringReinsert();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            if (contents(stack).size() != expectedSize) continue;
            if (!contentMultiset(stack).equals(expected)) continue;
            bundleSlot = i;
            debug("carried bundle taken externally - re-adopted from chest slot " + i);
            return true;
        }
        return false;
    }

    // Looks for a loose wave map that was moved elsewhere in the player inventory.
    private int findLooseElsewhere(LooseMap map) {
        Set<Integer> taken = new HashSet<>();
        for (LooseMap other : ejected) taken.add(other.slot);
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == map.slot || taken.contains(i)) continue;
            Slot slot = menu.getSlot(i);
            if (!(slot.container instanceof Inventory)) continue;
            if (matchesLoose(map, slot.getItem())) return i;
        }
        return -1;
    }

    // P1 eject streaming: PICKUP/1 with the carried bundle on a confirmed-empty, not-yet-used
    // inventory slot ejects entry 0 directly into that slot (the cursor never holds a map).
    // Cheap precondition before each click (cursor is the bundle, target empty); the HARD
    // verification (multiset + every landed map) happens once at the batch boundary.
    private void extractWave(Params params, long now) {
        if (ejectIndex >= wavePlan.size()) {
            enterBoundary(State.EXTRACT_VERIFY, params, wavePlan.size(), now);
            return;
        }
        ItemStack carried = menu.getCarried();
        if (!(carried.getItem() instanceof BundleItem)) {
            if (carried.isEmpty() && tryAdoptParkedBundle()) {
                afterPickup = State.EXTRACT_WAVE;
                state = State.TAKE_BUNDLE;
                phase = 0;
                nextActionAt = now;
                return;
            }
            startResolve("carried is no longer the bundle during ejection");
            return;
        }
        int freeSlot = firstEmptyInventorySlotExcludingWaveSlots();
        if (freeSlot < 0) {
            // No confirmed-empty slot left: end the batch here; the boundary verifies what
            // was actually sent.
            enterBoundary(State.EXTRACT_VERIFY, params, wavePlan.size(), now);
            return;
        }
        click(freeSlot, 1);
        ejected.add(new LooseMap(freeSlot, wavePlan.get(ejectIndex)));
        usedWaveSlots.add(freeSlot);
        ejectIndex++;
        nextActionAt = now + params.clickIntervalMs();
    }

    // P1 eject boundary: carried bundle must be exactly the pre-wave multiset minus every
    // ejected prediction, and every targeted slot must hold exactly its predicted stack.
    private void extractVerify(Params params, long now) {
        Map<String, Integer> expected = new HashMap<>(remaining);
        for (LooseMap map : ejected) expected = minusOne(expected, map.key);
        ItemStack carried = menu.getCarried();
        boolean ok = carried.getItem() instanceof BundleItem
            && contents(carried).size() == remainingEntries - ejected.size()
            && contentMultiset(carried).equals(expected);
        if (ok) {
            // Content-key matching: pair each landed stack with any unmatched plan prediction
            // of the same key. The plan order is selection-aware and deterministic, so this
            // normally re-binds the same prediction — but a single surprise can no longer
            // fail an otherwise-consistent wave.
            List<Prediction> unmatched = new ArrayList<>(wavePlan);
            for (LooseMap map : ejected) {
                ItemStack landed = menu.getSlot(map.slot).getItem();
                Prediction match = null;
                for (int i = 0; i < unmatched.size(); i++) {
                    if (matchesPrediction(unmatched.get(i), landed)) {
                        match = unmatched.remove(i);
                        break;
                    }
                }
                if (match == null) {
                    ok = false;
                    break;
                }
                map.retarget(match);
            }
        }
        if (ok) {
            remaining = expected;
            remainingEntries -= ejected.size();
            enterWaitData(now);
            return;
        }
        if (now < verifyDeadline) {
            nextActionAt = now + VERIFY_POLL_MS;
            return;
        }
        startRewind("eject batch was not confirmed (" + ejected.size() + " maps sent)");
    }

    // ------------------------------------------------------------------ waiting for data

    private void enterWaitData(long now) {
        state = State.WAIT_DATA;
        lastActivityAt = now;
        lastSyncAt = now;
    }

    // Step 4 (both protocols): event-driven completion. No data timeout, no retry — every
    // wave map either shows up in the capture or the dead-man's switch (zero captures AND
    // zero container syncs for stall-timeout) stops the run. New captures persist immediately.
    private void waitData(long now, Params params) {
        MapArtCache cache = manager.getCache();
        if (!bundleStateConfirmed()) {
            if (!p2Wave && menu.getCarried().isEmpty() && tryAdoptParkedBundle()) {
                // The carried bundle was taken and parked externally: continue the wait in
                // parked (P2-style) mode; the reinsert pickup happens right after.
                p2Wave = true;
            } else {
                startResolve("bundle changed while waiting for map data");
            }
            return;
        }

        boolean allCaptured = true;
        for (LooseMap map : ejected) {
            ItemStack inSlot = menu.getSlot(map.slot).getItem();
            if (!matchesLoose(map, inSlot)) {
                int moved = findLooseElsewhere(map);
                if (moved >= 0) {
                    // MOVED to another inventory slot: follow it, keep waiting.
                    debug("loose map moved externally - following from slot " + map.slot + " to " + moved);
                    map.retargetSlot(moved);
                    usedWaveSlots.add(moved);
                    inSlot = menu.getSlot(moved).getItem();
                } else if (isKnown(map.mapId, cache)) {
                    // Vacuumed into another bundle AFTER its data arrived: this map is
                    // fetched - persist, count it, stop tracking it (not ours to reinsert).
                    debug("loose map vacuumed after capture - counting as fetched");
                    map.captured = true;
                    map.settled = true;
                    stolen.add(map);
                    byte[] colors = manager.getCapturedColors(map.mapId);
                    if (colors != null) {
                        fetchedCount++;
                        if (map.parsed != null) {
                            cache.putTile(map.parsed.projectId(), map.parsed.row(), map.parsed.col(),
                                map.parsed.rawName(), map.mapId);
                            cache.putColors(map.parsed.projectId(), map.parsed.row(), map.parsed.col(), colors.clone());
                        }
                    } else {
                        cachedSkippedCount++;
                    }
                    continue;
                } else if (findEntryInChestBundles(map)) {
                    // Vacuumed BEFORE its data arrived: give up tracking it (settled, no
                    // reinsert). It now lives inside another bundle and is retried
                    // automatically on the next chest open - a deferral, not a skip.
                    debug("loose map vacuumed into another bundle before capture - deferred to next open");
                    map.settled = true;
                    stolen.add(map);
                    vacuumedCount++;
                    continue;
                } else {
                    startRewind("wave map " + describeStack(inSlot) + " disappeared from slot "
                        + map.slot + " while loose");
                    return;
                }
            }
            if (!map.fetchable || map.captured) continue;
            byte[] colors = manager.getCapturedColors(map.mapId);
            boolean known = colors != null || cache.hasColorsByMapId(map.mapId)
                || clientMapDataColors(map.mapId) != null;
            if (!known) {
                allCaptured = false;
                continue;
            }
            map.captured = true;
            lastActivityAt = now;
            if (colors == null && manager.getCapturedColors(map.mapId) == null
                && !cache.hasColorsByMapId(map.mapId)) {
                // Data arrived as client map state rather than a capture (EvMod injection
                // or an out-of-band send): persist it from the client state.
                colors = clientMapDataColors(map.mapId);
            }
            if (colors != null) {
                // Newly captured this run.
                fetchedCount++;
                if (map.parsed != null) {
                    cache.putTile(map.parsed.projectId(), map.parsed.row(), map.parsed.col(),
                        map.parsed.rawName(), map.mapId);
                    cache.putColors(map.parsed.projectId(), map.parsed.row(), map.parsed.col(), colors.clone());
                }
            } else {
                // Known from disk (the viewer manager consumed the capture and persisted it
                // under the same parsed location) — counts as cached-skipped, not fetched.
                cachedSkippedCount++;
            }
        }

        if (allCaptured) {
            reinsertIndex = ejected.size() - 1;
            // Both protocols reinsert with the carried-bundle suck-up (1 click per map):
            // P2's bundle is parked, so TAKE_BUNDLE picks it up first and routes back into
            // REINSERT_WAVE after the pickup verify.
            if (p2Wave) {
                afterPickup = State.REINSERT_WAVE;
                state = State.TAKE_BUNDLE;
            } else {
                state = State.REINSERT_WAVE;
            }
            phase = 0;
            nextActionAt = now;
            return;
        }

        long idleFor = now - Math.max(lastActivityAt, lastSyncAt);
        if (idleFor > params.stallTimeoutMs()) {
            List<Integer> missing = new ArrayList<>();
            for (LooseMap map : ejected) {
                if (map.fetchable && !map.captured) missing.add(map.mapId);
            }
            // The server only sends a map's full data ONCE per player per session; these
            // were already sent before this run, so no packet will ever come. Skip them
            // for the whole session, unwind the wave, and CONTINUE the chest — a stall is
            // degraded, never a run-killer.
            unfetchableIds.addAll(missing);
            unfetchableCount += missing.size();
            debug(params, "stall after " + (idleFor / 1000) + "s: giving up on " + missing.size()
                + " map id(s) for this session: " + joinIds(missing) + " — continuing chest");
            feedback.accept("§e" + missing.size() + " map(s) got no data (server already sent them "
                + "earlier this session) — skipping them and continuing: " + joinIds(missing));
            continueAfterRewind = true;
            startRewind("stalled maps skipped");
        }
    }

    // Expected bundle location/contents per protocol: P1 carries it, P2 parks it in the chest.
    private boolean bundleStateConfirmed() {
        ItemStack stack = p2Wave ? menu.getSlot(bundleSlot).getItem() : menu.getCarried();
        boolean carriedOk = p2Wave ? menu.getCarried().isEmpty() : stack.getItem() instanceof BundleItem;
        return carriedOk
            && stack.getItem() instanceof BundleItem
            && contents(stack).size() == remainingEntries
            && contentMultiset(stack).equals(remaining);
    }

    // ------------------------------------------------------------------ parking (P2 entry / RETURN_BUNDLE / abort)

    // Parks the carried bundle back on its (empty) chest slot. Strictness depends on the
    // purpose: DONE/P2 park a boundary-verified bundle (exact multiset expected); ABORT only
    // requires the carried stack to be the bundle — VERIFY_BUNDLE then reports the real
    // contents diff. This IS the RETURN_BUNDLE step.
    private void parkBundle(Params params, long now) {
        boolean strict = parkPurpose != ParkPurpose.ABORT;
        if (phase == 0) {
            ItemStack carried = menu.getCarried();
            if (!(carried.getItem() instanceof BundleItem)) {
                // An external module may have parked the bundle for us: if a chest-side
                // bundle matches the expected contents, verify it as the park result.
                if (carried.isEmpty() && tryAdoptParkedBundle()) {
                    phase = 1;
                    verifyDeadline = now + verifyTimeout(params);
                    nextActionAt = now;
                    return;
                }
                startResolve("carried stack is not the bundle before parking");
                return;
            }
            if (strict
                && (contents(carried).size() != remainingEntries || !contentMultiset(carried).equals(remaining))) {
                startResolve("carried bundle changed before returning it");
                return;
            }
            if (!menu.getSlot(bundleSlot).getItem().isEmpty()) {
                startResolve("chest slot " + bundleSlot + " is no longer empty for the bundle");
                return;
            }
            click(bundleSlot, 0);
            phase = 1;
            verifyDeadline = now + verifyTimeout(params);
            nextActionAt = now + params.clickIntervalMs();
            return;
        }

        ItemStack parked = menu.getSlot(bundleSlot).getItem();
        boolean ok = menu.getCarried().isEmpty()
            && parked.getItem() instanceof BundleItem
            && (!strict
                || (contents(parked).size() == remainingEntries && contentMultiset(parked).equals(remaining)));
        if (ok) {
            bundleCarried = false;
            switch (parkPurpose) {
                case P2 -> {
                    state = State.P2_EXTRACT;
                    phase = 0;
                    nextActionAt = now;
                }
                case DONE -> {
                    bundleIndex++;
                    startNextBundle(now);
                }
                case RESUME -> startWave(now);
                case ABORT -> {
                    state = State.VERIFY_BUNDLE;
                    nextActionAt = now;
                }
            }
            return;
        }
        if (now < verifyDeadline) {
            nextActionAt = now + VERIFY_POLL_MS;
            return;
        }
        startResolve("returning the bundle to slot " + bundleSlot + " was not confirmed");
    }

    // ------------------------------------------------------------------ P2 deep-entry protocol

    // P2 per FETCHER_INTEGRATION_NOTES §7 (repo-proven select/extract/place shape): the
    // bundle STAYS in its chest slot; per map — select the entry index (client toggle +
    // ServerboundSelectBundleItemPacket; selection is a TOGGLE, so never re-send the index
    // that is already selected, and it never survives between operations so it is set
    // explicitly each time), then PICKUP/1 with an empty cursor pops the SELECTED entry onto
    // the cursor, then PICKUP/0 places it into a confirmed-empty inventory slot. The entry
    // index and prediction are re-read from synced state per extraction (removals shift
    // indices); the extracted map is identified by reading the carried/slot stack. P2 keeps
    // full per-step verification (rare path, correctness over speed).
    // P2 (position-based) extraction, STREAMED: select + extract in one tick, verify the
    // prediction and place in the next, verify + commit in the third (TCP ordering
    // guarantees the select packet is applied server-side before the click). Hard
    // verification still runs at the reinsert and park boundaries, which catch any
    // rejected click the streaming preconditions miss.
    private void p2Extract(Params params, long now) {
        Minecraft mc = Minecraft.getInstance();
        MapArtCache cache = manager.getCache();
        ItemStack chestStack = menu.getSlot(bundleSlot).getItem();

        if (phase == 0) {
            if (!(chestStack.getItem() instanceof BundleItem)
                || contents(chestStack).size() != remainingEntries
                || !contentMultiset(chestStack).equals(remaining)) {
                startResolve("bundle in slot " + bundleSlot + " changed during extraction");
                return;
            }
            if (!menu.getCarried().isEmpty()) {
                startResolve("cursor was not empty before extracting");
                return;
            }
            if (p2Placed >= p2Target) {
                enterWaitData(now);
                return;
            }
            int selectedIndex = -1;
            ItemStack selected = null;
            List<ItemStack> entries = contents(chestStack).itemCopyStream().toList();
            for (int i = 0; i < entries.size(); i++) {
                if (predict(entries.get(i), cache, params).fetchable()) {
                    selectedIndex = i;
                    selected = entries.get(i);
                    break;
                }
            }
            int freeSlot = firstEmptyInventorySlotExcludingWaveSlots();
            if (selectedIndex < 0 || freeSlot < 0) {
                // No unknown left in the plan or nowhere to place it: end the wave with what
                // is already loose.
                enterWaitData(now);
                return;
            }
            p2Index = selectedIndex;
            p2Prediction = predict(selected, cache, params);
            // Selection is a TOGGLE: never re-send the index that is already selected (it
            // would deselect and the extraction would pull entry 0 instead). The client-side
            // toggle makes the local (predicted) state reflect the selection immediately.
            int current = BundleItem.getSelectedItemIndex(chestStack);
            if (current != selectedIndex) {
                BundleItem.toggleSelectedItem(chestStack, selectedIndex);
                if (mc.getConnection() == null) {
                    startResolve("no server connection for bundle selection");
                    return;
                }
                mc.getConnection().send(new ServerboundSelectBundleItemPacket(bundleSlot, selectedIndex));
            }
            p2PreExtract = new HashMap<>(remaining);
            p2PreEntries = remainingEntries;
            click(bundleSlot, 1); // pops the SELECTED entry onto the cursor
            phase = 1;
            verifyDeadline = now + verifyTimeout(params);
            nextActionAt = now + params.clickIntervalMs();
            return;
        }

        if (phase == 1) {
            // The extraction was locally predicted: verify the predicted effect, then place.
            chestStack = menu.getSlot(bundleSlot).getItem();
            if (matchesPrediction(menu.getCarried(), p2Prediction)
                && chestStack.getItem() instanceof BundleItem
                && contents(chestStack).size() == remainingEntries - 1
                && contentMultiset(chestStack).equals(minusOne(remaining, p2Prediction.key()))) {
                int freeSlot = firstEmptyInventorySlotExcludingWaveSlots();
                if (freeSlot < 0) {
                    // Nothing is confirmed empty: put the carried map straight back into its
                    // bundle and end the wave.
                    click(bundleSlot, 0);
                    phase = 3;
                    verifyDeadline = now + verifyTimeout(params);
                    nextActionAt = now + params.clickIntervalMs();
                    return;
                }
                click(freeSlot, 0);
                pendingSlot = freeSlot;
                phase = 2;
                verifyDeadline = now + verifyTimeout(params);
                nextActionAt = now + params.clickIntervalMs();
                return;
            }
            if (now < verifyDeadline) {
                nextActionAt = now + VERIFY_POLL_MS;
                return;
            }
            startResolve("extracting the selected map from slot " + bundleSlot
                + " was not confirmed (carried: " + describeStack(menu.getCarried()) + ")");
            return;
        }

        if (phase == 2) {
            chestStack = menu.getSlot(bundleSlot).getItem();
            if (matchesPrediction(menu.getSlot(pendingSlot).getItem(), p2Prediction)
                && menu.getCarried().isEmpty()
                && chestStack.getItem() instanceof BundleItem
                && contents(chestStack).size() == remainingEntries - 1
                && contentMultiset(chestStack).equals(minusOne(remaining, p2Prediction.key()))) {
                remaining = minusOne(remaining, p2Prediction.key());
                remainingEntries--;
                ejected.add(new LooseMap(pendingSlot, p2Prediction));
                usedWaveSlots.add(pendingSlot);
                p2Placed++;
                phase = 0;
                nextActionAt = now + params.clickIntervalMs();
                return;
            }
            if (now < verifyDeadline) {
                nextActionAt = now + VERIFY_POLL_MS;
                return;
            }
            startResolve("placing the extracted map into slot " + pendingSlot
                + " was not confirmed (carried: " + describeStack(menu.getCarried()) + ")");
            return;
        }

        // phase 3: verify the put-back of a map that could not be placed anywhere.
        chestStack = menu.getSlot(bundleSlot).getItem();
        if (menu.getCarried().isEmpty()
            && chestStack.getItem() instanceof BundleItem
            && contents(chestStack).size() == p2PreEntries
            && contentMultiset(chestStack).equals(p2PreExtract)) {
            remaining = new HashMap<>(p2PreExtract);
            remainingEntries = p2PreEntries;
            enterWaitData(now);
            return;
        }
        if (now < verifyDeadline) {
            nextActionAt = now + VERIFY_POLL_MS;
            return;
        }
        startResolve("returning the extracted map to the bundle was not confirmed");
    }

    // ------------------------------------------------------------------ reinsert + verify

    // Reinsert streaming — ONE left-click (PICKUP/0) per loose map slot with the bundle
    // carried, for BOTH protocols (P2 picks the bundle up right after WAIT_DATA): the
    // carried bundle's overrideStackedOnOther PRIMARY branch sucks the slot's stack up
    // into the bundle. Cheap precondition before each click (cursor is the bundle, slot
    // holds the expected map); HARD verification at the batch boundary.
    private void reinsertWave(Params params, long now) {
        if (reinsertIndex < 0) {
            enterBoundary(State.REINSERT_VERIFY, params, ejected.size(), now);
            return;
        }
        LooseMap map = ejected.get(reinsertIndex);
        if (map.settled) {
            // Already sucked up (or skipped): no click needed, continue immediately.
            reinsertIndex--;
            nextActionAt = now;
            return;
        }

        ItemStack carried = menu.getCarried();
        if (!(carried.getItem() instanceof BundleItem)) {
            if (carried.isEmpty() && tryAdoptParkedBundle()) {
                afterPickup = State.REINSERT_WAVE;
                state = State.TAKE_BUNDLE;
                phase = 0;
                nextActionAt = now;
                return;
            }
            startResolve("carried is no longer the bundle during reinsert");
            return;
        }
        ItemStack inSlot = menu.getSlot(map.slot).getItem();
        if (!matchesLoose(map, inSlot)) {
            if (aborting) {
                markLooseLost(map, "slot " + map.slot + " no longer holds the expected map");
                return;
            }
            int moved = findLooseElsewhere(map);
            if (moved >= 0) {
                debug("loose map moved externally during reinsert — following to slot " + moved);
                map.retargetSlot(moved);
                inSlot = menu.getSlot(moved).getItem();
                if (matchesLoose(map, inSlot)) {
                    click(moved, 0);
                    map.settled = true;
                    reinsertIndex--;
                    nextActionAt = now + params.clickIntervalMs();
                    return;
                }
            }
            startRewind("slot " + map.slot + " no longer holds the expected map during reinsert");
            return;
        }
        click(map.slot, 0); // suck the map up into the carried bundle
        map.settled = true;
        reinsertIndex--;
        nextActionAt = now + params.clickIntervalMs();
    }

    // P1 reinsert boundary: the carried bundle must be exactly the pre-wave multiset again
    // (every wave map sucked back up) and every wave slot must be empty.
    private void reinsertVerify(Params params, long now) {
        // Stolen (externally vacuumed) maps are not coming back into OUR bundle: the
        // boundary expectation is the pre-wave multiset minus exactly those entries.
        Map<String, Integer> expectedFinal = new HashMap<>(preContents);
        for (LooseMap map : stolen) expectedFinal = minusOne(expectedFinal, map.key);
        ItemStack carried = menu.getCarried();
        boolean ok = carried.getItem() instanceof BundleItem
            && contents(carried).size() == remainingEntries + ejected.size() - stolen.size()
            && contentMultiset(carried).equals(expectedFinal);
        if (ok) {
            for (LooseMap map : ejected) {
                if (!menu.getSlot(map.slot).getItem().isEmpty()) {
                    ok = false;
                    break;
                }
            }
        }
        if (ok) {
            remaining = new HashMap<>(preContents);
            remainingEntries += ejected.size();
            if (aborting) {
                parkPurpose = ParkPurpose.ABORT;
                state = State.PARK_BUNDLE;
                phase = 0;
                nextActionAt = now;
            } else if (p2Wave) {
                // P2's bundle is carried after the suck-up reinsert; park it before the next
                // wave (selection packets need the bundle in a slot), then continue waves.
                parkPurpose = ParkPurpose.RESUME;
                state = State.PARK_BUNDLE;
                phase = 0;
                nextActionAt = now;
            } else {
                // Next wave on the same bundle — it stays on the cursor (no park).
                state = State.WAVE_PLAN;
                phase = 0;
                nextActionAt = now;
            }
            return;
        }
        if (now < verifyDeadline) {
            nextActionAt = now + VERIFY_POLL_MS;
            return;
        }
        // Boundary mismatch: park whatever the bundle actually holds and let VERIFY_BUNDLE
        // report the real diff.
        aborting = true;
        if (abortReason.isEmpty()) abortReason = "reinsert batch was not confirmed";
        parkPurpose = ParkPurpose.ABORT;
        state = State.PARK_BUNDLE;
        phase = 0;
        nextActionAt = now;
    }

    // Step 6: the server-confirmed bundle contents must match the pre-wave multiset (inserts
    // are LIFO so the ORDER differs; only ids are compared) and no wave map may remain loose.
    // Reached at the end of a P2 wave (bundle parked) and from abort paths after parking.
    private void verifyBundle(long now) {
        Map<String, Integer> expectedFinal = new HashMap<>(preContents);
        for (LooseMap map : stolen) expectedFinal = minusOne(expectedFinal, map.key);
        ItemStack parked = menu.getSlot(bundleSlot).getItem();
        boolean multisetOk = parked.getItem() instanceof BundleItem
            && contentMultiset(parked).equals(expectedFinal);
        List<String> leftovers = new ArrayList<>();
        if (!menu.getCarried().isEmpty()) leftovers.add("cursor holds " + describeStack(menu.getCarried()));
        for (LooseMap map : ejected) {
            if (!menu.getSlot(map.slot).getItem().isEmpty()) {
                leftovers.add(describeStack(menu.getSlot(map.slot).getItem()) + " in slot " + map.slot);
            }
        }

        if (multisetOk && leftovers.isEmpty()) {
            if (aborting && continueAfterRewind) {
                // Stall skip: the wave unwound cleanly; the stalled ids are already marked
                // session-unfetchable, so re-planning simply proceeds without them.
                feedback.accept("§eWave unwound after a stall — continuing with the remaining maps.");
                startWave(now);
                return;
            }
            if (aborting) {
                feedback.accept("§cAborted: " + abortReason + " — bundle in slot " + bundleSlot
                    + " restored intact.");
                abortedCount++;
                finishChest();
                return;
            }
            // Loop: plan the next wave on the same bundle; planWave advances only when the
            // bundle has no unknown maps left.
            startWave(now);
            return;
        }

        String detail = (multisetOk ? "bundle contents match, " : "bundle contents MISMATCH, ")
            + (leftovers.isEmpty() ? "no loose maps" : "still loose: " + String.join(", ", leftovers));
        if (aborting) {
            debug("verify-fail (aborting): " + abortReason + " — " + detail);
            feedback.accept("§cAborted: " + abortReason + " — rewind incomplete (" + detail
                + "). Bundle left in chest slot " + bundleSlot + "; NOT clicking further.");
        } else {
            debug("verify-fail after wave: " + detail);
            feedback.accept("§cVerify failed after wave (" + detail + "). Bundle left in chest slot "
                + bundleSlot + "; NOT clicking further.");
        }
        abortedCount++;
        finishChest();
    }

    // ------------------------------------------------------------------ abort paths

    // Controlled unwind while the state is still confirmed: suck (P1, bundle carried) or
    // insert (P2, bundle parked) every loose wave map back into the bundle, then park and let
    // VERIFY_BUNDLE report. Already-settled maps are skipped by reinsertWave's fast path, so
    // re-entering REINSERT mid-batch is safe.
    private void startRewind(String reason) {
        debug("rewind: " + reason);
        aborting = true;
        abortReason = reason;
        ItemStack carried = menu.getCarried();
        if (carried.getItem() instanceof BundleItem) {
            reinsertIndex = ejected.size() - 1;
            phase = 0;
            state = State.REINSERT_WAVE;
            nextActionAt = System.currentTimeMillis();
        } else if (carried.isEmpty() && !bundleCarried && p2Wave) {
            // P2's bundle is parked in its chest slot: the suck-up reinsert needs it on the
            // cursor - TAKE_BUNDLE picks it up and routes to REINSERT_WAVE.
            afterPickup = State.REINSERT_WAVE;
            phase = 0;
            state = State.TAKE_BUNDLE;
            nextActionAt = System.currentTimeMillis();
        } else if (carried.isEmpty()) {
            reinsertIndex = ejected.size() - 1;
            phase = 0;
            state = State.REINSERT_WAVE;
            nextActionAt = System.currentTimeMillis();
        } else {
            startResolve(reason);
        }
    }

    // Cursor resolution only: verified placements into confirmed-empty slots until the cursor
    // is empty or the deadline passes; then a full report and stop. Never re-clicks a slot
    // whose expected effect was not confirmed.
    private void startResolve(String reason) {
        debug("resolve: " + reason);
        aborting = true;
        abortReason = reason;
        state = State.RESOLVE_CURSOR;
        phase = 0;
        nextActionAt = System.currentTimeMillis();
        verifyDeadline = System.currentTimeMillis() + 15_000;
    }

    private void resolveCursor(Params params, long now) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            reportResolved();
            return;
        }
        if (now >= verifyDeadline) {
            feedback.accept("§cAborted: " + abortReason + " — could not resolve the cursor; "
                + describeStack(carried) + " remains ON THE CURSOR. " + looseReport() + " Stopped clicking.");
            abortedCount++;
            finishChest();
            return;
        }
        int slot = verifiedPlacementSlot(carried);
        if (slot < 0) {
            feedback.accept("§cAborted: " + abortReason + " — no confirmed empty slot for "
                + describeStack(carried) + "; it remains ON THE CURSOR. " + looseReport() + " Stopped clicking.");
            abortedCount++;
            finishChest();
            return;
        }
        click(slot, 0);
        nextActionAt = now + params.clickIntervalMs();
    }

    private void reportResolved() {
        feedback.accept("§cAborted: " + abortReason + " — cursor resolved. " + describeBundleLocation()
            + ". " + looseReport());
        abortedCount++;
        finishChest();
    }

    private void markLooseLost(LooseMap map, String detail) {
        map.settled = true; // do not retry this slot; reported by verifyBundle/looseReport
        stolen.add(map);
        if (!aborting) {
            aborting = true;
            abortReason = detail;
        }
        reinsertIndex--;
        nextActionAt = System.currentTimeMillis();
    }

    private void abortNoClicks(String reason) {
        debug("abort-no-clicks: " + reason + " " + looseReport());
        feedback.accept("§cAborted (" + reason + "): " + describeBundleLocation() + ", "
            + looseReport() + " No further clicks; fetched " + fetchedCount + " maps.");
        abortedCount++;
        resetChest();
        state = State.WAITING;
    }

    // ------------------------------------------------------------------ shared helpers

    // Preference for a carried BUNDLE: original chest slot, then any empty chest slot, then
    // the inventory. Anything else prefers inventory slots first (repo ladder order).
    private int verifiedPlacementSlot(ItemStack carried) {
        if (carried.getItem() instanceof BundleItem) {
            if (isSlotEmpty(bundleSlot)) return bundleSlot;
            int chest = firstEmptyChestSlot();
            if (chest >= 0) return chest;
            return firstEmptyInventorySlot();
        }
        int inv = firstEmptyInventorySlot();
        if (inv >= 0) return inv;
        if (isSlotEmpty(bundleSlot)) return bundleSlot;
        return firstEmptyChestSlot();
    }

    private boolean isSlotEmpty(int index) {
        return index >= 0 && index < menu.slots.size() && menu.getSlot(index).getItem().isEmpty();
    }

    private int firstEmptyInventorySlot() {
        List<Integer> free = freeInventorySlots();
        return free.isEmpty() ? -1 : free.getFirst();
    }

    private int firstEmptyInventorySlotExcludingWaveSlots() {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory && slot.getItem().isEmpty() && !usedWaveSlots.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private int firstEmptyChestSlot() {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!(slot.container instanceof Inventory) && slot.getItem().isEmpty()) return i;
        }
        return -1;
    }

    private List<Integer> freeInventorySlots() {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory && slot.getItem().isEmpty()) free.add(i);
        }
        return free;
    }

    private ItemStack currentBundleStack() {
        return bundleCarried ? menu.getCarried() : menu.getSlot(bundleSlot).getItem();
    }

    // Enters a P1 boundary-verification state; the deadline scales with the batch size so a
    // whole streamed batch has time to confirm (last click lands at k*interval + 1 RTT).
    private void enterBoundary(State boundary, Params params, int clicks, long now) {
        state = boundary;
        phase = 0;
        verifyDeadline = now + Math.max(1500, params.clickIntervalMs() * (4L + clicks));
        nextActionAt = now;
    }

    private static Map<String, Integer> minusOne(Map<String, Integer> multiset, String key) {
        Map<String, Integer> copy = new HashMap<>(multiset);
        copy.merge(key, -1, Integer::sum);
        copy.values().removeIf(v -> v <= 0);
        return copy;
    }

    private static BundleContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
    }

    private static Integer mapIdOf(ItemStack stack) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        return mapId == null ? null : mapId.id();
    }

    // Multiset key over bundle entries: mapId when present (count-copies share the id),
    // otherwise item + count + name so renamed stacks still compare deterministically.
    private static String contentKey(ItemStack stack) {
        Integer mapId = mapIdOf(stack);
        if (mapId != null) return "m" + mapId;
        return stack.getItem() + "|" + stack.getCount() + "|"
            + ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
    }

    private static Map<String, Integer> contentMultiset(ItemStack bundleStack) {
        Map<String, Integer> multiset = new HashMap<>();
        for (ItemStack item : contents(bundleStack).itemCopyStream().toList()) {
            multiset.merge(contentKey(item), 1, Integer::sum);
        }
        return multiset;
    }

    private static boolean matchesPrediction(ItemStack landed, Prediction prediction) {
        if (landed.getCount() != prediction.count()) return false;
        if (!Objects.equals(mapIdOf(landed), prediction.mapId())) return false;
        String name = ChatFormatting.stripFormatting(landed.getHoverName().getString()).trim();
        return name.equals(prediction.name());
    }

    private static boolean matchesLoose(LooseMap map, ItemStack stack) {
        if (stack == null || stack.getCount() != map.count) return false;
        if (!Objects.equals(mapIdOf(stack), map.mapId)) return false;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
        return name.equals(map.name);
    }

    // Same identity check as matchesLoose, straight from a plan prediction.
    private static boolean matchesPrediction(Prediction prediction, ItemStack stack) {
        if (stack == null || stack.getCount() != prediction.count()) return false;
        if (!Objects.equals(mapIdOf(stack), prediction.mapId())) return false;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
        return name.equals(prediction.name());
    }

    private static String describeStack(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        Integer mapId = mapIdOf(stack);
        return ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim()
            + " x" + stack.getCount() + (mapId == null ? "" : " (map #" + mapId + ")");
    }

    private String describeBundleLocation() {
        if (menu == null || bundleSlot < 0 || bundleSlot >= menu.slots.size()) {
            return "bundle location unknown";
        }
        ItemStack at = menu.getSlot(bundleSlot).getItem();
        if (at.getItem() instanceof BundleItem) return "bundle sits in chest slot " + bundleSlot;
        if (!menu.getCarried().isEmpty()) return "bundle is on the cursor";
        return "bundle is not in chest slot " + bundleSlot + " (holds " + describeStack(at) + ")";
    }

    private String looseReport() {
        int loose = 0;
        int captured = 0;
        List<Integer> ids = new ArrayList<>();
        for (LooseMap map : ejected) {
            if (map.settled) continue;
            loose++;
            if (map.captured) captured++;
            else if (map.mapId != null && ids.size() < 8) ids.add(map.mapId);
        }
        if (loose == 0) return "No maps left loose.";
        return loose + " wave map(s) still loose (" + captured + " captured, uncaptured ids: "
            + joinIds(ids) + ").";
    }

    private static String joinIds(List<Integer> ids) {
        if (ids.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(id);
        }
        return sb.toString();
    }

    private void click(int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, mc.player);
    }

    // Appends a diagnostic line to <cacheRoot>/fetcher.log when debug-log is enabled. This is
    // the "black box" for diagnosing runs: chest plans, wave protocol choices, stall give-ups,
    // and aborts all land here. Best-effort — diagnostics must never break the fetcher.
    private void debug(Params params, String line) {
        if (params == null || !params.debugLog()) return;
        try {
            MapArtCache cache = manager.getCache();
            if (cache == null) return;
            Path log = cache.getRootDir().resolve("fetcher.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, java.time.LocalTime.now() + " [" + state + "] " + line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private void debug(String line) {
        debug(lastParams, line);
    }

    // The 4-check variant from the proven modules: catches server-forced closes and menu
    // swaps even without the OpenScreenEvent.
    private boolean contextOpen() {
        Minecraft mc = Minecraft.getInstance();
        return activeScreen != null
            && menu != null
            && mc.gui.screen() == activeScreen
            && mc.player != null
            && mc.player.containerMenu == menu
            && activeScreen.getMenu() == menu;
    }

    private static long verifyTimeout(Params params) {
        return Math.max(1500, params.clickIntervalMs() * 4L);
    }
}
