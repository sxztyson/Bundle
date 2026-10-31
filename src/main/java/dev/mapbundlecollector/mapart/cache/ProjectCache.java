package dev.mapbundlecollector.mapart.cache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// In-memory presence index for one project. Not thread-safe by itself; MapArtCache synchronizes.
public final class ProjectCache {
    private final String projectId;
    private int expectedRows;
    private int expectedCols;
    private final Map<String, CachedTileInfo> tiles = new HashMap<>();

    public ProjectCache(String projectId) {
        this.projectId = projectId;
    }

    public CachedTileInfo getOrCreateTile(int row, int col) {
        return tiles.computeIfAbsent(row + "_" + col, k -> new CachedTileInfo(row, col, "", null));
    }

    public CachedTileInfo getTile(int row, int col) {
        return tiles.get(row + "_" + col);
    }

    void putTileInfo(CachedTileInfo info) {
        tiles.put(info.row + "_" + info.col, info);
    }

    public int tileCount() {
        return tiles.size();
    }

    public Map<String, CachedTileInfo> getTiles() {
        return Collections.unmodifiableMap(tiles);
    }

    public String getProjectId() {
        return projectId;
    }

    public int getExpectedRows() {
        return expectedRows;
    }

    public void setExpectedRows(int expectedRows) {
        this.expectedRows = Math.max(0, expectedRows);
    }

    public int getExpectedCols() {
        return expectedCols;
    }

    public void setExpectedCols(int expectedCols) {
        this.expectedCols = Math.max(0, expectedCols);
    }
}
