# Map Data Fetcher — verified integration notes (MC 26.2)

Everything below was verified against the mapped jar
`<user-home>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`
(**[MC]**, `javap -c -p` bytecode disassembly) and the Meteor 26.2 sources jar
`<user-home>\.gradle\caches\modules-2\files-2.1\meteordevelopment\meteor-client\26.2-SNAPSHOT\78fd6e89694eda4e009dc48aa2c0481def809dd7\meteor-client-26.2-SNAPSHOT-sources.jar`
(**[METEOR-SRC]**), plus the repo's own proven modules (**[REPO]**, exact `file:line` quotes).
JDK used: `<user-home>\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin`.
No decompiler was available in the caches — all semantics are read from bytecode; where a
conclusion depends on bytecode operand order it is called out explicitly.

Terminology: "carried" = cursor stack (`AbstractContainerMenu.getCarried()`); "PICKUP/0" =
`ContainerInput.PICKUP` with button 0 (left click), "PICKUP/1" = right click.

---

## 1. Existing module patterns (all three proven on the user's server)

### 1.1 The one and only click call in 26.2

`MapChestSorter.java:540-542` (identical in `MapBundleCollector.java:479-481` and
`MapDuplicateExtractor.java:363-365`):

```java
private void click(int slot, int button) {
    mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, mc.player);
}
```

Verified signature [MC] `net.minecraft.client.multiplayer.MultiPlayerGameMode`:

```java
public void handleContainerInput(int containerId, int slotIndex, int button, ContainerInput input, Player player)
public void handleInventoryButtonClick(int containerId, int button)   // numbered-button menus only
```

`handleContainerInput` bytecode (verified) does, in order:
1. Drops the click (warn log) if `containerId != player.containerMenu.containerId`.
2. Snapshots every slot, runs `menu.clicked(slot, button, input, player)` **locally first**
   (client-side prediction — the same `BundleItem` logic runs on both sides), diffs the
   snapshots, and sends
   `new ServerboundContainerClickPacket(containerId, menu.getStateId(), slot, button,
   input, changedSlotsHashes, carriedHash)` (packet ctor `(int,int,short,byte,ContainerInput,
   Int2ObjectMap<HashedStack>, HashedStack)` verified).
3. Because the packet carries `menu.getStateId()`, any click that raced a pending server sync
   is detected server-side and answered with a **full** content resync (see §3.3) — the
   client's view is force-corrected. This is the self-healing loop the modules rely on.

`ContainerInput` [MC] is the 26.2 replacement for the old `ClickType` enum
(`net.minecraft.world.inventory.ClickType` does **not** exist anymore — the surviving
`ClickAction` (`PRIMARY`/`SECONDARY`, `net.minecraft.world.inventory.ClickAction`) is the
button-level enum used inside menu logic). Constants and wire ids (decoded from `<clinit>`):
`PICKUP=0, QUICK_MOVE=1, SWAP=2, CLONE=3, THROW=4, QUICK_CRAFT=5, PICKUP_ALL=6`.

**Fetcher: reuse `click()` verbatim.** Everything the fetcher needs is PICKUP with button
0 or 1.

### 1.2 Menu acquisition and "is my container still open"

`MapChestSorter.java:544-549`:

```java
private boolean contextIsOpen() {
    return activeScreen != null
        && mc.gui.screen() == activeScreen
        && mc.player != null
        && mc.player.containerMenu == menu;
}
```

`MapBundleCollector.java:184-189` adds a 4th check (`activeScreen.getMenu() == activeHandler`).
Menu is captured once at screen open (`MapChestSorter.java:182-184`):

```java
activeScreen = screen;
menu = screen.getMenu();
```

Verified [MC]: `AbstractContainerScreen<T extends AbstractContainerMenu> implements
MenuAccess<T>` → `getMenu()`; `Minecraft.player`, `Minecraft.gameMode` public fields;
`Minecraft.gui` public field; `Gui.screen()` accessor; `Player.containerMenu` (public,
non-final) and `Player.inventoryMenu` (public final) fields. All also [REPO]-proven.

**Fetcher: reuse `contextIsOpen()` verbatim** (the 4-check variant is strictly better) and
re-read state from `menu` every tick; do not cache ItemStacks across ticks.

### 1.3 Cursor recovery ("never drop")

`MapChestSorter.java:551-581`:

```java
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
        if (s.getItem().isEmpty()) { click(currentSourceSlot, 0); return; }
    }
    // 3. Try any empty slot in the container
    for (int i = 0; i < menu.slots.size(); i++) {
        Slot s = menu.getSlot(i);
        if (s.getItem().isEmpty()) { click(i, 0); return; }
    }
}
```

(`MapBundleCollector.java:483-513` is the same ladder with the bundle slot tried first;
`MapDuplicateExtractor.java:374-395` drops step 3.)

