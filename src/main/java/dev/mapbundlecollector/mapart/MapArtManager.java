package dev.mapbundlecollector.mapart;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public final class MapArtManager {
    private static final MapArtManager INSTANCE = new MapArtManager();

    // Matches names like: HSiRN5MaQAEEEvk_3_3.nbt, HSiRN5MaQAEEEvk_0_1, MyMap-2-4, Art 1 2, Sam [0, 0]
    private static final Pattern PATTERN_GRID = Pattern.compile("^(.+?)[_\\-\\s,\\[\\(]+(\\d+)[_\\-\\s,xX]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
    // Fallback 1D index: MyMap_1.nbt, MyMap 4, Art #2
    private static final Pattern PATTERN_1D = Pattern.compile("^(.+?)[_\\-\\s#\\[\\(]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");

    private final List<MapArtSet> projectSets = new ArrayList<>();
    private int currentProjectIndex = 0;

    private MapArtSet activeSet;
    private Identifier textureId;
    private DynamicTexture dynamicTexture;
    private int lastScannedVersion = -1;
    private int textureWidth = 0;
    private int textureHeight = 0;

    public static MapArtManager get() {
        return INSTANCE;
    }

    private MapArtManager() {}

    public void forceUpdate(LocalPlayer player) {
        lastScannedVersion = -1;
        update(player);
    }

    public void update(LocalPlayer player) {
        dev.mapbundlecollector.modules.MapArtViewerModule module =
            meteordevelopment.meteorclient.systems.modules.Modules.get().get(dev.mapbundlecollector.modules.MapArtViewerModule.class);
        boolean keepLoaded = (module != null && module.keepLoaded.get());

        if (player == null || Minecraft.getInstance().level == null) {
            if (!keepLoaded) {
                clear();
            }
            return;
        }

        List<ItemStack> mapStacks = new ArrayList<>();

        // 1. Scan player inventory slots for bundles and maps
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.has(DataComponents.BUNDLE_CONTENTS)) {
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    for (ItemStack bundledItem : bundle.itemCopyStream().toList()) {
                        if (bundledItem.is(Items.FILLED_MAP)) {
                            mapStacks.add(bundledItem);
                        }
                    }
                }
            } else if (stack.is(Items.FILLED_MAP)) {
                mapStacks.add(stack);
            }
        }

        if (mapStacks.isEmpty()) {
            if (!keepLoaded) {
                clear();
            }
            return;
        }

        // 2. Parse and group maps by project name
        Map<String, List<ParsedEntry>> scannedProjects = new HashMap<>();

        for (ItemStack stack : mapStacks) {
            String rawName = net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
            Matcher gridMatcher = PATTERN_GRID.matcher(rawName);

            if (gridMatcher.matches()) {
                String project = gridMatcher.group(1).trim();
                int num1 = Integer.parseInt(gridMatcher.group(2));
                int num2 = Integer.parseInt(gridMatcher.group(3));

                boolean isXY = module == null || module.coordinateFormat.get() == dev.mapbundlecollector.modules.MapArtViewerModule.CoordinateFormat.X_Y;

                int col = isXY ? num1 : num2;
                int row = isXY ? num2 : num1;

                scannedProjects.computeIfAbsent(project, k -> new ArrayList<>())
                        .add(new ParsedEntry(project, row, col, rawName, stack));
            } else {
                Matcher singleMatcher = PATTERN_1D.matcher(rawName);
                if (singleMatcher.matches()) {
                    String project = singleMatcher.group(1).trim();
                    int index = Integer.parseInt(singleMatcher.group(2));
                    // Assume 1D index
                    scannedProjects.computeIfAbsent(project, k -> new ArrayList<>())
                            .add(new ParsedEntry(project, index, 0, rawName, stack));
                } else if (!rawName.isEmpty()) {
                    // Fallback for single/standalone map without numeric index
                    scannedProjects.computeIfAbsent(rawName, k -> new ArrayList<>())
                            .add(new ParsedEntry(rawName, 0, 0, rawName, stack));
                }
            }
        }

        if (scannedProjects.isEmpty()) {
            if (!keepLoaded) {
                clear();
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (keepLoaded) {
            // MERGE into existing projectSets so pieces stay loaded when bundles are removed!
            for (Map.Entry<String, List<ParsedEntry>> entry : scannedProjects.entrySet()) {
                String projName = entry.getKey();
                List<ParsedEntry> entries = entry.getValue();

                int maxRow = 0;
                int maxCol = 0;
                for (ParsedEntry pe : entries) {
                    if (pe.row > maxRow) maxRow = pe.row;
                    if (pe.col > maxCol) maxCol = pe.col;
                }

                MapArtSet targetSet = null;
                for (MapArtSet existing : projectSets) {
                    if (existing.getProjectId().equalsIgnoreCase(projName)) {
                        targetSet = existing;
                        break;
                    }
                }

                if (targetSet == null) {
                    targetSet = new MapArtSet(projName, maxRow + 1, maxCol + 1);
                    projectSets.add(targetSet);
                } else {
                    targetSet.updateDimensions(maxRow + 1, maxCol + 1);
                }

                for (ParsedEntry pe : entries) {
                    MapId mapId = pe.stack.get(DataComponents.MAP_ID);
                    MapItemSavedData mapState = (mc.level != null && mapId != null) ? mc.level.getMapData(mapId) : null;
                    targetSet.addTile(new MapTile(pe.row, pe.col, pe.name, mapId, pe.stack, mapState));
                }
                targetSet.calculateMissing();
            }
        } else {
            // Fresh overwrite from current inventory
            List<MapArtSet> newSets = new ArrayList<>();
            for (Map.Entry<String, List<ParsedEntry>> entry : scannedProjects.entrySet()) {
                String projName = entry.getKey();
                List<ParsedEntry> entries = entry.getValue();

                int maxRow = 0;
                int maxCol = 0;
                for (ParsedEntry pe : entries) {
                    if (pe.row > maxRow) maxRow = pe.row;
                    if (pe.col > maxCol) maxCol = pe.col;
                }

                MapArtSet set = new MapArtSet(projName, maxRow + 1, maxCol + 1);
                for (ParsedEntry pe : entries) {
                    MapId mapId = pe.stack.get(DataComponents.MAP_ID);
                    MapItemSavedData mapState = (mc.level != null && mapId != null) ? mc.level.getMapData(mapId) : null;
                    set.addTile(new MapTile(pe.row, pe.col, pe.name, mapId, pe.stack, mapState));
                }
                set.calculateMissing();
                newSets.add(set);
            }

            projectSets.clear();
            projectSets.addAll(newSets);
        }

        // Sort projects by number of pieces (descending)
        projectSets.sort((a, b) -> Integer.compare(b.getPresentCount(), a.getPresentCount()));

        if (currentProjectIndex >= projectSets.size()) {
            currentProjectIndex = 0;
        }
        if (currentProjectIndex < 0) {
            currentProjectIndex = 0;
        }

        if (projectSets.isEmpty()) return;

        MapArtSet chosenSet = projectSets.get(currentProjectIndex);

        // Calculate a signature to check if data changed
        int signature = calculateSignature(chosenSet);
        if (signature != lastScannedVersion || activeSet == null || !activeSet.getProjectId().equals(chosenSet.getProjectId())) {
            activeSet = chosenSet;
            lastScannedVersion = signature;
            rebuildTexture(chosenSet);
        } else {
            activeSet = chosenSet;
        }
    }

    public void nextProject() {
        if (projectSets.isEmpty()) return;
        currentProjectIndex = (currentProjectIndex + 1) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        lastScannedVersion = -1;
        rebuildTexture(activeSet);
    }

    public void prevProject() {
        if (projectSets.isEmpty()) return;
        currentProjectIndex = (currentProjectIndex - 1 + projectSets.size()) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        lastScannedVersion = -1;
        rebuildTexture(activeSet);
    }

    public int getProjectCount() {
        return projectSets.size();
    }

    public int getCurrentProjectIndex() {
        return currentProjectIndex;
    }

    public List<MapArtSet> getProjectSets() {
        return Collections.unmodifiableList(projectSets);
    }

    private int calculateSignature(MapArtSet set) {
        int hash = set.getProjectId().hashCode();
        hash = 31 * hash + set.getRows();
        hash = 31 * hash + set.getCols();
        hash = 31 * hash + set.getPresentCount();
        for (MapTile tile : set.getTiles().values()) {
            hash = 31 * hash + tile.row() * 1000 + tile.col();
            if (tile.mapId() != null) hash = 31 * hash + tile.mapId().hashCode();
            byte[] colors = tile.getColors();
            if (colors != null && colors.length > 0) {
                // Map packets commonly update only interior pixels. Hashing just
                // the edges left the initial blank texture cached indefinitely.
                hash = 31 * hash + java.util.Arrays.hashCode(colors);
            }
        }
        return hash;
    }

    private void rebuildTexture(MapArtSet set) {
        int cols = set.getCols();
        int rows = set.getRows();
        int width = cols * 128;
        int height = rows * 128;

        NativeImage image = new NativeImage(width, height, false);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                MapTile tile = set.getTile(r, c);
                int originX = c * 128;
                int originY = r * 128;

                if (tile != null && tile.hasData()) {
                    byte[] colors = tile.getColors();
                    if (colors != null && colors.length >= 16384) {
                        for (int py = 0; py < 128; py++) {
                            for (int px = 0; px < 128; px++) {
                                byte colorByte = colors[px + py * 128];
                                int argb = MapColor.getColorFromPackedId(colorByte & 0xFF);
                                image.setPixel(originX + px, originY + py, argb);
                            }
                        }
                    }
                } else {
                    // Placeholder for missing or loading map piece
                    for (int py = 0; py < 128; py++) {
                        for (int px = 0; px < 128; px++) {
                            boolean isBorder = (px == 0 || px == 127 || py == 0 || py == 127);
                            boolean checker = ((px / 16) + (py / 16)) % 2 == 0;
                            int color = isBorder ? 0xFF3E434D : (checker ? 0xFF1E2128 : 0xFF272B34);
                            image.setPixel(originX + px, originY + py, color);
                        }
                    }
                }
            }
        }

        Minecraft mc = Minecraft.getInstance();

        if (dynamicTexture != null) {
            dynamicTexture.close();
            if (textureId != null) {
                mc.getTextureManager().release(textureId);
            }
        }

        String safeProject = set.getProjectId().replaceAll("[^a-z0-9_.-]", "_").toLowerCase();
        textureId = Identifier.fromNamespaceAndPath("mapbundlecollector", "map_art_" + safeProject + "_" + Math.abs(lastScannedVersion));
        dynamicTexture = new DynamicTexture(() -> "map_art_" + safeProject, image);
        mc.getTextureManager().register(textureId, dynamicTexture);

        textureWidth = width;
        textureHeight = height;
    }

    public MapArtSet getActiveSet() {
        return activeSet;
    }

    public Identifier getTextureId() {
        return textureId;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public boolean hasValidMapArt() {
        return activeSet != null && activeSet.getPresentCount() > 0 && textureId != null;
    }

    public void clear() {
        activeSet = null;
        projectSets.clear();
        currentProjectIndex = 0;
        lastScannedVersion = -1;
        if (dynamicTexture != null) {
            dynamicTexture.close();
            dynamicTexture = null;
        }
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            textureId = null;
        }
        textureWidth = 0;
        textureHeight = 0;
    }

    private record ParsedEntry(String project, int row, int col, String name, ItemStack stack) {}
}
