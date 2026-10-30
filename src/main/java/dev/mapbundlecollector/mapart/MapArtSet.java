package dev.mapbundlecollector.mapart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// State of one map-art project: its tiles plus the canvas dimensions. Canvas rows/cols are the
// max of the expected dimensions (0 = auto) and the largest seen tile coordinate + 1, so a
// partially loaded batch is verified against the full target grid.
public final class MapArtSet {
    public static final int TILE_PIXELS = 128;

    private final String projectId;
    private int expectedRows;
    private int expectedCols;
    private int seenRows;
    private int seenCols;
    private int rows = 1;
    private int cols = 1;
    // True when this set was loaded from the persistent cache (no live tiles). Used to drop
    // disk-backed sets from memory when the persistent-cache setting is turned off.
    private boolean cacheOrigin;
    private final Map<String, MapTile> tiles = new HashMap<>();
    private final List<int[]> missingTiles = new ArrayList<>();

    public MapArtSet(String projectId, int rows, int cols) {
        this.projectId = projectId;
        updateDimensions(Math.max(1, rows), Math.max(1, cols));
    }

    public void updateDimensions(int newRows, int newCols) {
        seenRows = Math.max(seenRows, Math.max(1, newRows));
        seenCols = Math.max(seenCols, Math.max(1, newCols));
        recomputeCanvas();
    }

    public void setExpectedDims(int rows, int cols) {
        expectedRows = Math.max(0, rows);
        expectedCols = Math.max(0, cols);
        recomputeCanvas();
    }

    private void recomputeCanvas() {
        rows = Math.max(expectedRows, seenRows);
        cols = Math.max(expectedCols, seenCols);
    }

    public void addTile(MapTile tile) {
        MapTile existing = tiles.get(tileKey(tile.row(), tile.col()));
        // A live tile beats a cache-only one; anything else keeps the existing entry.
        if (existing != null && !(tile.isLive() && !existing.isLive())) return;
        // Seen dims follow every added tile so carried-over/disk tiles can grow the canvas.
        seenRows = Math.max(seenRows, tile.row() + 1);
        seenCols = Math.max(seenCols, tile.col() + 1);
        recomputeCanvas();
        tiles.put(tileKey(tile.row(), tile.col()), tile);
    }

    public void clearTiles() {
        tiles.clear();
        missingTiles.clear();
    }

    public void calculateMissing() {
        missingTiles.clear();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!tiles.containsKey(tileKey(r, c))) {
                    missingTiles.add(new int[]{r, c});
                }
            }
        }
    }

    public String getProjectId() {
        return projectId;
    }

    public boolean isCacheOrigin() {
        return cacheOrigin;
    }

    public void markCacheOrigin() {
        this.cacheOrigin = true;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public int getExpectedRows() {
        return expectedRows;
    }

    public int getExpectedCols() {
        return expectedCols;
    }

    public int getTotalExpected() {
        return rows * cols;
    }

    public int getPresentCount() {
        return tiles.size();
    }

    public boolean isComplete() {
        return getPresentCount() >= getTotalExpected() && missingTiles.isEmpty();
    }

    public int getCompletionPercentage() {
        int total = getTotalExpected();
        if (total <= 0) return 0;
        return Math.min(100, (getPresentCount() * 100) / total);
    }

    public MapTile getTile(int row, int col) {
        return tiles.get(tileKey(row, col));
    }

    public Map<String, MapTile> getTiles() {
        return Collections.unmodifiableMap(tiles);
    }

    public List<int[]> getMissingTiles() {
        return Collections.unmodifiableList(missingTiles);
    }

    private static String tileKey(int row, int col) {
        return row + "_" + col;
    }
}