**Fetcher: reuse unchanged.** Note it fires ONE click and returns — callers re-check next
tick (`MapChestSorter.java:376-385` loops: `returnCursorItem(); if (!menu.getCarried().isEmpty())
fail(...)`), which is the right "click is a request" discipline (spec rule 2/3).

Safety net if the screen closes while something is carried [MC]
`AbstractContainerMenu.removed(Player)`: server-side it calls
`dropOrPlaceInInventory(player, getCarried())` then clears the carried stack — so a close
mid-cursor cannot lose the item outright, but it CAN drop it if the inventory is full. The
module must still always resolve the cursor before transitions (spec rule 4).

### 1.4 Delay / verify / retry cadence

- `nextActionAt = System.currentTimeMillis() + actionDelay` gate at the top of the tick
  handler (`MapChestSorter.java:160-161`), default delays 100–150 ms.
- Verify phases poll at **40 ms** with an absolute deadline
  `verifyDeadline = now + Math.max(1500, actionDelay.get() * 4)`
  (`MapChestSorter.java:396, 409-410`).
- Open delay: `MapBundleCollector.java:172-173` waits `openDelay` (200 ms) after screen
  open before scanning (lets the initial full sync land).

**Fetcher: reuse the exact pacing scheme** (click-delay-ms setting maps to `actionDelay`;
the spec's 75 ms default is within the proven 20–150 ms range).

### 1.5 What to reuse vs avoid

| Pattern | Verdict for the fetcher |
|---|---|
| `click(slot, button)` → `handleContainerInput(..., PICKUP, ...)` | REUSE verbatim |
| `contextIsOpen()` screen+menu identity check each tick | REUSE (4-check variant) |
| `returnCursorItem()` ladder | REUSE verbatim |
| 40 ms verify polling + `max(1500, delay*4)` deadline | REUSE |
| select-then-extract (`SelectBundleItemPacket` + `click(src,1)`) | REUSE (see §2.4) — this is the *proven* per-map path |
| identifying the extracted map by reading `menu.getCarried()` after the click (`MapChestSorter.java:400-416`, `MapDuplicateExtractor.java:308-324`) | REUSE — mandatory, never assume order |
| `onActivate` → check current screen, else prompt (`MapChestSorter.java:113-122`) | REUSE |
| `MapBundleCollector`'s carried-bundle insert loop (loose-map case, lines 353-371) | AVOID for the fetcher — it inserts INTO a carried bundle (opposite direction), keep it as a reference only |
| `MapDuplicateExtractor`'s `reset()` on any screen change (line 96-103) vs Sorter's WAIT_FOR_CONTAINER resume | For the fetcher use **reset-to-waiting** (each chest is an independent unit; no cross-chest queue like the sorter) |

---

## 2. Bundle click semantics in 26.2 (bytecode-verified)

### 2.1 Where bundle behavior lives

`ServerGamePacketListenerImpl.handleContainerClick(ServerboundContainerClickPacket)` [MC]
bytecode, in order:
1. `containerId` must match, else ignore; spectator/dead → `sendAllDataToRemote()` (full
   resync) and ignore.
2. `menu.stillValid(player)` else log-and-ignore (chest broken/distance).
3. `menu.isValidSlotIndex(slotNum)` else log-and-ignore.
4. `flag = packet.stateId() != menu.getStateId()`.
5. `menu.suppressRemoteUpdates(); menu.clicked(slot, button, input, player);`
   then adopts the client-claimed hashes, `resumeRemoteUpdates()`.
6. `flag ? menu.broadcastFullState() : menu.broadcastChanges()`.

`AbstractContainerMenu.clicked` just wraps `doClick` in a crash report. `doClick` for
`PICKUP` (and `QUICK_MOVE`) with button 0/1 maps button → `ClickAction.PRIMARY/SECONDARY`,
then at the top of the PICKUP branch calls (verified bytecode order):

```java
// tryItemClickBehaviourOverride(player, action, slot, itemInSlot = slot.getItem(), carried = getCarried())
if (carried.overrideStackedOnOther(slot, action, player)) return;          // CARRIED stack's item reacts
if (itemInSlot.overrideOtherStackedOnMe(carried, slot, action, player, carriedSlotAccess)) return; // SLOT stack's item reacts
// ... vanilla pickup/insert/swap logic otherwise
```

Both are `ItemStack` methods forwarding to `Item`. `net.minecraft.world.item.BundleItem`
overrides both — that is the entirety of bundle-in-inventory click behavior. This runs on
client (prediction) and server (authoritative) alike.

### 2.2 (a) EXTRACT-ONE — `ContainerInput.PICKUP`, button **1** (right click), empty cursor, on the slot holding the bundle

`BundleItem.overrideOtherStackedOnMe(bundleStack, carried, slot, action, player, carriedAccess)`:

- `SECONDARY` **and** carried **empty** and `slot.allowModification(player)`
  (= `slot.mayPickup(player) && slot.mayPlace(slot.getItem())`, verified in `Slot`):
  ```java
  ItemStack removed = new BundleContents.Mutable(contents).removeOne();
  if (removed != null) { playRemoveOneSound; carriedAccess.set(removed); }  // -> cursor
  bundleStack.set(BUNDLE_CONTENTS, mutable.toImmutable());
  broadcastChangesOnContainerMenu(player);   // -> menu.slotsChanged(player.getInventory())
  return true;
  ```
  **The extracted stack lands ON THE CURSOR.** This is what the repo's
  `click(currentSourceSlot, 1)` does (`MapChestSorter.java:392`,
  `MapBundleCollector.java:385`, `MapDuplicateExtractor.java:302`).
- `PRIMARY` and carried **empty**: `toggleSelectedItem(bundleStack, -1)` (clears selection)
  then returns **false** → vanilla PICKUP runs → the WHOLE bundle is picked up onto the
  cursor. I.e. left-click a bundle with empty cursor = pick the bundle up (and clear its
  selection as a side effect).
- `PRIMARY` and carried non-empty → insert, see §2.3.
- `SECONDARY` and carried non-empty: clears selection, returns false → vanilla logic
  swaps carried ↔ bundle (both stacks trade places).

`BundleContents.Mutable.removeOne()` (verified bytecode):
- `idx = selectedItem` if `0 <= selectedItem < items.size()` **else index 0** (no selection →
  first entry).
- Removes the entry at `idx` and returns a **copy of the whole entry stack** (all copies of
  that entry, not just one item; weight is decremented by `perUnit * count`, operand order
  in the bytecode is `getFraction(count, 1)`). For filled maps this is normally 1 map per
  entry because maps with different `MAP_ID`/name components never merge
  (`findStackIndex` merges only on `ItemStack.isSameItemSameComponents`).
- Then `toggleSelectedItem(-1)` → selection is DESELECTED after every extraction.

### 2.3 (b) INSERT-CARRIED — `ContainerInput.PICKUP`, button **0** (left click), on the slot holding the bundle, with the map on the cursor

`BundleItem.overrideOtherStackedOnMe` `PRIMARY` + carried non-empty + `allowModification`:

```java
int inserted = new BundleContents.Mutable(contents).tryInsert(carried);
playInsertSound / playInsertFailSound;
bundleStack.set(BUNDLE_CONTENTS, mutable.toImmutable());
return true;   // carried was (partially or fully) consumed into the bundle
```

`Mutable.tryInsert(stack)` [MC]:
- Rejects `!canItemBeInBundle(stack)` (empty or `canFitInsideContainerItems()==false`).
- `amount = min(stack.count, getMaxAmountToAdd(unitWeight))` where
  `getMaxAmountToAdd = floor((Fraction.ONE - currentWeight) / unitWeight)` and per-unit
  weight is `1/maxStackSize` (`BundleContents.getWeight`, verified) → **each filled map
  costs 1/64, i.e. a 64-map bundle is exactly full**.
- If an existing entry matches (`isSameItemSameComponents`) → merge counts; else **insert
  the new entry at index 0** (`items.add(0, stack.split(amount))`) — **bundle order is
  LIFO: the most recently inserted item is index 0.**
- Returns the amount inserted (0 = rejected/full).

This is the repo's `click(currentDestinationSlot, 0)` insert (`MapChestSorter.java:419`,
`MapDuplicateExtractor.java:327`, `MapBundleCollector.java:445`).

