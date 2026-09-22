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
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MapBundleCollector extends Module {
    private static final Pattern FINAL_NUMERIC_SUFFIX = Pattern.compile("\\s+\\d+$");
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

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
    private AbstractContainerScreen<?> activeScreen;
    private AbstractContainerMenu activeHandler;
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
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive()) return;

        Screen screen = event.screen;
        if (screen == null) {
            reset();
            state = State.WAIT_FOR_CONTAINER;
            return;
        }

        if (!isSupportedContainer(screen)) {
            reset();
            state = State.WAIT_FOR_CONTAINER;
            return;
        }

        activeScreen = (AbstractContainerScreen<?>) screen;
        activeHandler = activeScreen.getMenu();
        rejectedBundles.clear();
        nextActionAt = System.currentTimeMillis() + openDelay.get();
        state = State.DELAY;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        if (mc.player == null || mc.gameMode == null) return;
        if (state == State.IDLE || state == State.WAIT_FOR_CONTAINER || state == State.DONE) return;

        if (!contextIsStillOpen()) {
            reset();
            state = State.WAIT_FOR_CONTAINER;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;

        switch (state) {
            case DELAY -> {
                state = State.SCAN;
                nextActionAt = now;
            }
            case SCAN -> scanForMaps();
            case FIND_BUNDLE -> findBundle();
            case MOVE_MAP -> moveMap(now);
            case VERIFY -> verify(now);
            default -> {}
        }
    }

    private boolean contextIsStillOpen() {
        return activeScreen != null
            && mc.gui.screen() == activeScreen
            && mc.player != null
            && mc.player.containerMenu == activeHandler;
    }

    private boolean isSupportedContainer(Screen screen) {
        return screen instanceof ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof HopperScreen
            || screen instanceof DispenserScreen;
    }

    private void reset() {
        state = State.IDLE;
        activeScreen = null;
        activeHandler = null;
        mapSlot = -1;
        bundleSlot = -1;
        movePhase = 0;
        stateAfterReturn = State.SCAN;
        rejectedBundles.clear();
    }

    private void scanForMaps() {
        mapSlot = findMatchingMapSlot();
        if (mapSlot < 0) {
            finish("All matching maps collected");
            return;
        }
        state = State.FIND_BUNDLE;
    }

    private int findMatchingMapSlot() {
        String configured = targetMapName.get();
        if (configured == null || configured.trim().isEmpty()) {
            return -1;
        }

        String target = normalize(configured);
        int containerSlotCount = activeHandler.slots.size() - Inventory.INVENTORY_SIZE;

        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = activeHandler.getSlot(i);
            if (slot.container instanceof Inventory) continue;

            ItemStack stack = slot.getItem();
            if (!stack.is(Items.FILLED_MAP)) continue;

            String mapName = normalize(stack.getHoverName().getString());
            mapName = FINAL_NUMERIC_SUFFIX.matcher(mapName).replaceFirst("");

            boolean matches = switch (matchMode.get()) {
                case Exact -> mapName.equals(target);
                case Contains -> mapName.contains(target);
            };

            if (matches) return slot.index;
        }

        return -1;
    }

    private String normalize(String value) {
        String trimmed = value.trim();
        return caseSensitive.get() ? trimmed : trimmed.toLowerCase(Locale.ROOT);
    }

    private void findBundle() {
        bundleSlot = -1;

        for (Slot slot : activeHandler.slots) {
            if (!(slot.container instanceof Inventory)) continue;
            if (rejectedBundles.contains(slot.index)) continue;

            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            BundleContents contents = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
            if (contents.weight().result().orElse(Fraction.ONE).compareTo(Fraction.ONE) < 0) {
                bundleSlot = slot.index;
                break;
            }
        }

        if (bundleSlot < 0) {
            finish("No bundle space");
            return;
        }

        movePhase = 0;
        mapCountBefore = activeHandler.getSlot(mapSlot).getItem().getCount();
        state = State.MOVE_MAP;
    }

    private void moveMap(long now) {
        if (!contextIsStillOpen()) return;
        if (movePhase == 0) {
            click(bundleSlot, 0);
            movePhase = 1;
        } else if (movePhase == 1) {
            click(mapSlot, 0);
            state = State.VERIFY;
        } else {
            click(bundleSlot, 0);
            movePhase = 3;
            state = State.VERIFY;
        }
        nextActionAt = now + actionDelay.get();
    }

    private void verify(long now) {
        ItemStack cursor = activeHandler.getCarried();

        if (movePhase == 3) {
            if (cursor.isEmpty()) state = stateAfterReturn;
            else finish("No bundle space");
            nextActionAt = now + actionDelay.get();
            return;
        }

        ItemStack source = activeHandler.getSlot(mapSlot).getItem();
        boolean inserted = source.isEmpty() || source.getCount() < mapCountBefore;

        if (!(cursor.getItem() instanceof BundleItem)) {
            finish("No bundle space");
        } else if (!inserted) {
            rejectedBundles.add(bundleSlot);
            stateAfterReturn = State.FIND_BUNDLE;
            movePhase = 2;
            state = State.MOVE_MAP;
        } else {
            mapSlot = findMatchingMapSlot();
            BundleContents contents = cursor.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);

            if (mapSlot >= 0 && contents.weight().result().orElse(Fraction.ONE).compareTo(Fraction.ONE) < 0) {
                mapCountBefore = activeHandler.getSlot(mapSlot).getItem().getCount();
                movePhase = 1;
                state = State.MOVE_MAP;
            } else {
                stateAfterReturn = State.SCAN;
                movePhase = 2;
                state = State.MOVE_MAP;
            }
        }
        nextActionAt = now + actionDelay.get();
    }

    private void click(int slot, int button) {
        mc.gameMode.handleContainerInput(activeHandler.containerId, slot, button, ContainerInput.PICKUP, mc.player);
    }

    private void finish(String message) {
        state = State.DONE;
        if (localNotifications.get()) showLocalNotification(message);
    }

    private void showLocalNotification(String message) {
        SystemToast.addOrUpdate(
            mc.gui.toastManager(),
            TOAST_ID,
            Component.literal("Map Bundle Collector"),
            Component.literal(message)
        );
    }

    private enum State {
        IDLE,
        WAIT_FOR_CONTAINER,
        DELAY,
        SCAN,
        FIND_BUNDLE,
        MOVE_MAP,
        VERIFY,
        DONE
    }
}
