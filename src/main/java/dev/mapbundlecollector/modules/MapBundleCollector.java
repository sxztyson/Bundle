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
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.apache.commons.lang3.math.Fraction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapBundleCollector extends Module {
    private static final Pattern FINAL_NUMERIC_SUFFIX = Pattern.compile("\\s+\\d+$");
    private static final Pattern GRID_PROJECT_PATTERN = Pattern.compile("^(.*?)[_\\-\\s,\\[\\(#]+(\\d+)(?:[_\\-\\s,xX]+(\\d+))?[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetMapName = sgGeneral.add(new StringSetting.Builder()
        .name("target-map-name")
        .description("Name/prefix of the map art project to collect. If empty, collects all maps.")
        .defaultValue("")
        .build());

    private final Setting<Boolean> separateProjects = sgGeneral.add(new BoolSetting.Builder()
        .name("separate-by-project")
        .description("Automatically sort different map projects into separate bundles in your inventory.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> extractFromChestBundles = sgGeneral.add(new BoolSetting.Builder()
        .name("extract-from-chest-bundles")
        .description("Also pull matching maps out of bundles found inside the opened chest/container.")
        .defaultValue(true)
        .build());

    private final Setting<MatchMode> matchMode = sgGeneral.add(new EnumSetting.Builder<MatchMode>()
        .name("match-mode")
        .description("Whether the normalized map name must equal or contain the target name.")
        .defaultValue(MatchMode.Contains)
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
        .min(20)
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
        .defaultValue(true)
        .build());

    private State state = State.IDLE;
    private HandledScreen<?> activeScreen;
    private ScreenHandler activeHandler;
    private long nextActionAt;
    private long verifyDeadline;

    private ChestMapTarget currentTarget = null;
    private int bundleSlot = -1;
    private int mapCountBefore;
    private int movePhase;
    private final Set<Integer> rejectedBundles = new HashSet<>();

    public MapBundleCollector() {
        super(MapBundleCollectorAddon.CATEGORY, "map-bundle-collector", "Collects filled maps from opened chests and containers into bundles.");
    }

    @Override
    public void onActivate() {
        reset();
        state = State.WAIT_FOR_CONTAINER;
        beginIfSupported(mc.currentScreen);
    }

    @Override
    public void onDeactivate() {
        returnCursorItem();
        reset();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen == null) {
            returnCursorItem();
            resetToWaiting();
            return;
        }

        if (!beginIfSupported(event.screen)) {
            returnCursorItem();
            resetToWaiting();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (state == State.IDLE || state == State.WAIT_FOR_CONTAINER) return;
        if (!contextIsStillOpen()) {
            returnCursorItem();
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
            case VERIFY_LOOSE -> verifyLoose(now);
            case VERIFY_BUNDLE_EXTRACT -> verifyBundleExtract(now);
            case VERIFY_BUNDLE_INSERT -> verifyBundleInsert(now);
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
        currentTarget = findNextChestTarget();
        if (currentTarget == null) {
            finish("All matching maps collected!");
            return;
        }
        rejectedBundles.clear();
        state = State.FIND_BUNDLE;
    }

    private ChestMapTarget findNextChestTarget() {
        for (Slot slot : activeHandler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();

            // 1. Check loose filled map in chest
            if (stack.isOf(Items.FILLED_MAP)) {
                String raw = Formatting.strip(stack.getName().getString()).trim();
                if (matches(raw)) {
                    String proj = extractProjectName(raw);
                    return new ChestMapTarget(slot.id, false, -1, proj);
                }
            }

            // 2. Check bundle inside chest
            if (extractFromChestBundles.get() && stack.getItem() instanceof BundleItem && stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
                BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (contents != null && !contents.isEmpty()) {
                    int idx = 0;
                    for (ItemStack item : contents.iterate()) {
                        if (item.isOf(Items.FILLED_MAP)) {
                            String raw = Formatting.strip(item.getName().getString()).trim();
                            if (matches(raw)) {
                                String proj = extractProjectName(raw);
                                return new ChestMapTarget(slot.id, true, idx, proj);
                            }
                        }
                        idx++;
                    }
                }
            }
        }
        return null;
    }

    public static String extractProjectName(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "Map";
        String clean = Formatting.strip(displayName).trim();

        Matcher m = GRID_PROJECT_PATTERN.matcher(clean);
        if (m.matches()) {
            String p = m.group(1).trim();
            if (!p.isEmpty()) return p;
        }

        String p2 = FINAL_NUMERIC_SUFFIX.matcher(clean).replaceFirst("").trim();
        if (!p2.isEmpty()) return p2;

        return clean;
    }

    private boolean matches(String displayName) {
        String target = targetMapName.get().trim();
        if (target.isEmpty()) return true; // If target name is empty, collect all maps!

        String candidate = FINAL_NUMERIC_SUFFIX.matcher(displayName.strip()).replaceFirst("").strip();
        if (!caseSensitive.get()) {
            candidate = candidate.toLowerCase(Locale.ROOT);
            target = target.toLowerCase(Locale.ROOT);
        }
        return matchMode.get() == MatchMode.Exact ? candidate.equals(target) : candidate.contains(target);
    }

    private void findBundle() {
        if (currentTarget == null) {
            state = State.SCAN;
            return;
        }

        bundleSlot = findBundleForProject(currentTarget.projectName());
        if (bundleSlot < 0) {
            finish("No receptive bundles in inventory for project '" + currentTarget.projectName() + "'");
            return;
        }

        movePhase = 0;
        state = State.MOVE_MAP;
    }

    private int findBundleForProject(String projectName) {
        int emptyBundleSlot = -1;

        // 1. Search player inventory for an existing bundle containing maps with same project
        for (Slot slot : activeHandler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) || rejectedBundles.contains(slot.id)) continue;
            ItemStack stack = slot.getStack();
            if (!(stack.getItem() instanceof BundleItem)) continue;

            BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.getOccupancy().compareTo(Fraction.ONE) >= 0 || contents.size() >= 64) continue;

            if (contents.isEmpty()) {
                if (emptyBundleSlot < 0) emptyBundleSlot = slot.id;
                continue;
            }

            if (separateProjects.get()) {
                boolean hasSameProject = false;
                for (ItemStack item : contents.iterate()) {
                    if (item.isOf(Items.FILLED_MAP)) {
                        String itemProj = extractProjectName(item.getName().getString());
                        if (itemProj.equalsIgnoreCase(projectName)) {
                            hasSameProject = true;
                            break;
                        }
                    }
                }
                if (hasSameProject) {
                    return slot.id;
                }
            } else {
                return slot.id;
            }
        }

        // 2. Use empty bundle in inventory
        if (emptyBundleSlot >= 0) {
            return emptyBundleSlot;
        }

        // 3. Fallback: if not separating by project, any receptive bundle works
        if (!separateProjects.get()) {
            for (Slot slot : activeHandler.slots) {
                if (!(slot.inventory instanceof PlayerInventory) || rejectedBundles.contains(slot.id)) continue;
                ItemStack stack = slot.getStack();
                if (!(stack.getItem() instanceof BundleItem)) continue;
                BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
                if (contents.getOccupancy().compareTo(Fraction.ONE) < 0 && contents.size() < 64) {
                    return slot.id;
                }
            }
        }

        return -1;
    }

    private void moveMap(long now) {
        if (!contextIsStillOpen()) return;

        // Make sure cursor is clear before starting
        if (!activeHandler.getCursorStack().isEmpty()) {
            returnCursorItem();
            if (!activeHandler.getCursorStack().isEmpty()) {
                finish("Inventory full; cannot clear cursor item");
                return;
            }
            nextActionAt = now + actionDelay.get();
            return;
        }

        if (!currentTarget.isBundle()) {
            // Case 1: Loose map in chest
            if (movePhase == 0) {
                // Pick up player inventory bundle onto cursor
                click(bundleSlot, 0);
                movePhase = 1;
                nextActionAt = now + actionDelay.get();
            } else if (movePhase == 1) {
                // Left click loose map in chest to insert into held bundle
                mapCountBefore = activeHandler.getSlot(currentTarget.slotId()).getStack().getCount();
                click(currentTarget.slotId(), 0);
                state = State.VERIFY_LOOSE;
                verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
                nextActionAt = now + actionDelay.get();
            } else {
                // Return bundle back to its inventory slot
                click(bundleSlot, 0);
                movePhase = 3;
                state = State.VERIFY_LOOSE;
                nextActionAt = now + actionDelay.get();
            }
        } else {
            // Case 2: Map is inside a bundle in the chest
            // Select the item index in the chest bundle
            ItemStack chestBundle = activeHandler.getSlot(currentTarget.slotId()).getStack();
            int selected = BundleItem.getSelectedStackIndex(chestBundle);
            if (selected != currentTarget.itemIndex()) {
                BundleItem.setSelectedStackIndex(chestBundle, currentTarget.itemIndex());
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new BundleItemSelectedC2SPacket(currentTarget.slotId(), currentTarget.itemIndex()));
                }
            }

            // Right click chest bundle to pop the map onto cursor
            click(currentTarget.slotId(), 1);
            state = State.VERIFY_BUNDLE_EXTRACT;
            verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
            nextActionAt = now + actionDelay.get();
        }
    }

    private void verifyLoose(long now) {
        ItemStack cursor = activeHandler.getCursorStack();

        if (movePhase == 3) {
            // Bundle was returned to its slot
            if (cursor.isEmpty()) {
                state = State.SCAN;
            } else {
                returnCursorItem();
                state = State.SCAN;
            }
            nextActionAt = now + actionDelay.get();
            return;
        }

        ItemStack source = activeHandler.getSlot(currentTarget.slotId()).getStack();
        boolean inserted = source.isEmpty() || source.getCount() < mapCountBefore;

        if (!(cursor.getItem() instanceof BundleItem)) {
            returnCursorItem();
            finish("Cursor desync");
        } else if (!inserted) {
            // Bundle rejected map. Return bundle, find next
            rejectedBundles.add(bundleSlot);
            movePhase = 2;
            state = State.MOVE_MAP;
            nextActionAt = now + actionDelay.get();
        } else {
            // Map successfully inserted!
            // Check if there is another loose map of the SAME project in the chest to grab with this bundle
            BundleContentsComponent contents = cursor.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            ChestMapTarget nextSameProj = findNextChestTarget();

            if (nextSameProj != null && !nextSameProj.isBundle()
                && nextSameProj.projectName().equalsIgnoreCase(currentTarget.projectName())
                && contents.getOccupancy().compareTo(Fraction.ONE) < 0 && contents.size() < 64) {
                // Keep bundle on cursor and grab the next map of this project immediately!
                currentTarget = nextSameProj;
                movePhase = 1;
                state = State.MOVE_MAP;
            } else {
                // Return this bundle to player inventory
                movePhase = 2;
                state = State.MOVE_MAP;
            }
            nextActionAt = now + actionDelay.get();
        }
    }

    private void verifyBundleExtract(long now) {
        ItemStack carried = activeHandler.getCursorStack();
        if (carried.isOf(Items.FILLED_MAP)) {
            // Extracted map is on cursor. Now left click destination inventory bundle to insert
            click(bundleSlot, 0);
            state = State.VERIFY_BUNDLE_INSERT;
            verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
            nextActionAt = now + actionDelay.get();
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        returnCursorItem();
        finish("Extraction from chest bundle timed out");
    }

    private void verifyBundleInsert(long now) {
        if (activeHandler.getCursorStack().isEmpty()) {
            // Map was successfully deposited into destination bundle!
            state = State.SCAN;
            nextActionAt = now + actionDelay.get();
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        // Return cursor item safely to inventory
        returnCursorItem();
        finish("Destination bundle could not accept map (returned safely)");
    }

    private void click(int slot, int button) {
        mc.interactionManager.clickSlot(activeHandler.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private void returnCursorItem() {
        if (activeHandler == null || mc.player == null) return;
        if (activeHandler.getCursorStack().isEmpty()) return;

        // 1. Try to return to original bundle slot if empty
        if (bundleSlot >= 0 && bundleSlot < activeHandler.slots.size()) {
            Slot s = activeHandler.getSlot(bundleSlot);
            if (s.getStack().isEmpty()) {
                click(bundleSlot, 0);
                return;
            }
        }

        // 2. Try any empty player inventory slot
        for (int i = 0; i < activeHandler.slots.size(); i++) {
            Slot s = activeHandler.getSlot(i);
            if (s.inventory instanceof PlayerInventory && s.getStack().isEmpty()) {
                click(i, 0);
                return;
            }
        }

        // 3. Try any empty container slot
        for (int i = 0; i < activeHandler.slots.size(); i++) {
            Slot s = activeHandler.getSlot(i);
            if (s.getStack().isEmpty()) {
                click(i, 0);
                return;
            }
        }
    }

    private void finish(String message) {
        returnCursorItem();
        state = State.DONE;
        if (localNotifications.get()) showLocalNotification(message);
    }

    private void showLocalNotification(String message) {
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
        currentTarget = null;
        bundleSlot = -1;
        mapCountBefore = 0;
        movePhase = 0;
        rejectedBundles.clear();
    }

    private record ChestMapTarget(int slotId, boolean isBundle, int itemIndex, String projectName) {}

    public enum MatchMode {
        Exact,
        Contains
    }

    private enum State {
        IDLE,
        WAIT_FOR_CONTAINER,
        OPEN_DELAY,
        SCAN,
        FIND_BUNDLE,
        MOVE_MAP,
        VERIFY_LOOSE,
        VERIFY_BUNDLE_EXTRACT,
        VERIFY_BUNDLE_INSERT,
        DONE
    }
}