The mirror method `BundleItem.overrideStackedOnOther(bundleStack, slot, action, player)`
applies when the **bundle itself is the carried stack**:
- `PRIMARY` on a slot with items: `tryTransfer(slot, player)` — **sucks the slot's stack
  into the carried bundle**. (Do not left-click a carried bundle onto anything but an
  empty slot.)
- `SECONDARY` on an **empty** slot: `removeOne()` → `slot.safeInsert(removed)` → the
  selected (or index-0) entry is ejected **directly into that slot** — the cursor never
  holds the map. Leftovers are re-inserted into the bundle. `safeInsert` checks
  `mayPlace` (verified). On a NON-empty slot SECONDARY falls through (returns false) →
  vanilla swap. See §7 for how the fetcher exploits this.

### 2.4 Selecting WHICH item gets extracted

Selection is a component value (`BundleContents.selectedItem`, `NO_SELECTED_ITEM_INDEX = -1`)
on the bundle stack; `removeOne()` extracts that index (fallback 0 when deselected).

Verified selection API [MC]:

```java
// net.minecraft.world.item.BundleItem
public static void toggleSelectedItem(ItemStack bundle, int index)
public static int  getSelectedItemIndex(ItemStack bundle)   // -1 when none
public static ItemStackTemplate getSelectedItem(ItemStack bundle)
// net.minecraft.world.item.component.BundleContents
public int getSelectedItemIndex();  public ItemStackTemplate getSelectedItem();
public int size();  public Stream<ItemStack> itemCopyStream();  public List<ItemStackTemplate> items();
public boolean isEmpty();  public DataResult<Fraction> weight();
public static final int NO_SELECTED_ITEM_INDEX = -1;
// packet
public record ServerboundSelectBundleItemPacket(int slotId, int selectedItemIndex)
```

