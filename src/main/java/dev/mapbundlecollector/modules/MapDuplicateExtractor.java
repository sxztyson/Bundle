package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.toast.SystemToast;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts duplicate coordinate-named maps from bundles into an empty bundle. */
public final class MapDuplicateExtractor extends Module {
    private static final Pattern COORDINATE = Pattern.compile("^(.+?)[_\\-\\s,\\[\\(]+(\\d+)[_\\-\\s,xX]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
    private static final SystemToast.Type TOAST_TYPE = SystemToast.Type.PERIODIC_NOTIFICATION;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<CoordinateFormat> coordinateFormat = sgGeneral.add(new EnumSetting.Builder<CoordinateFormat>()
        .name("coordinate-order")
        .description("How the two numbers in each map name are interpreted.")
        .defaultValue(CoordinateFormat.X_Y)
        .build());

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in milliseconds between bundle actions.")
        .defaultValue(150)
        .min(50)
        .sliderMax(1000)
        .build());

    private final Setting<Boolean> localNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("local-notifications")
        .description("Show chat and toast status and completion notices.")
        .defaultValue(true)
        .build());

    private State state = State.WAIT_FOR_INVENTORY;
    private InventoryScreen activeScreen;
    private ScreenHandler menu;
    private List<Integer> sourceSlots = List.of();
    private int currentSourceSlot = -1;
    private int currentDestinationSlot = -1;
    private int destinationCountBefore;
    private long nextActionAt;
    private long verifyDeadline;
    private List<PlannedTransfer> plannedTransfers = List.of();
    private int transferIndex;
    private PlannedTransfer currentTransfer;

    public MapDuplicateExtractor() {
        super(MapBundleCollectorAddon.CATEGORY, "map-duplicate-extractor", "Extracts duplicate coordinate maps from bundles into an empty bundle.");
    }

    @Override
    public void onActivate() {
        reset();
        Screen screen = mc.currentScreen;
        if (screen instanceof InventoryScreen inventoryScreen) start(inventoryScreen);
        else notifyUser("Open your inventory with map bundles to extract duplicates");
    }

    @Override
    public void onDeactivate() {
        returnCursorItem();
        reset();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive()) return;
        if (event.screen instanceof InventoryScreen inventoryScreen) {
            start(inventoryScreen);
        } else {
            returnCursorItem();
            reset();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || mc.interactionManager == null) return;
        if (state == State.WAIT_FOR_INVENTORY || state == State.DONE) return;
        if (!contextIsOpen()) {
            returnCursorItem();
            reset();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;

        switch (state) {
            case PREPARE -> prepare(now);
            case SELECT -> selectTarget(now);
            case EXTRACT -> extractTarget(now);
            case VERIFY_EXTRACT -> verifyExtract(now);
            case INSERT -> insertTarget(now);
            case VERIFY_INSERT -> verifyInsert(now);
            default -> { }
        }
    }

    private void start(InventoryScreen screen) {
        reset();
        activeScreen = screen;
        menu = screen.getScreenHandler();
        state = State.PREPARE;
        nextActionAt = System.currentTimeMillis() + 200;
    }

    private void prepare(long now) {
        boolean isXY = coordinateFormat.get() == CoordinateFormat.X_Y;
        List<Integer> emptyBundles = new ArrayList<>();
        List<Integer> filledBundleSlots = new ArrayList<>();

        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.getSlot(menuSlot);
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            ItemStack stack = slot.getStack();
            if (!(stack.getItem() instanceof BundleItem)) continue;

            BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            if (contents.isEmpty()) {
                emptyBundles.add(menuSlot);
            } else {
                filledBundleSlots.add(menuSlot);
            }
        }

        if (filledBundleSlots.isEmpty()) {
            fail("No filled bundles found in inventory");
            return;
        }

        // Track seen coordinates and detect duplicates
        Set<String> seenCoordinates = new HashSet<>();
        List<DuplicateEntry> duplicates = new ArrayList<>();

        for (int slotIndex : filledBundleSlots) {
            ItemStack stack = menu.getSlot(slotIndex).getStack();
            BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);

            for (ItemStack map : contents.iterate()) {
                if (!map.isOf(Items.FILLED_MAP)) continue;
                Matcher matcher = COORDINATE.matcher(net.minecraft.util.Formatting.strip(map.getName().getString()).trim());
                if (!matcher.matches()) continue;

                String project = matcher.group(1).trim().toLowerCase(Locale.ROOT);
                int first = Integer.parseInt(matcher.group(2));
                int second = Integer.parseInt(matcher.group(3));
                int col = isXY ? first : second;
                int row = isXY ? second : first;

                String coordKey = project + ":" + col + "_" + row;
                if (!seenCoordinates.add(coordKey)) {
                    // Duplicate found!
                    duplicates.add(new DuplicateEntry(project, col, row));
                }
            }
        }

        if (duplicates.isEmpty()) {
            finish("No duplicate maps found in bundles");
            return;
        }

        if (emptyBundles.isEmpty()) {
            fail("Found " + duplicates.size() + " duplicate maps. Put an empty bundle in your inventory to extract them");
            return;
        }

        int neededBundles = (duplicates.size() + 63) / 64;
        int bundlesToUse = Math.min(emptyBundles.size(), neededBundles);
        List<Integer> destinationSlots = emptyBundles.subList(0, bundlesToUse);

        List<PlannedTransfer> transfers = new ArrayList<>();
        int maxTransfers = bundlesToUse * 64;
        int transfersToPlan = Math.min(duplicates.size(), maxTransfers);

        for (int i = 0; i < transfersToPlan; i++) {
            int destSlot = destinationSlots.get(i / 64);
            transfers.add(new PlannedTransfer(duplicates.get(i), destSlot));
        }

        plannedTransfers = transfers;
        sourceSlots = filledBundleSlots;
        transferIndex = 0;
        state = State.SELECT;
        nextActionAt = now;

        notifyUser("Extracting " + plannedTransfers.size() + " duplicate maps into " + bundlesToUse + (bundlesToUse == 1 ? " bundle" : " bundles"));
    }

    private void selectTarget(long now) {
        if (transferIndex >= plannedTransfers.size()) {
            finish("Extracted " + plannedTransfers.size() + " duplicate maps into bundle");
            return;
        }

        currentTransfer = plannedTransfers.get(transferIndex);
        DuplicateEntry target = currentTransfer.entry();

        int targetSourceSlot = -1;
        int itemIndex = -1;

        for (int slot : sourceSlots) {
            ItemStack stack = menu.getSlot(slot).getStack();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            BundleContentsComponent contents = stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT);
            int idx = findTargetIndex(contents, target);
            if (idx >= 0) {
                targetSourceSlot = slot;
                itemIndex = idx;
                break;
            }
        }

        if (targetSourceSlot < 0) {
            fail("Duplicate map " + target.column() + "_" + target.row() + " could not be found; stopped safely");
            return;
        }

        currentSourceSlot = targetSourceSlot;
        currentDestinationSlot = currentTransfer.destinationSlot();

        ItemStack source = menu.getSlot(currentSourceSlot).getStack();
        int selected = BundleItem.getSelectedStackIndex(source);
        if (selected != itemIndex) {
            BundleItem.setSelectedStackIndex(source, itemIndex);
            if (mc.getNetworkHandler() == null) {
                fail("Not connected to a server");
                return;
            }
            mc.getNetworkHandler().sendPacket(new BundleItemSelectedC2SPacket(currentSourceSlot, itemIndex));
        }
        state = State.EXTRACT;
        nextActionAt = now + actionDelay.get();
    }

    private int findTargetIndex(BundleContentsComponent contents, DuplicateEntry target) {
        int i = 0;
        for (ItemStack item : contents.iterate()) {
            DuplicateEntry parsed = parseEntry(item);
            if (parsed != null && parsed.column() == target.column() && parsed.row() == target.row() && parsed.project().equals(target.project())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private DuplicateEntry parseEntry(ItemStack map) {
        if (!map.isOf(Items.FILLED_MAP)) return null;
        Matcher matcher = COORDINATE.matcher(net.minecraft.util.Formatting.strip(map.getName().getString()).trim());
        if (!matcher.matches()) return null;
        int first = Integer.parseInt(matcher.group(2));
        int second = Integer.parseInt(matcher.group(3));
        int column = coordinateFormat.get() == CoordinateFormat.X_Y ? first : second;
        int row = coordinateFormat.get() == CoordinateFormat.X_Y ? second : first;
        String project = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        return new DuplicateEntry(project, column, row);
    }

    private void extractTarget(long now) {
        if (!menu.getCursorStack().isEmpty()) {
            // Automatically clear cursor by safely returning held item to inventory
            returnCursorItem();
            if (!menu.getCursorStack().isEmpty()) {
                fail("Inventory full; please clear the item from your cursor");
                return;
            }
            nextActionAt = now + actionDelay.get();
            return;
        }
        destinationCountBefore = bundleSize(menu.getSlot(currentDestinationSlot).getStack());
        click(currentSourceSlot, 1);
        state = State.VERIFY_EXTRACT;
        verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
        nextActionAt = now + actionDelay.get();
    }

    private void verifyExtract(long now) {
        DuplicateEntry carried = parseEntry(menu.getCursorStack());
        DuplicateEntry target = currentTransfer.entry();
        if (carried != null && carried.column() == target.column() && carried.row() == target.row() && carried.project().equals(target.project())) {
            state = State.INSERT;
            nextActionAt = now;
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        returnCursorItem();
        fail("Map extraction timed out / was rejected; stopped safely");
    }

    private void insertTarget(long now) {
        click(currentDestinationSlot, 0);
        state = State.VERIFY_INSERT;
        verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
        nextActionAt = now + actionDelay.get();
    }

    private void verifyInsert(long now) {
        int count = bundleSize(menu.getSlot(currentDestinationSlot).getStack());
        if (menu.getCursorStack().isEmpty() && count >= destinationCountBefore + 1) {
            transferIndex++;
            state = State.SELECT;
            nextActionAt = now;
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        if (menu.getCursorStack().isEmpty()) {
            // Cursor is empty: duplicate map was deposited into bundle
            transferIndex++;
            state = State.SELECT;
            nextActionAt = now;
            return;
        }

        returnCursorItem();
        fail("The destination bundle could not accept the duplicate map (returned safely)");
    }

    private int bundleSize(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT).size();
    }

    private void click(int slot, int button) {
        mc.interactionManager.clickSlot(menu.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private boolean contextIsOpen() {
        return activeScreen != null
            && mc.currentScreen == activeScreen
            && mc.player != null
            && mc.player.currentScreenHandler == menu;
    }

    private void returnCursorItem() {
        if (menu == null || mc.player == null) return;
        if (menu.getCursorStack().isEmpty()) return;

        // Try to find an empty slot in player inventory
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.inventory instanceof PlayerInventory && s.getStack().isEmpty()) {
                click(i, 0);
                return;
            }
        }

        // If player inventory is full, try original source slot if empty
        if (currentSourceSlot >= 0 && currentSourceSlot < menu.slots.size()) {
            Slot s = menu.getSlot(currentSourceSlot);
            if (s.getStack().isEmpty()) {
                click(currentSourceSlot, 0);
                return;
            }
        }
    }

    private void finish(String message) {
        returnCursorItem();
        state = State.DONE;
        notifyUser("§a" + message);
    }

    private void fail(String message) {
        returnCursorItem();
        state = State.DONE;
        notifyUser("§c" + message);
    }

    private void notifyUser(String message) {
        if (!localNotifications.get()) return;
        ChatUtils.sendMsg("Map Duplicate Extractor", Text.literal(message));
        mc.getToastManager().add(new SystemToast(
            TOAST_TYPE,
            Text.literal("Map Duplicate Extractor"),
            Text.literal(message.replaceAll("§[0-9a-fk-or]", ""))
        ));
    }

    private void reset() {
        state = State.WAIT_FOR_INVENTORY;
        activeScreen = null;
        menu = null;
        sourceSlots = List.of();
        currentSourceSlot = -1;
        currentDestinationSlot = -1;
        destinationCountBefore = 0;
        plannedTransfers = List.of();
        transferIndex = 0;
        currentTransfer = null;
    }

    public enum CoordinateFormat {
        X_Y,
        Y_X
    }

    private enum State {
        WAIT_FOR_INVENTORY,
        PREPARE,
        SELECT,
        EXTRACT,
        VERIFY_EXTRACT,
        INSERT,
        VERIFY_INSERT,
        DONE
    }

    private record DuplicateEntry(String project, int column, int row) { }

    private record PlannedTransfer(DuplicateEntry entry, int destinationSlot) { }
}
