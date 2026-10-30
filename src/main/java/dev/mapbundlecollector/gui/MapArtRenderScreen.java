package dev.mapbundlecollector.gui;

import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import dev.mapbundlecollector.mapart.MapTile;
import dev.mapbundlecollector.modules.MapArtViewerModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class MapArtRenderScreen extends Screen {
    private static final float MIN_ZOOM = 0.005f;
    private static final float MAX_ZOOM = 20.0f;
    private static final float LABEL_ZOOM_THRESHOLD = 0.4f;
    private static final float OVERLAY_MIN_TILE_PX = 6f;
    private static final int OVERLAY_FILL_CAP = 4096;
    private static final long EXPORT_FLASH_MS = 1200;

    private final Screen parentScreen;
    private float zoom = 1.0f;
    private float panX = 0;
    private float panY = 0;
    private boolean showGrid = true;
    private boolean showMissingOverlay = true;
    private boolean initializedPosition = false;

    private Button exportButton;
    private long exportFlashUntil = 0;
    private boolean exportFlashing = false;

    public MapArtRenderScreen(Screen parentScreen) {
        super(Component.literal("Map Art Viewer"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return;
        MapArtSet set = manager.getActiveSet();
        if (set == null) return;

        if (!initializedPosition) {
            fitToScreen();
            initializedPosition = true;
        }

        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        int buttonY = height - 28;
        int rightLimit = width - 130; // reserve the right-anchored Back button
        int curX = 12;

        addBarButton("Center View", curX, buttonY, 90, b -> fitToScreen());
        curX += 94;
        addBarButton(showGrid ? "Grid: ON" : "Grid: OFF", curX, buttonY, 80, b -> {
            showGrid = !showGrid;
            b.setMessage(Component.literal(showGrid ? "Grid: ON" : "Grid: OFF"));
        });
        curX += 84;
        addBarButton("+", curX, buttonY, 24, b -> adjustZoom(1.25f, width / 2.0, height / 2.0));
        curX += 28;
        addBarButton("-", curX, buttonY, 24, b -> adjustZoom(0.8f, width / 2.0, height / 2.0));
        curX += 28;
        addBarButton("Flip: " + set.getCols() + "x" + set.getRows(), curX, buttonY, 95, b -> {
            if (module != null) {
                module.toggleCoordinateOrder();
                fitToScreen();
                rebuildWidgets();
            }
        });
        curX += 99;

        if (manager.getProjectCount() > 1) {
            addBarButton("◀ Prev", curX, buttonY, 52, b -> switchProject(manager::prevProject));
            curX += 56;
            addBarButton("Next ▶", curX, buttonY, 52, b -> switchProject(manager::nextProject));
            curX += 56;
        }

        if (module != null && curX + 80 < rightLimit) {
            addBarButton(module.keepLoaded.get() ? "Loaded: ON" : "Loaded: OFF", curX, buttonY, 80, b -> {
                module.toggleKeepLoaded();
                b.setMessage(Component.literal(module.keepLoaded.get() ? "Loaded: ON" : "Loaded: OFF"));
            });
            curX += 84;
            if (curX + 46 < rightLimit) {
                addBarButton("Clear", curX, buttonY, 46, b -> {
                    module.clearLoaded();
                    onClose();
                });
                curX += 50;
            }
        }

        if (curX + 78 < rightLimit) {
            addBarButton(showMissingOverlay ? "Miss: ON" : "Miss: OFF", curX, buttonY, 78, b -> {
                showMissingOverlay = !showMissingOverlay;
                b.setMessage(Component.literal(showMissingOverlay ? "Miss: ON" : "Miss: OFF"));
            });
            curX += 82;
        }
        if (curX + 92 < rightLimit) {
            exportButton = addBarButton("Export JSON", curX, buttonY, 92, b -> exportMissing());
            exportFlashing = false;
            curX += 96;
        }
        if (curX + 80 < rightLimit) {
            addBarButton("Del Cache", curX, buttonY, 80, b -> {
                if (module != null) module.deleteCache();
            });
        }

        addRenderableWidget(Button.builder(Component.literal("Back to Inventory"), b -> onClose())
                .bounds(width - 126, buttonY, 114, 20)
                .build());
    }

    private Button addBarButton(String label, int x, int y, int width, Consumer<Button> onPress) {
        Button button = Button.builder(Component.literal(label), onPress::accept)
                .bounds(x, y, width, 20)
                .build();
        addRenderableWidget(button);
        return button;
    }

    private void switchProject(Runnable switcher) {
        switcher.run();
        fitToScreen();
        rebuildWidgets();
    }

    private void exportMissing() {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module != null) module.exportMissing();
        if (exportButton != null) {
            exportFlashUntil = System.currentTimeMillis() + EXPORT_FLASH_MS;
        }
    }

    private void updateExportLabel() {
        boolean flashing = System.currentTimeMillis() < exportFlashUntil;
        if (exportButton != null && flashing != exportFlashing) {
            exportFlashing = flashing;
            exportButton.setMessage(Component.literal(flashing ? "Exported!" : "Export JSON"));
        }
    }

    private void fitToScreen() {
        MapArtManager manager = MapArtManager.get();
        int texW = manager.getTextureWidth();
        int texH = manager.getTextureHeight();
        if (texW <= 0 || texH <= 0) return;

        int availW = width - 40;
        int availH = height - 80;

        float scaleW = (float) availW / texW;
        float scaleH = (float) availH / texH;
        zoom = Math.max(MIN_ZOOM, Math.min(scaleW, scaleH));

        panX = (width - (texW * zoom)) / 2.0f;
        panY = (height - (texH * zoom)) / 2.0f + 10;
    }

    private void adjustZoom(float factor, double cursorX, double cursorY) {
        float oldZoom = zoom;
        zoom = Math.max(MIN_ZOOM, Math.min(zoom * factor, MAX_ZOOM));

        // Zoom toward cursor position
        panX = (float) (cursorX - (cursorX - panX) * (zoom / oldZoom));
        panY = (float) (cursorY - (cursorY - panY) * (zoom / oldZoom));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0) {
            float factor = verticalAmount > 0 ? 1.15f : 0.85f;
            adjustZoom(factor, mouseX, mouseY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        panX += (float) offsetX;
        panY += (float) offsetY;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }

        MapArtManager manager = MapArtManager.get();
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);

        if (k == GLFW.GLFW_KEY_M) {
            showMissingOverlay = !showMissingOverlay;
            rebuildWidgets();
            return true;
        }
        if (k == GLFW.GLFW_KEY_E) {
            exportMissing();
            return true;
        }
        if (k == GLFW.GLFW_KEY_DELETE && module != null) {
            module.deleteCache();
            return true;
        }

        // Project switching shortcuts: [ or ], Tab, or Shift + Left/Right
        boolean isShift = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 || input.hasShiftDown();
        boolean isLeftKey = input.isLeft() || k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_KP_4;
        boolean isRightKey = input.isRight() || k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_KP_6;
        boolean isUpKey = input.isUp() || k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_KP_8;
        boolean isDownKey = input.isDown() || k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_KP_2;

        if (manager.hasValidMapArt() && manager.getProjectCount() > 1) {
            if (k == GLFW.GLFW_KEY_LEFT_BRACKET || (isShift && isLeftKey)) {
                switchProject(manager::prevProject);
                return true;
            }
            if (k == GLFW.GLFW_KEY_RIGHT_BRACKET || (isShift && isRightKey) || k == GLFW.GLFW_KEY_TAB) {
                switchProject(manager::nextProject);
                return true;
            }
        }

        // Pan with Arrow Keys or WASD (dynamically scaled for large maps)
        float panStep = Math.max(50f, 150f * (1.0f / Math.max(0.1f, zoom * 8f)));
        if (isUpKey || k == GLFW.GLFW_KEY_W) {
            panY += panStep;
            return true;
        }
        if (isDownKey || k == GLFW.GLFW_KEY_S) {
            panY -= panStep;
            return true;
        }
        if (isLeftKey || k == GLFW.GLFW_KEY_A) {
            if (manager.hasValidMapArt() && manager.getProjectCount() > 1 && !isShift && isLeftKey) {
                switchProject(manager::prevProject);
                return true;
            }
            panX += panStep;
            return true;
        }
        if (isRightKey || k == GLFW.GLFW_KEY_D) {
            if (manager.hasValidMapArt() && manager.getProjectCount() > 1 && !isShift && isRightKey) {
                switchProject(manager::nextProject);
                return true;
            }
            panX -= panStep;
            return true;
        }

        // Zoom shortcuts: + / = / PageUp / - / _ / PageDown
        if (k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD || k == GLFW.GLFW_KEY_PAGE_UP) {
            adjustZoom(1.25f, width / 2.0, height / 2.0);
            return true;
        }
        if (k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT || k == GLFW.GLFW_KEY_PAGE_DOWN) {
            adjustZoom(0.8f, width / 2.0, height / 2.0);
            return true;
        }
        if (k == GLFW.GLFW_KEY_C || k == GLFW.GLFW_KEY_SPACE) {
            fitToScreen();
            return true;
        }
        if (k == GLFW.GLFW_KEY_G) {
            showGrid = !showGrid;
            rebuildWidgets();
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parentScreen);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Dark background with subtle gradient
        context.fill(0, 0, width, height, 0xF0121418);

        MapArtManager manager = MapArtManager.get();
        MapArtSet set = manager.getActiveSet();

        if (manager.hasValidMapArt() && set != null) {
            updateExportLabel();

            int texW = manager.getTextureWidth();
            int texH = manager.getTextureHeight();
            float drawW = texW * zoom;
            float drawH = texH * zoom;

            // Draw map shadow/border
            context.fill((int) panX - 2, (int) panY - 2, (int) (panX + drawW) + 2, (int) (panY + drawH) + 2, 0xFF2A2E38);

            // Chunked async map render
            MapArtChunkRenderer.get().render(context, panX, panY, zoom, 0, 0, width, height);

            float tilePx = MapArtSet.TILE_PIXELS * zoom;
            if (showGrid) {
                renderGrid(context, set, tilePx, drawW, drawH);
            }
            if (showMissingOverlay && tilePx >= OVERLAY_MIN_TILE_PX) {
                renderMissingOverlay(context, set, tilePx);
            }

            // Top Header Card
            renderHeaderCard(context, manager, set, texW, texH);

            // Mouse hover inspector tooltip
            if (mouseX >= panX && mouseX < panX + drawW && mouseY >= panY && mouseY < panY + drawH) {
                int hCol = (int) ((mouseX - panX) / tilePx);
                int hRow = (int) ((mouseY - panY) / tilePx);

                if (hRow >= 0 && hRow < set.getRows() && hCol >= 0 && hCol < set.getCols()) {
                    renderTileTooltip(context, set, hRow, hCol, mouseX, mouseY);
                }
            }
        } else {
            context.centeredText(font, "No map bundles found in player inventory.", width / 2, height / 2, 0xFFAAAAAA);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    // Grid lines + [r,c] labels restricted to the visible row/col window so a 204x124
    // mapart never iterates thousands of off-screen tiles.
    private void renderGrid(GuiGraphicsExtractor context, MapArtSet set, float tilePx, float drawW, float drawH) {
        int rowFrom = Math.max(0, (int) Math.floor(-panY / tilePx));
        int rowTo = Math.min(set.getRows(), (int) Math.ceil((height - panY) / tilePx));
        int colFrom = Math.max(0, (int) Math.floor(-panX / tilePx));
        int colTo = Math.min(set.getCols(), (int) Math.ceil((width - panX) / tilePx));

        for (int r = rowFrom; r <= rowTo; r++) {
            int yPos = (int) (panY + r * tilePx);
            context.fill((int) panX, yPos, (int) (panX + drawW), yPos + 1, 0x66FFFFFF);
        }
        for (int c = colFrom; c <= colTo; c++) {
            int xPos = (int) (panX + c * tilePx);
            context.fill(xPos, (int) panY, xPos + 1, (int) (panY + drawH), 0x66FFFFFF);
        }

        if (zoom >= LABEL_ZOOM_THRESHOLD) {
            for (int r = rowFrom; r < rowTo; r++) {
                for (int c = colFrom; c < colTo; c++) {
                    int cx = (int) (panX + c * tilePx + 4);
                    int cy = (int) (panY + r * tilePx + 4);
                    MapTile tile = set.getTile(r, c);
                    int color = (tile != null) ? 0xDDFFFFFF : 0xFFFF5555;
                    context.text(font, "[" + r + "," + c + "]", cx, cy, color);
                }
            }
        }
    }

    private void renderMissingOverlay(GuiGraphicsExtractor context, MapArtSet set, float tilePx) {
        int rowFrom = Math.max(0, (int) Math.floor(-panY / tilePx));
        int rowTo = Math.min(set.getRows(), (int) Math.ceil((height - panY) / tilePx));
        int colFrom = Math.max(0, (int) Math.floor(-panX / tilePx));
        int colTo = Math.min(set.getCols(), (int) Math.ceil((width - panX) / tilePx));

        int fills = 0;
        for (int r = rowFrom; r < rowTo && fills < OVERLAY_FILL_CAP; r++) {
            for (int c = colFrom; c < colTo && fills < OVERLAY_FILL_CAP; c++) {
                if (set.getTile(r, c) != null) continue;
                int x = (int) (panX + c * tilePx);
                int y = (int) (panY + r * tilePx);
                context.fill(x, y, x + (int) tilePx, y + (int) tilePx, 0x40FF0000);
                fills++;
            }
        }
    }

    private void renderHeaderCard(GuiGraphicsExtractor context, MapArtManager manager, MapArtSet set, int texW, int texH) {
        int cardW = Math.min(width - 24, 480);
        int cardX = (width - cardW) / 2;
        int cardY = 8;
        int cardH = 44;

        // Background
        context.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xDD1E2128);
        context.outline(cardX, cardY, cardW, cardH, 0xFF353945);

        // Title
        String title = set.getProjectId();
        if (manager.getProjectCount() > 1) {
            title += " (" + (manager.getCurrentProjectIndex() + 1) + "/" + manager.getProjectCount() + ")";
        }
        context.text(font, title, cardX + 10, cardY + 6, 0xFFFFFFFF);

        // Subtitle: tiles and pixel resolution
        String sub = set.getCols() + "x" + set.getRows() + " • " + set.getPresentCount() + "/" + set.getTotalExpected()
            + " tiles • " + texW + " x " + texH + " px";
        context.text(font, sub, cardX + 10, cardY + 18, 0xFFAAAAAA);

        // Status line: missing + cached counts, build progress
        StringBuilder status = new StringBuilder();
        status.append(set.getMissingTiles().isEmpty() ? "§amissing 0§r" : "§cmissing " + set.getMissingTiles().size() + "§r");
        status.append(" • cached ").append(manager.getCachedTileCount(set.getProjectId()));
        int pending = MapArtChunkRenderer.get().pendingBuilds();
        if (pending > 0) status.append(" • §erendering ").append(pending).append(" chunks…§r");
        context.text(font, status.toString(), cardX + 10, cardY + 30, 0xFF888888);

        // Status badge
        boolean complete = set.isComplete();
        String badge = complete ? "[100% COMPLETE]" : "[" + set.getPresentCount() + "/" + set.getTotalExpected() + " - " + set.getCompletionPercentage() + "%]";
        int badgeColor = complete ? 0xFF55FF55 : 0xFFFFAA00;
        int badgeW = font.width(badge);
        context.text(font, badge, cardX + cardW - badgeW - 10, cardY + 6, badgeColor);

        // Zoom text
        String zoomStr = "Zoom: " + Math.round(zoom * 100) + "%";
        int zoomW = font.width(zoomStr);
        context.text(font, zoomStr, cardX + cardW - zoomW - 10, cardY + 18, 0xFF888888);
    }

    private void renderTileTooltip(GuiGraphicsExtractor context, MapArtSet set, int row, int col, int mouseX, int mouseY) {
        MapTile tile = set.getTile(row, col);
        List<Component> lines = new ArrayList<>();

        if (tile != null && tile.isLive()) {
            lines.add(Component.literal("Tile: [" + row + ", " + col + "]").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            lines.add(Component.literal("Name: " + tile.originalName()).withStyle(ChatFormatting.WHITE));
            if (tile.mapId() != null) {
                lines.add(Component.literal("Map ID: #" + tile.mapId()).withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Status: Stored in Inventory Bundle").withStyle(ChatFormatting.GREEN));
        } else if (tile != null) {
            lines.add(Component.literal("Tile: [" + row + ", " + col + "]").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            lines.add(Component.literal("Name: " + tile.originalName()).withStyle(ChatFormatting.WHITE));
            if (tile.mapId() != null) {
                lines.add(Component.literal("Map ID: #" + tile.mapId()).withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Status: Cached from previous batch").withStyle(ChatFormatting.YELLOW));
        } else {
            lines.add(Component.literal("Tile: [" + row + ", " + col + "]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            lines.add(Component.literal("Status: MISSING FROM INVENTORY").withStyle(ChatFormatting.DARK_RED));
            lines.add(Component.literal("Place bundle with piece in inventory").withStyle(ChatFormatting.GRAY));
        }

        context.setComponentTooltipForNextFrame(font, lines, mouseX + 8, mouseY + 8);
    }
}