Server handler [MC] `ServerGamePacketListenerImpl.handleBundleItemSelectedPacket` →
`player.containerMenu.setSelectedBundleItemIndex(slotId, index)` which (after a
`0 <= slotId < slots.size()` guard) calls `BundleItem.toggleSelectedItem(slot.getItem(),
index)`. **It is a toggle**: `Mutable.toggleSelectedItem(int)` sets `selectedItem = -1`
(deselect) when `index == currentSelected` **or** index is out of bounds, else selects
`index`. Two consequences:

1. **Never send a select for the index that is already selected** — it would deselect and
   the next extraction would pull index 0 instead. The repo guards this
   (`MapChestSorter.java:357-364`):
   ```java
   ItemStack sourceStack = menu.getSlot(currentSourceSlot).getItem();
   int selected = BundleItem.getSelectedItemIndex(sourceStack);
   if (selected != match.bundleItemIndex()) {
       BundleItem.toggleSelectedItem(sourceStack, match.bundleItemIndex());
       if (mc.getConnection() != null) {
           mc.getConnection().send(new ServerboundSelectBundleItemPacket(currentSourceSlot, match.bundleItemIndex()));
       }
   }
   ```
   (client-side component toggle + packet, exactly what vanilla's own
   `net.minecraft.client.gui.BundleMouseActions.toggleSelectedBundleItem` does — verified
   [MC]: `BundleItem.toggleSelectedItem(stack, idx)` then
   `connection.send(new ServerboundSelectBundleItemPacket(slotIndex, idx))`.)
2. Selection is NOT capped at the tooltip grid on the server: vanilla UI scroll selection
   is limited to `getNumberOfItemsToShow()` (≤ 12: `MAX_SHOWN_GRID_ITEMS = 12`, overflowing
   11 — constants verified), but `setSelectedBundleItemIndex` only bounds-checks against
   `items.size()`. The repo already selects arbitrary indices in 64-map bundles and it
   works. **Any entry index 0..size-1 is selectable via the packet.**

Also note the client UI auto-clears selection on hover-off and on QUICK_MOVE/SWAP clicks
(`BundleMouseActions.onStopHovering`/`onSlotClicked`, verified) by sending `index = -1`;
and `removeOne()` itself deselects after each extraction, and picking a bundle up
(left-click, §2.2) clears selection too. So: **assume selection does not survive between
operations; set it explicitly before every extraction** (or don't set it at all when you
mean index 0 — unselected defaults to 0).

### 2.5 Is there a bundle screen? Held vs inventory behavior?

- **No bundle screen exists in 26.2.** Jar listing contains only
  `net/minecraft/client/gui/screens/inventory/tooltip/ClientBundleTooltip.class` and
  `net/minecraft/world/inventory/tooltip/BundleTooltip.class` — the bundle UI is a TOOLTIP
  (grid + scroll selection) rendered inside any `AbstractContainerScreen`; there is no
  `BundleScreen` and no separate containerId for bundles. All bundle interaction happens
  through normal container clicks in whatever menu is open.
- Bundle held in hand (not in a menu): `BundleItem.use` starts "using" the item;
  `onUseTick` → `dropContent` throws items out one at a time (the
  `TICKS_AFTER_FIRST_THROW=10 / TICKS_BETWEEN_THROWS=2 / TICKS_MAX_THROW_DURATION=200`
  constants). That path DROPS ITEMS INTO THE WORLD — irrelevant and forbidden for the
  fetcher (spec rule 4: no drops, menu clicks only).
- Clicking behaves identically whether the bundle sits in the chest part or the
  inventory part of the open menu — `doClick` → override chain has no such distinction;
  the only gates are `stillValid`, `isValidSlotIndex`, `allowModification` (which is
  `mayPickup && mayPlace(getItem())`, trivially true for an open chest/shulker you own).
- A carried bundle right-clicked on an EMPTY slot ejects directly to that slot (§2.3) —
  this works in any menu, including the inventory screen (`InventoryScreen`) the duplicate
  extractor drives.

---

## 3. Server-confirmed state access (client side)

