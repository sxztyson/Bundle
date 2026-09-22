package dev.mapbundlecollector.mapart;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapArtManager {
    private static final MapArtManager INSTANCE = new MapArtManager();

    private static final Pattern PATTERN_GRID = Pattern.compile("^(.+?)[_\\-\\s,\\[\\(]+(\\d+)[_\\-\\s,xX]+(\\d+)[_\\]\\)]*(?:\\.[a-zA-Z0-9]+)?$");
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

        List<ItemStack> candidates = new ArrayList<>();

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(Items.BUNDLE)) {
                BundleContents contents = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                for (ItemStack inner : contents.itemCopyStream().toList()) {
                    if (inner != null && inner.is(Items.FILLED_MAP)) {
                        candidates.add(inner);
                    }
                }
            } else if (stack.is(Items.FILLED_MAP)) {
                candidates.add(stack);
            }
        }

        if (candidates.isEmpty()) {
            if (!keepLoaded) {
                clear();
            }
            return;
        }

        Map<String, List<ParsedEntry>> scannedProjects = new HashMap<>();

        for (ItemStack stack : candidates) {
            String rawName = stack.getHoverName().getString().trim();
            Matcher mGrid = PATTERN_GRID.matcher(rawName);
            if (mGrid.matches()) {
                String projName = mGrid.group(1).trim();
                int num1 = Integer.parseInt(mGrid.group(2));
                int num2 = Integer.parseInt(mGrid.group(3));

                boolean isXY = module == null || module.coordinateFormat.get() == dev.mapbundlecollector.modules.MapArtViewerModule.CoordinateFormat.X_Y;
                int col = isXY ? num1 : num2;
                int row = isXY ? num2 : num1;

                scannedProjects.computeIfAbsent(projName, k -> new ArrayList<>())
                        .add(new ParsedEntry(projName, row, col, rawName, stack));
            } else {
                Matcher m1D = PATTERN_1D.matcher(rawName);
                if (m1D.matches()) {
                    String projName = m1D.group(1).trim();
                    int idx = Integer.parseInt(m1D.group(2));
                    scannedProjects.computeIfAbsent(projName, k -> new ArrayList<>())
                            .add(new ParsedEntry(projName, idx, 0, rawName, stack));
                } else if (!rawName.isEmpty()) {
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

        if (projectSets.isEmpty()) {
            clear();
            return;
        }

        if (currentProjectIndex >= projectSets.size()) {
            currentProjectIndex = 0;
        }
        activeSet = projectSets.get(currentProjectIndex);

        int sig = calculateSignature(activeSet);
        if (sig != lastScannedVersion) {
            rebuildTexture(activeSet);
            lastScannedVersion = sig;
        }
    }

    public void nextProject() {
        if (projectSets.size() <= 1) return;
        currentProjectIndex = (currentProjectIndex + 1) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        rebuildTexture(activeSet);
        lastScannedVersion = calculateSignature(activeSet);
    }

    public void prevProject() {
        if (projectSets.size() <= 1) return;
        currentProjectIndex = (currentProjectIndex - 1 + projectSets.size()) % projectSets.size();
        activeSet = projectSets.get(currentProjectIndex);
        rebuildTexture(activeSet);
        lastScannedVersion = calculateSignature(activeSet);
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
                hash = 31 * hash + colors[0] + colors[colors.length - 1];
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
        return activeSet != null && textureId != null;
    }

    public void clear() {
        projectSets.clear();
        currentProjectIndex = 0;
        activeSet = null;
        lastScannedVersion = -1;
        if (dynamicTexture != null) {
            dynamicTexture.close();
            if (textureId != null) {
                Minecraft.getInstance().getTextureManager().release(textureId);
            }
            dynamicTexture = null;
            textureId = null;
        }
    }

    private record ParsedEntry(String projName, int row, int col, String name, ItemStack stack) {}
}
