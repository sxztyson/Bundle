package dev.mapbundlecollector.mapart;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.saveddata.maps.MapId;

// Collects every filled map reachable from the player inventory (loose or inside bundles) and
// computes a cheap structural signature so callers can skip reparsing unchanged inventories.
public final class InventoryScanner {
    public record ScannedMap(ItemStack stack, String rawName, Integer mapId) {}

    // Signature hashes per-slot item name + mapId (+ bundle contents), never color payloads.
    public record ScanResult(List<ScannedMap> maps, int signature) {}

    private InventoryScanner() {}

    public static ScanResult scan(LocalPlayer player) {
        List<ScannedMap> maps = new ArrayList<>();
        int signature = 1;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.has(DataComponents.BUNDLE_CONTENTS)) {
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle == null) continue;
                signature = 31 * signature + stack.getHoverName().getString().hashCode();
                signature = 31 * signature + bundle.size();
                for (ItemStack bundledItem : bundle.itemCopyStream().toList()) {
                    if (!bundledItem.is(Items.FILLED_MAP)) continue;
                    signature = 31 * signature + scanOne(bundledItem, maps);
                }
            } else if (stack.is(Items.FILLED_MAP)) {
                signature = 31 * signature + scanOne(stack, maps);
            }
        }

        return new ScanResult(maps, signature);
    }

    // Parses one filled map stack into the result list; returns a hash of name + mapId.
    private static int scanOne(ItemStack stack, List<ScannedMap> maps) {
        String rawName = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
        MapId mapId = stack.get(DataComponents.MAP_ID);
        Integer id = mapId == null ? null : mapId.id();
        maps.add(new ScannedMap(stack, rawName, id));
        return 31 * rawName.hashCode() + (id == null ? 0 : id.hashCode());
    }
}