### 3.1 Accessors (all verified [MC], all used by [REPO] already)

```java
AbstractContainerMenu menu = mc.player.containerMenu;      // open menu (chest/shulker/inventory)
InventoryMenu invMenu     = mc.player.inventoryMenu;       // containerId == 0, always present
menu.containerId                                          // public final int
menu.slots                                                // public final NonNullList<Slot>
menu.getSlot(i).getItem()                                 // synced ItemStack (components included)
menu.getCarried()                                         // cursor stack (synced, see below)
menu.setCarried(ItemStack)                                // only the sync path uses this
menu.getStateId()
Slot.container   // public final Container — `instanceof Inventory` identifies player-inv slots [REPO]
```

Because `BUNDLE_CONTENTS` is a data component of the ItemStack, the synced slot stack gives
you the full bundle contents, order, and selected index
(`stack.get(DataComponents.BUNDLE_CONTENTS)` / `getOrDefault(..., BundleContents.EMPTY)`) —
the repo parses bundled map names straight from synced slots everywhere.

### 3.2 Which packets update this state (verified client handlers)

| Packet | Client handler | Effect |
|---|---|---|
| `ClientboundContainerSetSlotPacket` `(containerId, stateId, slot, itemStack)` | `ClientPacketListener.handleContainerSetSlot` | `containerId==0` → `inventoryMenu.setItem(slot, stateId, item)`; else if matches `containerMenu.containerId` → `containerMenu.setItem(...)`. Single-slot authoritative update. |
| `ClientboundContainerSetContentPacket` `(containerId, stateId, items, carriedItem)` | `handleContainerContent` | `initializeContents(stateId, items, carriedItem)` on the matching menu (full resync: all slots + carried). |
| `ClientboundSetCursorItemPacket` (`contents()`) | `handleSetCursorItem` | `containerMenu.setCarried(contents)` — the cursor has its OWN packet in 26.2. |

Server send side (verified): `AbstractContainerMenu.broadcastChanges()` per changed slot →
`synchronizeSlotToRemote` (SetSlot packet) and `synchronizeCarriedToRemote` → synchronizer
`sendCarriedChange` (SetCursorItem packet); `broadcastFullState()`/`sendAllDataToRemote()`
→ SetContent packet. `ServerPlayer.tick()` calls `containerMenu.broadcastChanges()` every
game tick and closes the menu if `!stillValid`.

### 3.3 How clicks get confirmed, and the state-id self-heal

`handleContainerClick` server-side applies the click then either `broadcastChanges()`
(incremental) or, **if the click's stateId did not match the server's current one,
`broadcastFullState()`** — a full SetContent resync. Practical consequence for the
fetcher: after every click, wait until the server-synced view reflects the expected
change (slot contents and/or carried). Typical latency = 1 RTT + up to 50 ms (one server
tick), matching the repo's 40 ms poll / 1.5 s deadline scheme.

### 3.4 Detecting "container changed by server" — Meteor events vs re-reading

Meteor 26.2 has **exactly two** container-sync events ([METEOR-SRC]
`meteordevelopment/meteorclient/mixin/ClientPacketListenerMixin.java:99-107`):

```java
@Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
private void onHandleContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
    MeteorClient.EVENT_BUS.post(ContainerSlotUpdateEvent.get(packet));
}
@Inject(method = "handleContainerContent", at = @At("TAIL"))
private void onHandleContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
    MeteorClient.EVENT_BUS.post(InventoryEvent.get(packet));
}
```

`ContainerSlotUpdateEvent` / `InventoryEvent` are singleton events with a public
`packet` field, posted AFTER vanilla applied the change (so reading `mc.player.containerMenu`
inside the handler already sees the new state). There is **no event for
`ClientboundSetCursorItemPacket`** (cursor sync) and no other container event
(full list of `events/` checked: game/ChangePerspective|GameJoined|GameLeft|ItemStackTooltip|**OpenScreen**|ReceiveMessage|ResolutionChanged|ResourcePacksReloaded|SendMessage,
packets/ContainerSlotUpdate|**Inventory**|Packet|PlaySound, world/...|**Tick**).

