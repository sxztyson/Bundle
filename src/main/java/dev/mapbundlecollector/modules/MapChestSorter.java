package dev.mapbundlecollector.modules;

import dev.mapbundlecollector.MapBundleCollectorAddon;
import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
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
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sorts maps from chest/shulker bundles into player inventory bundles with multi-chest resume. */
public final class MapChestSorter extends Module {
    private static final Pattern COORDINATE = Pattern.compile("^(.+?)[_\\-\\s,\\[\\(]+(\\d+)[_\\-\\s,xX]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
    private static final Pattern LAST_COORDINATE = Pattern.compile("^\\s*(?:(.+?)[_\\-\\s,\\[\\(]+)?(\\d+)[_\\-\\s,xX]+(\\d+)[_\\]\\)]*\\s*$");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetMapName = sgGeneral.add(new StringSetting.Builder()
        .name("target-map-name")
        .description("Name/prefix of the map art project to sort. If empty, auto-detects or parses from coordinate.")
        .defaultValue("")
        .build());

    private final Setting<String> lastMapCoordinate = sgGeneral.add(new StringSetting.Builder()
        .name("last-map-coordinate")
        .description("Final coordinate (e.g. 21_15) or full name (e.g. MonaLisa 21_15), or 'auto'.")
        .defaultValue("21_15")
        .build());

    private final Setting<GridDirection> gridDirection = sgGeneral.add(new EnumSetting.Builder<GridDirection>()
        .name("grid-direction")
        .description("Traversal sequence order (Column_First: 21_15 -> 21_14, Row_First: 21_15 -> 20_15).")
        .defaultValue(GridDirection.Column_First)
        .build());

    private final Setting<CoordinateFormat> coordinateFormat = sgGeneral.add(new EnumSetting.Builder<CoordinateFormat>()
        .name("coordinate-order")
        .description("How the two numbers in each map name are interpreted.")
        .defaultValue(CoordinateFormat.X_Y)
        .build());

    private final Setting<OutputOrder> outputOrder = sgGeneral.add(new EnumSetting.Builder<OutputOrder>()
        .name("output-order")
        .description("Order to fetch maps (LastToFirst starts at 21_15, FirstToLast starts at 0_0).")
        .defaultValue(OutputOrder.LastToFirst)
        .build());

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay in milliseconds between actions.")
        .defaultValue(150)
        .min(50)
        .sliderMax(1000)
        .build());

