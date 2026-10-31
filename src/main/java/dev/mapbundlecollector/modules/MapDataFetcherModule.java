package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import dev.mapbundlecollector.gui.MapArtUi;
import dev.mapbundlecollector.mapart.MapArtManager;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.ContainerSlotUpdateEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;

/** Fetches map colors for bundled maps by briefly making them loose in the player inventory. */
public final class MapDataFetcherModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> clicksPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-second")
        .description("Paced click rate, evenly spaced; effectively capped at 20/s (the engine never sends more than one click per game tick).")
        .defaultValue(40)
        .min(5)
        .max(100)
        .sliderRange(5, 100)
        .build());

    private final Setting<Integer> batchSize = sgGeneral.add(new IntSetting.Builder()
        .name("batch-size")
        .description("How many uncached maps to make loose per wave (one inventory slot each, max 36).")
        .defaultValue(24)
        .min(1)
        .max(36)
        .sliderRange(1, 36)
        .build());

    private final Setting<Integer> stallTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("stall-timeout-s")
        .description("Stop a wave when no map data AND no container syncs arrived for this many seconds.")
        .defaultValue(30)
        .min(5)
        .max(300)
        .sliderRange(5, 300)
        .build());

    private final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-start")
        .description("Start fetching automatically when a chest/shulker with fetchable bundles is opened.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write chest plans, wave decisions, stalls and give-ups to mapbundlecollector/fetcher.log for diagnostics.")
        .defaultValue(true)
        .build());

    private final MapDataFetcher fetcher = new MapDataFetcher(MapArtManager.get(), this::feedback);

    public MapDataFetcherModule() {
        super(MapBundleCollectorAddon.CATEGORY, "map-data-fetcher",
            "Fetches map colors for bundled maps the server never sends, by making each map loose for a moment. Clicks are verified against the server and any surprise safely aborts.");
    }

    @Override
    public void onActivate() {
        fetcher.reset();
        warnIfSiblingMoversActive();
        Screen screen = mc.gui.screen();
        if (isSupportedContainer(screen)) {
            fetcher.begin((AbstractContainerScreen<?>) screen);
        } else {
            feedback("§eOpen a chest or shulker with map bundles to start fetching.");
        }
    }

    // These siblings move maps automatically; running them together makes them fight the
    // fetcher over the same loose maps (the fetcher now FOLLOWS moved maps, but not having
    // them fight at all is faster and safer).
    private void warnIfSiblingMoversActive() {
        warnIfActive(MapBundleCollector.class);
        warnIfActive(MapChestSorter.class);
        warnIfActive(MapDuplicateExtractor.class);
    }

    private void warnIfActive(Class<? extends Module> type) {
        Module module = Modules.get().get(type);
        if (module != null && module.isActive()) {
            feedback("§eWarning: " + type.getSimpleName()
                + " is active and moves maps automatically — it will fight the fetcher. Disable it while fetching.");
        }
    }

    @Override
    public void onDeactivate() {
        fetcher.stopNow();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (isSupportedContainer(event.screen)) {
            if (autoStart.get()) {
                fetcher.begin((AbstractContainerScreen<?>) event.screen);
            } else {
                // One-time hint per container opening: don't leave the user wondering why
                // nothing happens with auto-start off.
                fetcher.hintFetchable((AbstractContainerScreen<?>) event.screen);
            }
        } else {
            fetcher.onClosed();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        fetcher.tick(new MapDataFetcher.Params(
            clickIntervalMs(),
            batchSize.get(),
            stallTimeout.get() * 1000L,
            isXyFirst(),
            isEvmodBridge(),
            debugLog.get()));
    }

    // Container-sync heartbeats for the fetcher's stall detector, scoped to the fetch's own
    // container id so unrelated syncs cannot suppress the dead-man's switch. Decisions
    // themselves stay tick-driven re-reads of the server-synced menu (the cursor has no sync
    // event at all).
    @EventHandler
    private void onContainerSlotUpdate(ContainerSlotUpdateEvent event) {
        fetcher.noteContainerSync(event.packet.getContainerId());
    }

    @EventHandler
    private void onInventoryEvent(InventoryEvent event) {
        fetcher.noteContainerSync(event.packet.containerId());
    }

    private boolean isSupportedContainer(Screen screen) {
        return screen instanceof ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof DispenserScreen
            || screen instanceof HopperScreen;
    }

    // Even spacing = 1000/cps (SPEED rule 6). The fetcher's tick driver structurally sends at
    // most one click per game tick, so configured rates above 20/s are effectively capped at
    // 20/s — bursts above one click per tick are never sent.
    private int clickIntervalMs() {
        return Math.max(1, Math.round(1000f / clicksPerSecond.get()));
    }

    // Same name-interpretation order the viewer/cache use, so fetched colors persist at the
    // exact tile locations the renderer later reads (MapArtManager does the same lookup).
    private boolean isXyFirst() {
        MapArtViewerModule viewer = Modules.get().get(MapArtViewerModule.class);
        return viewer == null || viewer.coordinateFormat.get() == MapArtViewerModule.CoordinateFormat.X_Y;
    }

    // Borrow Ev's Mod's per-server map cache before scanning (same bridge the viewer uses).
    private boolean isEvmodBridge() {
        MapArtViewerModule viewer = Modules.get().get(MapArtViewerModule.class);
        return viewer == null || viewer.evmodBridge.get();
    }

    private void feedback(String message) {
        MapArtUi.chat("§9[Map Fetcher]§r " + message);
    }
}