**Recommendation: do NOT subscribe to sync events — re-read `menu` state each tick before
acting** (the repo modules' model). Reasons: (1) the cursor has no event, so you need
polling anyway; (2) the fetcher acts at ≥75 ms cadence, so per-tick reads are strictly
fresher than the last event; (3) per-tick re-reads cannot miss a full-resync correction.
`ContainerSlotUpdateEvent` is still worth ONE optional use: as a cheap "container is alive"
signal for the stall detector (spec rule 3 counts "container syncs") — the fetcher can
subscribe to `ContainerSlotUpdateEvent` + `InventoryEvent` + `PacketEvent.Receive`
(map packets) purely to timestamp "last sync", while all decisions stay tick-driven
re-reads.

---

## 4. Detecting close + auto-start hook

- **Close detection (robust, proven):** the repo's `contextIsOpen()` (§1.2) —
  `mc.gui.screen() == activeScreen && mc.player.containerMenu == menu`. Screen changes
  arrive via `OpenScreenEvent` (`GuiMixin` posts it from `Gui.setScreen`, cancellable,
  `event.screen == null` on close — verified [METEOR-SRC]
  `mixin/GuiMixin.java:42-45`). All three modules handle close in `onOpenScreen` by
  returning the cursor and resetting (`MapChestSorter.java:130-145`,
  `MapBundleCollector.java:127-139`).
- **The "no container open" idiom** `mc.player.containerMenu == mc.player.inventoryMenu`
  is valid in 26.2 (fields verified, `inventoryMenu` is the containerId-0 menu the player
  falls back to — `ServerPlayer.tick` resets `containerMenu = inventoryMenu` on invalid,
  verified bytecode).
- **Auto-start hook:** `OpenScreenEvent` is the right and proven hook — better than
  tick-polling `mc.gui.screen() instanceof AbstractContainerScreen`, because it also
  catches the close event and fires once instead of per tick. Use the repo's
  `beginIfSupported` shape (`MapBundleCollector.java:166-182`): filter to
  `ContainerScreen | ShulkerBoxScreen | DispenserScreen | HopperScreen`, capture
  `getMenu()`, set an open delay (200 ms) before the first scan so the initial
  `ClientboundContainerSetContentPacket` has landed.
- Belt-and-braces: keep the per-tick `contextIsOpen()` re-check even with the event, as
  the repo does (`MapChestSorter.java:152-158`) — it catches every failure mode
  (server-forced close, dimension change) without needing the event to fire.

---

## 5. MapItemSavedData send timing (when does a loose map's data arrive?)

Full chain, bytecode-verified [MC]:

1. **Registration:** `Inventory.tick()` (called from `Player.tick()`, offset verified)
   iterates the **main `items` list only** (36 slots: 27 + hotbar; 26.2 keeps equipment
   in a separate `EntityEquipment`) and calls
   `stack.inventoryTick(level, player, i == selected ? MAINHAND : null)` for each
   non-empty stack. `MapItem.inventoryTick(stack, ServerLevel, entity, slot)` resolves
   `MapItemSavedData` from the `MAP_ID` component and — for ANY slot, held or not — calls
   `savedData.tickCarriedBy(player, stack, null)`.
2. `tickCarriedBy` creates a `HoldingPlayer` for the player on first sight and keeps it
   only while `player.getInventory().contains(mapMatcher(stack))` — membership is
   Inventory-based, so a map that leaves the inventory stops being tracked.
3. **Send:** `ServerPlayer.doTick()` (every game tick) — after `Player.tick()` (i.e. after
   registration) — runs
   ```java
   for (int i = 0; i < getInventory().getContainerSize(); i++) {   // ALL compartments
       ItemStack s = getInventory().getItem(i);
       if (!s.isEmpty()) synchronizeSpecialItemUpdates(s);         // mapId -> getUpdatePacket -> connection.send
   }
   ```
   `synchronizeSpecialItemUpdates(stack)`: `mapId = stack.get(MAP_ID)`;
   `data = MapItem.getSavedData(mapId, level)`; if data != null →
   `packet = data.getUpdatePacket(mapId, this)`; if packet != null → send.
4. `getUpdatePacket` → `HoldingPlayer.nextUpdatePacket(mapId)`: the HoldingPlayer is
   constructed with `dirtyData = true` and dirty rect `(0,0)-(127,127)` — **the first poll
   after the map enters the inventory produces ONE `ClientboundMapItemDataPacket`
   containing the FULL 128×128 color patch** (plus decorations on a 5-tick cadence —
   `tick % 5`). Later packets are only dirty-rect patches.

**Timing conclusion:** the same server tick that the map lands in an inventory slot can
already register it AND poll it, so the full-data packet typically arrives **1–2 game
ticks (50–100 ms) after the server-confirmed slot update, + network RTT**. There is no
"hold it in hand" requirement — registration is per-slot in the whole main inventory,
and the doTick poll loop is unconditional (works with a chest screen open).

**Cursor-carried maps do NOT tick (verified):** the carried stack lives in
`AbstractContainerMenu.carried`, not in `Inventory`; `Inventory.tick()` iterates only
`items`, and the doTick poll iterates `Inventory.getItem(i)`. Nothing references the
menu's carried stack. So **the fetcher must place maps into real inventory slots** —
which both recommended sequences in §7 do (the carried-bundle eject path never puts a map
on the cursor at all; the proven select/extract path places the cursor map with one extra
click).

Offhand/equipment caveat: `Inventory.tick()` iterates only the non-equipment `items`
list, so equipment slots are not registered by this path (inferred from the loop; the
equipment tick path was not traced further — irrelevant for the fetcher, which only uses
main inventory slots).

Two data-availability caveats (affect WAIT_DATA, not clicks):
- If the SERVER has no `MapItemSavedData` for the map id (`getSavedData(...) == null`),
  `inventoryTick` returns early — **no packet will ever come** for that map. WAIT_DATA
  would sit until the stall detector fires; on the user's server the maps were made
  there, so data should exist, but the abort report should name the missing map ids.
- The repo's capture path (`ClientPacketListenerMixin.handleMapItemData` TAIL →
  `MapArtManager.onMapDataReceived(packet.mapId().id(), state.colors)` —
  `src/main/java/dev/mapbundlecollector/mixin/ClientPacketListenerMixin.java:20-27`)
  snapshots the MERGED state after vanilla applied the packet, so one full patch is
  enough; `MapArtCache.isBlank` filters never-rendered maps.

