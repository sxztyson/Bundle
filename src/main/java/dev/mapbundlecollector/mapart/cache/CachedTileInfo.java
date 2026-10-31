package dev.mapbundlecollector.mapart.cache;

// Immutable presence-index entry for one cached tile. Colors live on disk, never here.
public final class CachedTileInfo {
    public final int row;
    public final int col;
    public final String name;
    public final Integer mapId;

    public CachedTileInfo(int row, int col, String name, Integer mapId) {
        this.row = row;
        this.col = col;
        this.name = name == null ? "" : name;
        this.mapId = mapId;
    }
}
