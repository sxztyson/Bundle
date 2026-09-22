package dev.mapbundlecollector.mapart;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public record MapTile(
    int row,
    int col,
    String originalName,
    MapId mapId,
    ItemStack stack,
    MapItemSavedData mapState,
    byte[] cachedColors
) {
    public MapTile(int row, int col, String originalName, MapId mapId, ItemStack stack, MapItemSavedData mapState) {
        this(row, col, originalName, mapId, stack, mapState,
             (mapState != null && mapState.colors != null && mapState.colors.length >= 16384) ? mapState.colors.clone() : null);
    }

    public boolean hasData() {
        return (cachedColors != null && cachedColors.length >= 16384) ||
               (mapState != null && mapState.colors != null && mapState.colors.length >= 16384);
    }

    public byte[] getColors() {
        if (cachedColors != null && cachedColors.length >= 16384) {
            return cachedColors;
        }
        if (mapState != null && mapState.colors != null) {
            return mapState.colors;
        }
        return null;
    }
}