---

## 6. Meteor keybind/module conventions

Nothing new needed — fully covered by repo patterns:
- `TickEvent.Post` ([METEOR-SRC] bare singleton class; `@EventHandler private void
  onTick(TickEvent.Post event)` proven in 4 repo modules).
- `OpenScreenEvent` (§4), `Module.onActivate/onDeactivate`, `settings.getDefaultGroup()`,
  `IntSetting.Builder .min().sliderMax()` / `.defaultValue()`, `BoolSetting`,
  `ChatUtils.sendMsg`, `SystemToast`, `mc.gui.toastManager().addToast` — all proven in
  the three modules and consistent with API_NOTES_26.2.md item 11.
- Settings for the fetcher map 1:1 onto proven builder usage:
  click-delay-ms → `.min(25).max(500).sliderRange(25,500).defaultValue(75)` (sliderRange
  needed since default sliderMax caps at 10 — see API notes), batch-size →
  `.min(1).max(36).sliderMax(36).defaultValue(24)`, stall-timeout-s →
  `.min(5).max(300).sliderRange(5,300).defaultValue(30)`, auto-start → BoolSetting.

---

## 7. RECOMMENDED CLICK SEQUENCE

Two protocols. **P1 (carried-bundle eject)** is the fast one and never puts a map on the
cursor; **P2 (select/extract/place)** is the already-proven repo protocol, usable as-is
and as the fallback. All clicks are `mc.gameMode.handleContainerInput(menu.containerId,
slot, button, ContainerInput.PICKUP, mc.player)`. Between every click: wait
click-delay-ms, then verify against the SERVER-SYNCED state (§3) before proceeding; any
mismatch after the verify deadline (max(1500, 4×delay)) → resolve cursor
(`returnCursorItem`) → ABORT with report. Precondition for every step: `contextIsOpen()`
(§1.2), `menu.getCarried()` is exactly what the step expects.

Notation: chest slot N = the bundle's menu slot (server-synced `BundleContents` parsed
via `MapNameParser`); free inventory slots = menu slots with
`slot.container instanceof Inventory && slot.getItem().isEmpty()`, collected from synced
state; M = wave size (≤ batch-size, ≤ free slots found, ≤ bundle size).

### P1 — carried-bundle eject (recommended; 1 click per map, no cursor exposure of maps)

1. **Assert:** cursor empty; chest slot N holds the expected bundle (same map-id set as
   when scanned); ≥ M free inventory slots.
2. **TAKE_BUNDLE:** `click(N, 0)` — PICKUP/0 on the bundle slot with empty cursor.
   Vanilla picks the WHOLE bundle onto the cursor and clears its selection (§2.2).
   *Server-confirm:* `menu.getCarried()` is the bundle && slot N empty. (Deselect also
   means eject order is simply index 0, 1, 2, … — see step 4.)
3. **EXTRACT_WAVE (loop i = 0..M-1):** `click(freeSlot_i, 1)` — PICKUP/1 on a
   server-confirmed EMPTY inventory slot. `BundleItem.overrideStackedOnOther` SECONDARY
   pops entry 0 (unselected fallback, §2.2/§2.4) directly into that slot via
   `safeInsert` (leftovers go back into the bundle — no drops).
   *Server-confirm:* `freeSlot_i` now non-empty map && carried still the bundle && bundle
   `size()` shrank by the entry count. **Identify the fetched map by reading
   `freeSlot_i.getItem().get(DataComponents.MAP_ID)` from the synced state** — do NOT
   assume order (§2.2: entries are whole stacks; identical-component duplicates can pull
   count>1 at once — count copies, they share one mapId anyway).
   NEVER right-click a non-empty slot while holding the bundle (§2.3 swap hazard) and
   never left-click a slot with items (suck-in hazard).
