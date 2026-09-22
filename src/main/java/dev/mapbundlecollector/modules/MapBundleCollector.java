package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.apache.commons.lang3.math.Fraction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MapBundleCollector extends Module {
    private static final Pattern FINAL_NUMERIC_SUFFIX = Pattern.compile("\\s+\\d+$");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetMapName = sgGeneral.add(new StringSetting.Builder()
        .name("target-map-name")
        .description("Base display name of filled maps to collect. A final numeric suffix is ignored.")
        .defaultValue("")
        .build());

    private final Setting<MatchMode> matchMode = sgGeneral.add(new EnumSetting.Builder<MatchMode>()
        .name("match-mode")
        .description("Whether the normalized map name must equal or contain the target name.")
        .defaultValue(MatchMode.Exact)
        .build());

    private final Setting<Boolean> caseSensitive = sgGeneral.add(new BoolSetting.Builder()
        .name("case-sensitive")
        .description("Match uppercase and lowercase characters exactly.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in milliseconds between inventory actions.")
        .defaultValue(100)
        .min(0)
        .sliderMax(1000)
        .build());

    private final Setting<Integer> openDelay = sgGeneral.add(new IntSetting.Builder()
        .name("open-delay")
        .description("Delay in milliseconds before scanning a manually opened container.")
        .defaultValue(200)
        .min(0)
        .sliderMax(2000)
        .build());

    private final Setting<Boolean> localNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("local-notifications")
        .description("Show privacy-safe, client-only completion notices.")
        .defaultValue(false)
        .build());

    private State state = State.IDLE;
    private HandledScreen<?> activeScreen;
    private ScreenHandler activeHandler;
    private long nextActionAt;
    private int mapSlot = -1;
    private int bundleSlot = -1;
    private int mapCountBefore;
    private int movePhase;
    private State stateAfterReturn = State.SCAN;
    private final Set<Integer> rejectedBundles = new HashSet<>();

    public MapBundleCollector() {
        super(MapBundleCollectorAddon.CATEGORY, "map-bundle-collector", "Moves matching filled maps into bundles after you manually open a container.");
    }

    @Override
    public void onActivate() {
        reset();
        state = State.WAIT_FOR_CONTAINER;
        beginIfSupported(mc.currentScreen);
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen == null) {
            resetToWaiting();
            return;
        }

        if (!beginIfSupported(event.screen)) resetToWaiting();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (state == State.IDLE || state == State.WAIT_FOR_CONTAINER) return;
        if (!contextIsStillOpen()) {
            resetToWaiting();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;

        switch (state) {
            case OPEN_DELAY -> state = State.SCAN;
            case SCAN -> scan();
            case FIND_BUNDLE -> findBundle();
            case MOVE_MAP -> moveMap(now);
            case VERIFY -> verify(now);
            case DONE -> resetToWaiting();
            default -> { }
        }
    }

    private boolean beginIfSupported(Screen screen) {
        if (!(screen instanceof HandledScreen<?> handled) || !isSupported(screen)) return false;

        activeScreen = handled;
        activeHandler = handled.getScreenHandler();
        rejectedBundles.clear();
        state = State.OPEN_DELAY;
        nextActionAt = System.currentTimeMillis() + openDelay.get();
        return true;
    }

    private boolean isSupported(Screen screen) {
        return screen instanceof GenericContainerScreen
            || screen instanceof Generic3x3ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof HopperScreen;
    }

    private boolean contextIsStillOpen() {
        return mc.player != null && mc.interactionManager != null
            && mc.currentScreen == activeScreen
            && mc.player.currentScreenHandler == activeHandler
            && activeScreen.getScreenHandler() == activeHandler;
    }

    private void scan() {
        mapSlot = findMatchingMapSlot();
        if (mapSlot < 0) {
            finish("No matching maps");
            return;
        }
        rejectedBundles.clear();
        state = State.FIND_BUNDLE;
    }

    private int findMatchingMapSlot() {
        for (Slot slot : activeHandler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isOf(Items.FILLED_MAP) && matches(stack.getName().getString())) return slot.id;
        }
        return -1;
    }

    private boolean matches(String displayName) {
        String candidate = FINAL_NUMERIC_SUFFIX.matcher(displayName.strip()).replaceFirst("").strip();
        String target = targetMapName.get().strip();
        if (target.isEmpty()) return false;
        if (!caseSensitive.get()) {
            candidate = candidate.toLowerCase(Locale.ROOT);
            target = target.toLowerCase(Locale.ROOT);
        }
        return matchMode.get() == MatchMode.Exact ? candidate.equals(target) : candidate.contains(target);
    }

    private void findBundle() {
        bundleSlot = -1;
        for (Slot slot : activeHandler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || rejectedBundles.contains(slot.id)) continue;
            ItemStack stack = slot.getStack();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.getOccupancy().compareTo(Fraction.ONE) < 0) {
                bundleSlot = slot.id;
                break;
            }
        }

        if (bundleSlot < 0) {
            finish("No bundle space");
            return;
        }

        movePhase = 0;
        mapCountBefore = activeHandler.getSlot(mapSlot).getStack().getCount();
        state = State.MOVE_MAP;
    }

    private void moveMap(long now) {
        if (!contextIsStillOpen()) return;
        if (movePhase == 0) {
            // In 1.21.11 the bundle must be on the cursor.
            click(bundleSlot, 0);
            movePhase = 1;
        } else if (movePhase == 1) {
            // BundleItem.onStackClicked inserts from a non-empty slot only for
            // ClickType.LEFT. RIGHT is the removal path for an empty slot.
            click(mapSlot, 0);
            state = State.VERIFY;
        } else {
            // Return the held bundle only after it is full, rejected the map,
            // or no matching maps remain in this container.
            click(bundleSlot, 0);
            movePhase = 3;
            state = State.VERIFY;
        }
        nextActionAt = now + actionDelay.get();
    }

    private void verify(long now) {
        ItemStack cursor = activeHandler.getCursorStack();

        if (movePhase == 3) {
            if (cursor.isEmpty()) state = stateAfterReturn;
            else finish("No bundle space");
            nextActionAt = now + actionDelay.get();
            return;
        }

        ItemStack source = activeHandler.getSlot(mapSlot).getStack();
        boolean inserted = source.isEmpty() || source.getCount() < mapCountBefore;

        if (!(cursor.getItem() instanceof BundleItem)) {
            // A server correction changed the cursor unexpectedly. Stop without
            // issuing further inventory actions.
            finish("No bundle space");
        } else if (!inserted) {
            // This bundle cannot accept the current map. Return it, then try a
            // different bundle without losing our place in the container.
            rejectedBundles.add(bundleSlot);
            stateAfterReturn = State.FIND_BUNDLE;
            movePhase = 2;
            state = State.MOVE_MAP;
        } else {
            mapSlot = findMatchingMapSlot();
            BundleContentsComponent contents = cursor.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);

            if (mapSlot >= 0 && contents.getOccupancy().compareTo(Fraction.ONE) < 0) {
                // Keep the same bundle on the cursor and collect the next map.
                mapCountBefore = activeHandler.getSlot(mapSlot).getStack().getCount();
                movePhase = 1;
                state = State.MOVE_MAP;
            } else {
                // Return this bundle. SCAN will either select another bundle for
                // remaining maps or finish when the chest has been exhausted.
                stateAfterReturn = State.SCAN;
                movePhase = 2;
                state = State.MOVE_MAP;
            }
        }
        nextActionAt = now + actionDelay.get();
    }

    private void click(int slot, int button) {
        mc.interactionManager.clickSlot(activeHandler.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private void finish(String message) {
        state = State.DONE;
        if (localNotifications.get()) showLocalNotification(message);
    }

    private void showLocalNotification(String message) {
        // Deliberately uses only the local toast manager. No chat, command, packet,
        // logger, coordinates, container data, item data, or server-visible output.
        mc.getToastManager().add(new net.minecraft.client.toast.SystemToast(
            net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
            Text.literal("Map Bundle Collector"), Text.literal(message)
        ));
    }

    private void resetToWaiting() {
        reset();
        if (isActive()) state = State.WAIT_FOR_CONTAINER;
    }

    private void reset() {
        state = State.IDLE;
        activeScreen = null;
        activeHandler = null;
        mapSlot = -1;
        bundleSlot = -1;
        mapCountBefore = 0;
        movePhase = 0;
        stateAfterReturn = State.SCAN;
        rejectedBundles.clear();
    }

    private enum State {
        IDLE,
        WAIT_FOR_CONTAINER,
        OPEN_DELAY,
        SCAN,
        FIND_BUNDLE,
        MOVE_MAP,
        VERIFY,
        DONE
    }
}
