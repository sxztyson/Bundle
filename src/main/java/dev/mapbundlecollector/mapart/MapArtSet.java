package dev.mapbundlecollector.mapart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MapArtSet {
    private final String projectId;
    private int rows;
    private int cols;
    private final Map<String, MapTile> tiles = new HashMap<>();
    private final List<int[]> missingTiles = new ArrayList<>();

    public MapArtSet(String projectId, int rows, int cols) {
        this.projectId = projectId;
        this.rows = Math.max(1, rows);
        this.cols = Math.max(1, cols);
    }

    public void updateDimensions(int newRows, int newCols) {
        this.rows = Math.max(this.rows, Math.max(1, newRows));
        this.cols = Math.max(this.cols, Math.max(1, newCols));
    }

    public void addTile(MapTile tile) {
        String key = tile.row() + "_" + tile.col();
        MapTile existing = tiles.get(key);
        if (existing != null && existing.hasData() && !tile.hasData()) {
            // Keep existing tile with good data
            return;
        }
        tiles.put(key, tile);
    }

    public void clearTiles() {
        tiles.clear();
        missingTiles.clear();
    }

    public void calculateMissing() {
        missingTiles.clear();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!tiles.containsKey(r + "_" + c)) {
                    missingTiles.add(new int[]{r, c});
                }
            }
        }
    }

    public String getProjectId() {
        return projectId;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
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
        return tiles.get(row + "_" + col);
    }

    public Map<String, MapTile> getTiles() {
        return Collections.unmodifiableMap(tiles);
    }

    public List<int[]> getMissingTiles() {
        return Collections.unmodifiableList(missingTiles);
    }
}