4. **WAIT_DATA:** exit when every wave mapId shows up in
   `MapArtManager.get().getCapturedColors(mapId)` (the capture mixin feeds it the moment
   the full packet lands — expected within ~1-2 ticks + RTT per map, §5), or on abort /
   stall (30 s with zero captures AND zero syncs). Persist per capture via
   `MapArtCache.putColors` (spec). Also verify each wave map is still loose while
   uncaptured (spec rule 3).
5. **RETURN_BUNDLE (two phases).** While the bundle is carried, a carried map cannot be
   inserted into it (insert requires the bundle to sit in a slot, §2.3), so first park
   the bundle back in its home slot, then re-insert the maps:
   5a. `click(N, 0)` — carried bundle onto the (empty) chest slot N = vanilla
       safeInsert path. *Server-confirm:* slot N holds the bundle again &&
       `menu.getCarried()` empty.
   5b. For each loose wave map slot s_i: `click(s_i, 0)` (pick the map onto the cursor,
       vanilla pickup), *server-confirm:* `menu.getCarried()` == that map && s_i empty;
       then `click(N, 0)` (PICKUP/0 on the bundle slot with the map on the cursor →
       `overrideOtherStackedOnMe` PRIMARY → `tryInsert`, §2.3), *server-confirm:*
       cursor empty && bundle `size()` +1. (This is exactly the proven
       insertTarget/verifyInsert pair, `MapChestSorter.java:418-450`; s_i order does not
       matter, but taking them in reverse ejection order restores the original bundle
       order most closely.)
6. **VERIFY_BUNDLE:** compare slot N's synced `BUNDLE_CONTENTS` map-id MULTISET against
   the pre-wave snapshot (order will differ — inserts are LIFO at index 0, §2.3; compare
   ids, not order). Mismatch → ABORT report (bundle left intact in the chest slot).
7. **Next bundle / DONE:** chat summary per spec.

P1 total clicks per wave: 1 (take) + M (eject) + 1 (bundle back) + 2M (reinsert) + scans
≈ 3M + 2 — versus P2's ~4M + 2. P1's per-map server confirmations are SetSlot on the
target inventory slot plus SetCursorItem only when the bundle changes hands.

### P2 — select/extract/place (repo-proven fallback)

Per map: (optional) select — only if current selected != desired index
(`MapChestSorter.java:356-367` pattern, §2.4; sending `ServerboundSelectBundleItemPacket(N, idx)`
while the bundle sits in chest slot N), then `click(N, 1)` (extract selected → cursor),
confirm carried is a map (identify by name/MAP_ID parse —
`MapDuplicateExtractor.java:308-324`), then `click(freeSlot_i, 0)` (place carried map
into the empty slot; vanilla safeInsert), confirm slot holds the map && cursor empty.
WAIT_DATA as above; reinsert with `click(s_i, 0)` + `click(N, 0)`; VERIFY_BUNDLE as above.
Use P2 if P1's eject clicks ever behave unexpectedly on the user's server (e.g. a plugin
interfering with carried-bundle clicks) — it is byte-for-byte what three existing modules
already do.

### Abort rules (both protocols)

At every step: context closed → resolve cursor if state is confirmed, else stop clicking,
report. Carried != expected → `returnCursorItem()` once, re-check next tick, then abort if
unresolved. Wave map confirmed gone-loose-but-uncaptured → abort per spec rule 3. All
aborts leave the bundle whole in chest slot N whenever state was confirmable (cursor
resolution → bundle reinsert attempt → verify); otherwise stop with report.

---

## UNVERIFIED / caveats

- **Runtime behavior is bytecode-derived, not run-observed.** Every signature and branch
  above is from the shipped 26.2 jar; no test server was driven. The [REPO]-marked items
  are compile- AND runtime-proven on the user's server.
- **Server plugins** could reject clicks or alter menus; the verify-after-every-click
  scheme makes such interference visible (deadline expiry), it cannot corrupt the bundle.
- `ContainerInput` wire ids 0..6 decoded from `<clinit>` (each constant constructed with
  its own ordinal); the `id()` accessor exists but the STREAM_CODEC byte order was not
  traced — irrelevant for mod code that passes the enum.
- Offhand/equipment maps not being registered is inferred from `Inventory.tick()`'s loop
  over `items` only (§5); the fetcher never uses those slots.
- `handleBundleItemSelectedPacket` does not itself trigger a sync — the changed component
  is picked up by the next per-tick `broadcastChanges()` (verified call order on
  ServerPlayer.tick). If a server mod ever suppressed that broadcast, the fetcher's
  "confirm selection" step would time out rather than misfire — safe failure mode.
- Whether `MapItem.getSavedData` returns data for every map id in the user's chests is a
  server-data property, not an API question (§5 caveat).
