package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import dev.mapbundlecollector.mapart.MapArtManager;
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
        .description("Keep scanned map tiles in memory when bundles are removed (allows assembling huge 50x50 maps across multiple inventory loads).")
        .defaultValue(true)
        .build());

    private int tickCounter = 0;

    public MapArtViewerModule() {
        super(MapBundleCollectorAddon.CATEGORY, "map-art-viewer", "Builds up and previews multi-part map art directly from bundles in your inventory.");
    }

    public void toggleKeepLoaded() {
        keepLoaded.set(!keepLoaded.get());
        if (!keepLoaded.get() && mc.player != null) {
            MapArtManager.get().forceUpdate(mc.player);
        }
    }

    public void clearLoaded() {
        MapArtManager.get().clear();
        if (mc.player != null) {
            mc.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("§c[Map Art]§r Cleared all loaded maps from memory."));
            try {
                mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                    net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
            } catch (Exception ignored) {}
        }
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

    @Override
    public void onActivate() {
        if (mc.player != null) {
            MapArtManager.get().update(mc.player);
        }
    }

    @Override
    public void onDeactivate() {
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
