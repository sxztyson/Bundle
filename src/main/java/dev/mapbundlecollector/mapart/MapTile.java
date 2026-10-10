package dev.mapbundlecollector.mapart;

import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;

public record MapTile(
    int row,
    int col,
    String originalName,
    MapIdComponent mapId,
    ItemStack stack,
    MapState mapState,
    byte[] cachedColors
) {
    public MapTile(int row, int col, String originalName, MapIdComponent mapId, ItemStack stack, MapState mapState) {
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