    private final Setting<Boolean> localNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("local-notifications")
        .description("Show chat and toast status and completion notices.")
        .defaultValue(true)
        .build());

    private State state = State.WAIT_FOR_CONTAINER;
    private AbstractContainerScreen<?> activeScreen;
    private AbstractContainerMenu menu;

    private List<TargetCoord> targetQueue = new ArrayList<>();
    private int currentProgressIndex = 0;
    private String detectedProject = null;

    // Coordinates already sorted (kept across chests until the full queue is done) and
    // per-coordinate failure counts so a stuck map is skipped with a report after 3 tries
    // instead of stopping the whole run.
    private final java.util.Set<String> doneCoords = new java.util.HashSet<>();
    private final java.util.Map<String, Integer> coordFailures = new java.util.HashMap<>();
    private int cursorRetries;

    private int currentSourceSlot = -1;
    private int currentDestinationSlot = -1;
    private int destinationCountBefore = 0;
    private boolean isSourceLooseMap = false;

    private long nextActionAt;
    private long verifyDeadline;
    private TargetCoord currentTarget;

    public MapChestSorter() {
        super(MapBundleCollectorAddon.CATEGORY, "map-chest-sorter", "Sorts maps from chests/shulkers into inventory bundles across containers.");
    }

    @Override
    public void onActivate() {
        resetFull();
        Screen screen = mc.gui.screen();
        if (isSupportedContainer(screen)) {
            startContainer((AbstractContainerScreen<?>) screen);
        } else {
            notifyUser("Open a chest or shulker with map bundles to begin sorting");
        }
    }

    @Override
    public void onDeactivate() {
        returnCursorItem();
        resetFull();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isActive()) return;
        Screen screen = event.screen;
        if (isSupportedContainer(screen)) {
            startContainer((AbstractContainerScreen<?>) screen);
        } else {
            // Container closed - safely return cursor item, save progress, wait for next chest
            returnCursorItem();
            activeScreen = null;
            menu = null;
            if (state != State.DONE) {
                state = State.WAIT_FOR_CONTAINER;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || mc.gameMode == null) return;
        if (state == State.WAIT_FOR_CONTAINER || state == State.WAIT_FOR_EMPTY_BUNDLE || state == State.DONE) return;

        if (!contextIsOpen()) {
            returnCursorItem();
            activeScreen = null;
            menu = null;
            state = State.WAIT_FOR_CONTAINER;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;

        switch (state) {
            case PREPARE_QUEUE -> prepareQueue(now);
            case FIND_NEXT_MAP -> findNextMap(now);
            case SELECT -> selectTarget(now);
            case EXTRACT -> extractTarget(now);
            case VERIFY_EXTRACT -> verifyExtract(now);
            case INSERT -> insertTarget(now);
            case VERIFY_INSERT -> verifyInsert(now);
            default -> { }
        }
    }

    private boolean isSupportedContainer(Screen screen) {
        return screen instanceof ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof DispenserScreen
            || screen instanceof HopperScreen;
    }

    private void startContainer(AbstractContainerScreen<?> screen) {
        activeScreen = screen;
        menu = screen.getMenu();

        if (targetQueue.isEmpty()) {
            state = State.PREPARE_QUEUE;
        } else {
            state = State.FIND_NEXT_MAP;
        }
        nextActionAt = System.currentTimeMillis() + 200;
    }

    private void prepareQueue(long now) {
        boolean isXY = coordinateFormat.get() == CoordinateFormat.X_Y;
        String configuredName = targetMapName.get().trim();
        if (!configuredName.isEmpty()) {
            detectedProject = configuredName.toLowerCase(Locale.ROOT);
        } else {
            detectedProject = null;
        }

        String coordSetting = lastMapCoordinate.get().trim();
        boolean autoDetect = coordSetting.isEmpty() || coordSetting.equalsIgnoreCase("auto");

        int maxCol = -1;
        int maxRow = -1;

        if (!autoDetect) {
            Matcher matcher = LAST_COORDINATE.matcher(coordSetting);
            if (matcher.matches()) {
                if (detectedProject == null && matcher.group(1) != null) {
                    detectedProject = matcher.group(1).trim().toLowerCase(Locale.ROOT);
                }
                int first = Integer.parseInt(matcher.group(2));
                int second = Integer.parseInt(matcher.group(3));
                maxCol = isXY ? first : second;
                maxRow = isXY ? second : first;
            }
        }

        // Auto-detect project or dimensions if needed
        if (detectedProject == null || maxCol < 0 || maxRow < 0) {
            // Check MapArtManager active set
            MapArtSet active = MapArtManager.get().getActiveSet();
            if (active != null && active.getCols() > 0 && active.getRows() > 0) {
                if (maxCol < 0 || maxRow < 0) {
                    maxCol = active.getCols() - 1;
                    maxRow = active.getRows() - 1;
                }
                if (detectedProject == null) {
                    detectedProject = active.getProjectId().trim().toLowerCase(Locale.ROOT);
                }
            }
        }

        if (detectedProject == null || maxCol < 0 || maxRow < 0) {
            // Scan container and inventory for map bounds and project name
            for (Slot slot : menu.slots) {
                ItemStack stack = slot.getItem();
                if (stack.is(Items.BUNDLE)) {
                    BundleContents contents = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                    for (ItemStack item : contents.itemCopyStream().toList()) {
                        if (!item.is(Items.FILLED_MAP)) continue;
                        Matcher m = COORDINATE.matcher(net.minecraft.ChatFormatting.stripFormatting(item.getHoverName().getString()).trim());
                        if (m.matches()) {
                            String itemProj = m.group(1).trim().toLowerCase(Locale.ROOT);
                            if (detectedProject != null && !itemProj.equals(detectedProject)) continue;
                            int f = Integer.parseInt(m.group(2));
                            int s = Integer.parseInt(m.group(3));
                            int c = isXY ? f : s;
                            int r = isXY ? s : f;
                            if (c > maxCol) maxCol = c;
                            if (r > maxRow) maxRow = r;
                            if (detectedProject == null) detectedProject = itemProj;
                        }
                    }
                } else if (stack.is(Items.FILLED_MAP)) {
                    Matcher m = COORDINATE.matcher(net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim());
                    if (m.matches()) {
                        String itemProj = m.group(1).trim().toLowerCase(Locale.ROOT);
                        if (detectedProject != null && !itemProj.equals(detectedProject)) continue;
                        int f = Integer.parseInt(m.group(2));
                        int s = Integer.parseInt(m.group(3));
                        int c = isXY ? f : s;
                        int r = isXY ? s : f;
                        if (c > maxCol) maxCol = c;
                        if (r > maxRow) maxRow = r;
                        if (detectedProject == null) detectedProject = itemProj;
                    }
                }
            }
        }

        if (maxCol < 0 || maxRow < 0) {
            fail("Could not detect map coordinates. Please specify last-map-coordinate (e.g. 21_15)");
            return;
        }

        // Build target queue based on gridDirection and outputOrder
        List<TargetCoord> queue = new ArrayList<>();
        GridDirection dir = gridDirection.get();
        OutputOrder order = outputOrder.get();

        if (dir == GridDirection.Column_First) {
            if (order == OutputOrder.LastToFirst) {
                for (int c = maxCol; c >= 0; c--) {
                    for (int r = maxRow; r >= 0; r--) {
                        queue.add(new TargetCoord(c, r));
                    }
                }
            } else {
                for (int c = 0; c <= maxCol; c++) {
                    for (int r = 0; r <= maxRow; r++) {
                        queue.add(new TargetCoord(c, r));
                    }
                }
            }
        } else { // Row_First
            if (order == OutputOrder.LastToFirst) {
                for (int r = maxRow; r >= 0; r--) {
                    for (int c = maxCol; c >= 0; c--) {
                        queue.add(new TargetCoord(c, r));
                    }
                }
            } else {
                for (int r = 0; r <= maxRow; r++) {
                    for (int c = 0; c <= maxCol; c++) {
                        queue.add(new TargetCoord(c, r));
                    }
                }
            }
        }

        targetQueue = queue;
        currentProgressIndex = 0;
        state = State.FIND_NEXT_MAP;
        nextActionAt = now;

        notifyUser("Queue initialized" + (detectedProject != null ? " for '" + detectedProject + "': " : ": ") + targetQueue.size() + " maps (" + (maxCol + 1) + "x" + (maxRow + 1)
            + "), starting at " + formatCoord(targetQueue.getFirst()));
    }

    private void findNextMap(long now) {
        if (doneCoords.size() >= targetQueue.size()) {
            finish("All " + targetQueue.size() + " maps successfully sorted across containers!");
            return;
        }

        // Drain THIS chest completely: work through the queue in order, skipping
        // coordinates that are not present here (they stay pending for a later chest)
        // instead of stopping at the first missing one.
        int presentIndex = findNextPresentIndex();
        if (presentIndex < 0) {
            state = State.WAIT_FOR_CONTAINER;
            int remaining = targetQueue.size() - doneCoords.size();
            notifyUser("§eNothing more from this chest. " + remaining + " map(s) still pending (first: "
                + formatCoord(firstPending()) + ") - open the next chest/shulker.");
            return;
        }
        currentProgressIndex = presentIndex;
        currentTarget = targetQueue.get(presentIndex);
        cursorRetries = 0;

        // Destination: a bundle of the SAME project, or an empty one - never a foreign
        // project's bundle (that is the sun-into-moon mixing bug).
        currentDestinationSlot = findInventoryDestinationSlot();
        if (currentDestinationSlot < 0) {
            state = State.WAIT_FOR_EMPTY_BUNDLE;
            notifyUser("§eNo bundle with space for project '" + detectedProject
                + "'! Store filled bundle(s) and put a fresh empty bundle in inventory.");
            return;
        }

        SourceMatch match = findMap(currentTarget);
        currentSourceSlot = match.slot();
        isSourceLooseMap = match.isLoose();

        if (isSourceLooseMap) {
            state = State.EXTRACT;
            nextActionAt = now;
        } else {
            // Select item inside bundle
            ItemStack sourceStack = menu.getSlot(currentSourceSlot).getItem();
            int selected = BundleItem.getSelectedItemIndex(sourceStack);
            if (selected != match.bundleItemIndex()) {
                BundleItem.toggleSelectedItem(sourceStack, match.bundleItemIndex());
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundSelectBundleItemPacket(currentSourceSlot, match.bundleItemIndex()));
                }
            }
            state = State.EXTRACT;
            nextActionAt = now + actionDelay.get();
        }
    }

    // First queue entry (in order) that is not done yet AND is present in the open
    // container; -1 when this chest holds nothing more for the queue.
    private int findNextPresentIndex() {
        for (int i = 0; i < targetQueue.size(); i++) {
            if (doneCoords.contains(coordKey(targetQueue.get(i)))) continue;
            if (findMap(targetQueue.get(i)) != null) return i;
        }
        return -1;
    }

    private TargetCoord firstPending() {
        for (TargetCoord coord : targetQueue) {
            if (!doneCoords.contains(coordKey(coord))) return coord;
        }
        return targetQueue.getFirst();
    }

    private static String coordKey(TargetCoord coord) {
        return coord.col() + "_" + coord.row();
    }

    // Retry-or-skip on extraction/insertion failures: a map that keeps failing is reported
    // and marked done so the run continues; nothing but real user action stops the sort.
    private void retryOrSkip(String what) {
        String key = coordKey(currentTarget);
        int failures = coordFailures.merge(key, 1, Integer::sum);
        if (failures >= 3) {
            doneCoords.add(key);
            notifyUser("§eSkipped " + formatCoord(currentTarget) + " after " + failures
                + " failed " + what + " attempts - it stays pending, retry on the next chest.");
        }
        state = State.FIND_NEXT_MAP;
        nextActionAt = System.currentTimeMillis() + actionDelay.get();
    }

    private void selectTarget(long now) {
        state = State.EXTRACT;
        nextActionAt = now;
    }

    private void extractTarget(long now) {
        if (!menu.getCarried().isEmpty()) {
            // Automatically clear cursor by safely returning held item to inventory
            returnCursorItem();
            if (!menu.getCarried().isEmpty()) {
                if (++cursorRetries < 10) {
                    nextActionAt = now + actionDelay.get();
                    return;
                }
                retryOrSkip("cursor-clear");
                return;
            }
            cursorRetries = 0;
            nextActionAt = now + actionDelay.get();
            return;
        }

        // The destination may have changed hands (another module, filled up) since it was
        // chosen - re-validate or re-pick BEFORE extracting anything onto the cursor.
        if (!destinationStillValid(currentDestinationSlot)) {
            currentDestinationSlot = findInventoryDestinationSlot();
            if (currentDestinationSlot < 0) {
                state = State.WAIT_FOR_EMPTY_BUNDLE;
                notifyUser("§eDestination bundle vanished or filled - put a fresh empty bundle in inventory.");
                return;
            }
        }

        destinationCountBefore = bundleSize(menu.getSlot(currentDestinationSlot).getItem());

        if (isSourceLooseMap) {
            click(currentSourceSlot, 0); // Pick up loose map with left click
        } else {
            click(currentSourceSlot, 1); // Right click bundle to pop selected item
        }

        state = State.VERIFY_EXTRACT;
        verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
        nextActionAt = now + actionDelay.get();
    }

    private void verifyExtract(long now) {
        ItemStack carried = menu.getCarried();
        if (matchesTarget(carried, currentTarget)) {
            cursorRetries = 0;
            state = State.INSERT;
            nextActionAt = now;
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        // Extraction timed out or popped the wrong entry - return whatever is on the
        // cursor and retry; after repeated failures the coordinate is skipped with a
        // report so the chest keeps sorting.
        returnCursorItem();
        retryOrSkip("extraction");
    }

    private void insertTarget(long now) {
        // Final guard right before the click: never insert into a foreign project's bundle.
        if (!destinationStillValid(currentDestinationSlot)) {
            currentDestinationSlot = findInventoryDestinationSlot();
            if (currentDestinationSlot < 0) {
                returnCursorItem();
                state = State.WAIT_FOR_EMPTY_BUNDLE;
                notifyUser("§eDestination bundle vanished or filled - map returned safely; put a fresh empty bundle in inventory.");
                return;
            }
            destinationCountBefore = bundleSize(menu.getSlot(currentDestinationSlot).getItem());
        }
        click(currentDestinationSlot, 0); // Left click bundle with carried map
        state = State.VERIFY_INSERT;
        verifyDeadline = now + Math.max(1500, actionDelay.get() * 4);
        nextActionAt = now + actionDelay.get();
    }

    private void verifyInsert(long now) {
        int count = bundleSize(menu.getSlot(currentDestinationSlot).getItem());
        if (menu.getCarried().isEmpty() && count >= destinationCountBefore + 1) {
            doneCoords.add(coordKey(currentTarget));
            coordFailures.remove(coordKey(currentTarget));
            state = State.FIND_NEXT_MAP;
            nextActionAt = now;
            return;
        }

        if (now < verifyDeadline) {
            nextActionAt = now + 40;
            return;
        }

        if (menu.getCarried().isEmpty()) {
            // Cursor is empty: the map was accepted by server even if count didn't update in time
            doneCoords.add(coordKey(currentTarget));
            coordFailures.remove(coordKey(currentTarget));
            state = State.FIND_NEXT_MAP;
            nextActionAt = now;
            return;
        }

        // Return uninserted map safely to inventory so it never drops on ground, then
        // retry (or skip after repeated failures) instead of stopping the sort.
        returnCursorItem();
        retryOrSkip("insertion");
    }

    // Destination selection is PROJECT-AWARE: a map may only ever land in a bundle that is
    // empty or already holds maps of the SAME project. Without this, sun_25x30 maps end up
    // inside the moon_11x10 bundle whenever that one happens to have room first.
    private int findInventoryDestinationSlot() {
        int emptyBundle = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!(slot.container instanceof Inventory)) continue;
            ItemStack stack = slot.getItem();
            if (!(stack.getItem() instanceof BundleItem)) continue;
            if (bundleSize(stack) >= 64) continue;

            String project = bundleProject(stack);
            if (project == null) {
                // Empty bundle: a valid fresh destination, but an existing same-project
                // bundle always wins so a project never spreads across bundles.
                if (emptyBundle < 0) emptyBundle = i;
            } else if (detectedProject == null || project.equals(detectedProject)) {
                return i;
            }
            // Else: a DIFFERENT project's bundle or a mixed bundle - never a destination.
        }
        return emptyBundle;
    }

    // The project a bundle belongs to, from its maps' name prefixes: null when empty,
    // MIXED_BUNDLE when it holds maps of several projects (or non-map items) - neither
    // may ever receive a foreign map.
    private static final String MIXED_BUNDLE = "\0mixed";

    private String bundleProject(ItemStack bundleStack) {
        String project = null;
        for (ItemStack item : bundleStack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopyStream().toList()) {
            if (!item.is(Items.FILLED_MAP)) return MIXED_BUNDLE;
            Matcher m = COORDINATE.matcher(net.minecraft.ChatFormatting.stripFormatting(item.getHoverName().getString()).trim());
            if (!m.matches()) return MIXED_BUNDLE;
            String p = m.group(1).trim().toLowerCase(Locale.ROOT);
            if (project == null) {
                project = p;
            } else if (!project.equals(p)) {
                return MIXED_BUNDLE;
            }
        }
        return project;
    }

    // Re-checked right before the insert click: the slot may have changed hands (another
    // module, a full bundle) since it was chosen.
    private boolean destinationStillValid(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size()) return false;
        Slot slot = menu.getSlot(slotIndex);
        if (!(slot.container instanceof Inventory)) return false;
        ItemStack stack = slot.getItem();
        if (!(stack.getItem() instanceof BundleItem) || bundleSize(stack) >= 64) return false;
        String project = bundleProject(stack);
        return project == null || detectedProject == null || project.equals(detectedProject);
    }

    private SourceMatch findMap(TargetCoord target) {
        // Priority 1: Search chest / container slots
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container instanceof Inventory) continue; // Skip player inventory for source

            ItemStack stack = slot.getItem();
            if (stack.getItem() instanceof BundleItem) {
                BundleContents contents = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                int itemIdx = 0;
                for (ItemStack item : contents.itemCopyStream().toList()) {
                    if (matchesTarget(item, target)) {
                        return new SourceMatch(i, false, itemIdx);
                    }
                    itemIdx++;
                }
            } else if (stack.is(Items.FILLED_MAP)) {
                if (matchesTarget(stack, target)) {
                    return new SourceMatch(i, true, -1);
                }
            }
        }

        // Priority 2: Also check player inventory bundles if not found in chest
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!(slot.container instanceof Inventory)) continue;
            if (i == currentDestinationSlot) continue; // Don't pull from the current destination bundle

            ItemStack stack = slot.getItem();
            if (stack.is(Items.BUNDLE)) {
                BundleContents contents = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                int itemIdx = 0;
                for (ItemStack item : contents.itemCopyStream().toList()) {
                    if (matchesTarget(item, target)) {
                        return new SourceMatch(i, false, itemIdx);
                    }
                    itemIdx++;
                }
            }
        }

        return null;
    }

    private boolean matchesTarget(ItemStack stack, TargetCoord target) {
        if (!stack.is(Items.FILLED_MAP)) return false;
        Matcher m = COORDINATE.matcher(net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim());
        if (!m.matches()) return false;

        boolean isXY = coordinateFormat.get() == CoordinateFormat.X_Y;
        int first = Integer.parseInt(m.group(2));
        int second = Integer.parseInt(m.group(3));
        int col = isXY ? first : second;
        int row = isXY ? second : first;

        if (col != target.col() || row != target.row()) return false;

        if (detectedProject != null) {
            String proj = m.group(1).trim().toLowerCase(Locale.ROOT);
            return proj.equals(detectedProject);
        }
        return true;
    }

    private String formatCoord(TargetCoord c) {
        return coordinateFormat.get() == CoordinateFormat.X_Y ? (c.col() + "_" + c.row()) : (c.row() + "_" + c.col());
    }

    private int bundleSize(ItemStack stack) {
        return stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).size();
    }

    private void click(int slot, int button) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, mc.player);
    }

    private boolean contextIsOpen() {
        return activeScreen != null
            && mc.gui.screen() == activeScreen
            && mc.player != null
            && mc.player.containerMenu == menu;
    }

    private void returnCursorItem() {
        if (menu == null || mc.player == null) return;
        if (menu.getCarried().isEmpty()) return;

        // 1. Try to find an empty slot in player inventory first
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container instanceof Inventory && s.getItem().isEmpty()) {
                click(i, 0);
                return;
            }
        }

        // 2. Try original source slot if empty
        if (currentSourceSlot >= 0 && currentSourceSlot < menu.slots.size()) {
            Slot s = menu.getSlot(currentSourceSlot);
            if (s.getItem().isEmpty()) {
                click(currentSourceSlot, 0);
                return;
            }
        }

        // 3. Try any empty slot in the container
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.getItem().isEmpty()) {
                click(i, 0);
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
        state = State.WAIT_FOR_CONTAINER;
        notifyUser("§c" + message);
    }

    private void notifyUser(String message) {
        if (!localNotifications.get()) return;
        ChatUtils.sendMsg("Map Chest Sorter", Component.literal(message));
        mc.gui.toastManager().addToast(new SystemToast(
            SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal("Map Chest Sorter"),
            Component.literal(message.replaceAll("§[0-9a-fk-or]", ""))
        ));
    }

    private void resetFull() {
        state = State.WAIT_FOR_CONTAINER;
        activeScreen = null;
        menu = null;
        targetQueue.clear();
        currentProgressIndex = 0;
        detectedProject = null;
        doneCoords.clear();
        coordFailures.clear();
        cursorRetries = 0;
        currentSourceSlot = -1;
        currentDestinationSlot = -1;
        destinationCountBefore = 0;
        isSourceLooseMap = false;
        currentTarget = null;
    }

    public enum GridDirection {
        Column_First,
        Row_First
    }

    public enum CoordinateFormat {
        X_Y,
        Y_X
    }

    public enum OutputOrder {
        LastToFirst,
        FirstToLast
    }

    private enum State {
        WAIT_FOR_CONTAINER,
        WAIT_FOR_EMPTY_BUNDLE,
        PREPARE_QUEUE,
        FIND_NEXT_MAP,
        SELECT,
        EXTRACT,
        VERIFY_EXTRACT,
        INSERT,
        VERIFY_INSERT,
        DONE
    }

    private record TargetCoord(int col, int row) { }

    private record SourceMatch(int slot, boolean isLoose, int bundleItemIndex) { }
}
