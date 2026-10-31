package dev.mapbundlecollector.mapart;

import dev.mapbundlecollector.mapart.cache.MapArtCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

// One map-art tile. Live tiles reference the inventory stack and its MapItemSavedData directly;
// NO color array is cloned or retained (colors live in mapState or in the persistent cache).
// Tiles loaded from the persistent cache have stack == null and mapState == null.
public record MapTile(
    int row,
    int col,
    String originalName,
    Integer mapId,
    ItemStack stack,
    MapItemSavedData mapState
) {
    public boolean isLive() {
        return stack != null;
    }

    public boolean hasData() {
        return mapState != null && mapState.colors != null && mapState.colors.length >= MapArtCache.COLOR_BYTES;
    }

    // Returns the live color array (not a copy); caller must not mutate it. May be null.
    public byte[] getColors() {
        return mapState != null && mapState.colors != null ? mapState.colors : null;
    }
}
