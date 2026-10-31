package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import dev.mapbundlecollector.gui.MapArtChunkRenderer;
import dev.mapbundlecollector.gui.MapArtUi;
import dev.mapbundlecollector.mapart.MapArtManager;
import java.nio.file.Path;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public final class MapArtViewerModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> sidePanel = sgGeneral.add(new BoolSetting.Builder()
        .name("side-panel")
        .description("Display the live stitched map preview on the side of inventory screens.")
        .defaultValue(true)
        .build());

    public final Setting<Position> position = sgGeneral.add(new EnumSetting.Builder<Position>()
        .name("position")
        .description("Which side of the inventory GUI to attach the map preview.")
        .defaultValue(Position.Right)
        .build());

    public final Setting<CoordinateFormat> coordinateFormat = sgGeneral.add(new EnumSetting.Builder<CoordinateFormat>()
        .name("coordinate-order")
        .description("Format of coordinates in filenames: X_Y is Width x Height (Col_Row), Y_X is Height x Width (Row_Col).")
        .defaultValue(CoordinateFormat.X_Y)
        .build());

    public final Setting<DisplayMode> displayMode = sgGeneral.add(new EnumSetting.Builder<DisplayMode>()
        .name("display-mode")
        .description("Display mode: Smart automatically prevents covering your inventory slots and adapts to recipe book.")
        .defaultValue(DisplayMode.Smart)
        .build());

    public final Setting<Integer> previewSize = sgGeneral.add(new IntSetting.Builder()
        .name("preview-size")
        .description("Maximum width of the map preview on the inventory screen.")
        .defaultValue(100)
        .min(50)
        .sliderRange(50, 180)
        .build());

    public final Setting<Boolean> showMissing = sgGeneral.add(new BoolSetting.Builder()
        .name("show-missing")
        .description("Highlight missing pieces and list missing coordinates.")
        .defaultValue(true)
        .build());

    public final Setting<Boolean> showCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("show-coordinates")
        .description("Show [x, y] coordinates on map art pieces.")
        .defaultValue(false)
        .build());

    public final Setting<Boolean> keepLoaded = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-loaded")
        .description("Keep scanned map tiles in memory when bundles are removed (allows assembling huge maps across multiple inventory loads).")
        .defaultValue(true)
        .build());

    public final Setting<Integer> mapWidth = sgGeneral.add(new IntSetting.Builder()
        .name("map-width")
        .description("Expected width in tiles (columns); 0 = auto. Set 204 for a 204x124 mapart.")
        .defaultValue(0)
        .min(0)
        .max(1024)
        .sliderRange(0, 1024)
        .onChanged(v -> rescan())
        .build());

    public final Setting<Integer> mapHeight = sgGeneral.add(new IntSetting.Builder()
        .name("map-height")
        .description("Expected height in tiles (rows); 0 = auto. Set 124 for a 204x124 mapart.")
        .defaultValue(0)
        .min(0)
        .max(1024)
        .sliderRange(0, 1024)
        .onChanged(v -> rescan())
        .build());

    public final Setting<Boolean> persistentCache = sgGeneral.add(new BoolSetting.Builder()
        .name("persistent-cache")
        .description("Remember scanned tiles on disk (.minecraft/mapbundlecollector/) so map art survives restarts until deleted with Del Cache.")
        .defaultValue(true)
        .onChanged(v -> rescan())
        .build());

    public final Setting<Boolean> cacheTileColors = sgGeneral.add(new BoolSetting.Builder()
        .name("cache-tile-colors")
        .description("Also store each tile's rendered colors on disk so cached pieces display without reloading the bundle.")
        .defaultValue(true)
        .build());

    public final Setting<Boolean> evmodBridge = sgGeneral.add(new BoolSetting.Builder()
        .name("evmod-cache-bridge")
        .description("If Ev's Mod is installed, borrow its per-server map cache to fill in bundled maps the server never sends data for. No effect (and no dependency) when Ev's Mod is absent.")
        .defaultValue(true)
        .build());

    public final Setting<Integer> maxFullResChunks = sgGeneral.add(new IntSetting.Builder()
        .name("max-full-res-chunks")
        .description("How many 2048x2048 high-res chunks to keep on the GPU (~16MB VRAM per chunk).")
        .defaultValue(12)
        .min(4)
        .max(64)
        .sliderRange(4, 64)
        .onChanged(v -> MapArtChunkRenderer.get().setMaxFullResChunks(v))
        .build());

    private int tickCounter = 0;

    public MapArtViewerModule() {
        super(MapBundleCollectorAddon.CATEGORY, "map-art-viewer", "Builds up and previews multi-part map art directly from bundles in your inventory.");
    }

    public void toggleKeepLoaded() {
        keepLoaded.set(!keepLoaded.get());
        if (keepLoaded.get()) {
            MapArtUi.chat("§a[Map Art]§r Keep Loaded: §aON§r (maps stay in memory)");
            MapArtUi.clickSound(1.2f);
        } else {
            MapArtUi.chat("§e[Map Art]§r Keep Loaded: §cOFF§r (live inventory only)");
            MapArtUi.clickSound(1.2f);
            if (mc.player != null) {
                MapArtManager.get().forceUpdate(mc.player);
            }
        }
    }

    public void clearLoaded() {
        MapArtManager.get().clear();
        if (persistentCache.get()) {
            MapArtUi.chat("§c[Map Art]§r View cleared - cached projects stay hidden until their maps return to your inventory (or restart). Disk cache untouched; Del Cache deletes the viewed project.");
        } else {
            MapArtUi.chat("§c[Map Art]§r Cleared all loaded maps from memory.");
        }
        MapArtUi.clickSound(1.0f);
    }

    public void toggleCoordinateOrder() {
        if (coordinateFormat.get() == CoordinateFormat.X_Y) {
            coordinateFormat.set(CoordinateFormat.Y_X);
        } else {
            coordinateFormat.set(CoordinateFormat.X_Y);
        }
        if (mc.player != null) {
            MapArtManager.get().forceUpdate(mc.player);
        }
    }

    public void exportMissing() {
        MapArtManager manager = MapArtManager.get();
        int missingCount = manager.getActiveSet() != null ? manager.getActiveSet().getMissingTiles().size() : 0;
        Path path = manager.exportMissingJson();
        if (path == null) {
            MapArtUi.chat("§c[Map Art]§r Export failed: no active project or cache unavailable.");
            return;
        }
        MapArtUi.chat("§a[Map Art]§r Exported " + missingCount + " missing tiles to " + path.toAbsolutePath());
        MapArtUi.clickSound(1.0f);
    }

    public void deleteCache() {
        if (MapArtManager.get().deleteActiveProjectCache()) {
            MapArtUi.chat("§c[Map Art]§r Deleted the persistent cache for this project.");
            MapArtUi.clickSound(0.8f);
        } else {
            MapArtUi.chat("§e[Map Art]§r No persistent cache to delete.");
        }
    }

    private void rescan() {
        if (mc.player != null) {
            MapArtManager.get().forceUpdate(mc.player);
        }
    }

    @Override
    public void onActivate() {
        MapArtChunkRenderer.get().setMaxFullResChunks(maxFullResChunks.get());
        if (mc.player != null) {
            MapArtManager.get().update(mc.player);
        }
    }

    @Override
    public void onDeactivate() {
        MapArtManager.get().flushCache();
        MapArtManager.get().clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Re-scan inventory bundles periodically (every 10 ticks = 0.5s)
        if (++tickCounter >= 10) {
            tickCounter = 0;
            MapArtManager.get().update(mc.player);
        }
    }

    public enum Position {
        Left,
        Right
    }

    public enum CoordinateFormat {
        X_Y,
        Y_X
    }

    public enum DisplayMode {
        Smart,
        SideCard,
        TopBadge
    }
}
