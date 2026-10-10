package dev.mapbundlecollector.gui;

import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import dev.mapbundlecollector.mapart.MapTile;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class MapArtRenderScreen extends Screen {
    private static final float MIN_ZOOM = 0.005f;
    private static final float MAX_ZOOM = 20.0f;

    private final Screen parentScreen;
    private float zoom = 1.0f;
    private float panX = 0;
    private float panY = 0;
    private boolean showGrid = true;
    private boolean initializedPosition = false;

    public MapArtRenderScreen(Screen parentScreen) {
        super(Text.literal("Map Art Viewer"));
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

        int buttonY = height - 28;

        // Reset / Center button
        addDrawableChild(ButtonWidget.builder(Text.literal("Center View"), b -> fitToScreen())
                .dimensions(12, buttonY, 90, 20)
                .build());

        // Toggle Grid button
        addDrawableChild(ButtonWidget.builder(Text.literal(showGrid ? "Grid: ON" : "Grid: OFF"), b -> {
            showGrid = !showGrid;
            b.setMessage(Text.literal(showGrid ? "Grid: ON" : "Grid: OFF"));
        }).dimensions(108, buttonY, 80, 20).build());

        // Zoom In button
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> adjustZoom(1.25f, width / 2.0, height / 2.0))
                .dimensions(194, buttonY, 24, 20)
                .build());

        // Zoom Out button
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> adjustZoom(0.8f, width / 2.0, height / 2.0))
                .dimensions(222, buttonY, 24, 20)
                .build());

        // Flip / Swap orientation button
        addDrawableChild(ButtonWidget.builder(Text.literal("Flip: " + set.getCols() + "x" + set.getRows()), b -> {
            dev.mapbundlecollector.modules.MapArtViewerModule mod =
                meteordevelopment.meteorclient.systems.modules.Modules.get().get(dev.mapbundlecollector.modules.MapArtViewerModule.class);
            if (mod != null) {
                mod.toggleCoordinateOrder();
                fitToScreen();
                clearAndInit();
            }
        }).dimensions(252, buttonY, 95, 20).build());

        int curX = 352;

        // Multi-map navigation buttons
        if (manager.getProjectCount() > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("◀ Prev"), b -> {
                manager.prevProject();
                fitToScreen();
                clearAndInit();
            }).dimensions(curX, buttonY, 52, 20).build());
            curX += 56;

            addDrawableChild(ButtonWidget.builder(Text.literal("Next ▶"), b -> {
                manager.nextProject();
                fitToScreen();
                clearAndInit();
            }).dimensions(curX, buttonY, 52, 20).build());
            curX += 56;
        }

        // Loaded mode and Clear cache buttons
        dev.mapbundlecollector.modules.MapArtViewerModule mod =
            meteordevelopment.meteorclient.systems.modules.Modules.get().get(dev.mapbundlecollector.modules.MapArtViewerModule.class);
        if (mod != null && curX + 85 < width - 130) {
            addDrawableChild(ButtonWidget.builder(Text.literal(mod.keepLoaded.get() ? "Loaded: ON" : "Loaded: OFF"), b -> {
                mod.toggleKeepLoaded();
                b.setMessage(Text.literal(mod.keepLoaded.get() ? "Loaded: ON" : "Loaded: OFF"));
            }).dimensions(curX, buttonY, 80, 20).build());
            curX += 84;

            if (curX + 50 < width - 130) {
                addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> {
                    mod.clearLoaded();
                    close();
                }).dimensions(curX, buttonY, 46, 20).build());
            }
        }

        // Close / Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("Back to Inventory"), b -> close())
                .dimensions(width - 126, buttonY, 114, 20)
                .build());
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
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        panX += (float) offsetX;
        panY += (float) offsetY;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        MapArtManager manager = MapArtManager.get();

        // Project switching shortcuts: [ or ], Tab, or Shift + Left/Right
        boolean isShift = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 || input.hasShift();
        boolean isLeftKey = input.isLeft() || k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_KP_4;
        boolean isRightKey = input.isRight() || k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_KP_6;
        boolean isUpKey = input.isUp() || k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_KP_8;
        boolean isDownKey = input.isDown() || k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_KP_2;

        if (manager.hasValidMapArt() && manager.getProjectCount() > 1) {
            if (k == GLFW.GLFW_KEY_LEFT_BRACKET || (isShift && isLeftKey)) {
                manager.prevProject();
                fitToScreen();
                clearAndInit();
                return true;
            }
            if (k == GLFW.GLFW_KEY_RIGHT_BRACKET || (isShift && isRightKey) || k == GLFW.GLFW_KEY_TAB) {
                manager.nextProject();
                fitToScreen();
                clearAndInit();
                return true;
            }
        }

        // Pan with Arrow Keys or WASD (dynamically scaled for large 50x50 maps)
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
                manager.prevProject();
                fitToScreen();
                clearAndInit();
                return true;
            }
            panX += panStep;
            return true;
        }
        if (isRightKey || k == GLFW.GLFW_KEY_D) {
            if (manager.hasValidMapArt() && manager.getProjectCount() > 1 && !isShift && isRightKey) {
                manager.nextProject();
                fitToScreen();
                clearAndInit();
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
            clearAndInit();
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parentScreen);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dark background with subtle gradient
        context.fill(0, 0, width, height, 0xF0121418);

        MapArtManager manager = MapArtManager.get();
        MapArtSet set = manager.getActiveSet();
        Identifier texId = manager.getTextureId();

        if (manager.hasValidMapArt() && texId != null && set != null) {
            int texW = manager.getTextureWidth();
            int texH = manager.getTextureHeight();
            float drawW = texW * zoom;
            float drawH = texH * zoom;

            // Draw map shadow/border
            context.fill((int) panX - 2, (int) panY - 2, (int) (panX + drawW) + 2, (int) (panY + drawH) + 2, 0xFF2A2E38);

            // Draw stitched map texture
            context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    texId,
                    (int) panX, (int) panY,
                    0.0f, 0.0f,
                    (int) drawW, (int) drawH,
                    texW, texH,
                    texW, texH
            );

            // Grid lines
            if (showGrid) {
                float tileW = 128 * zoom;
                float tileH = 128 * zoom;

                for (int r = 0; r <= set.getRows(); r++) {
                    int yPos = (int) (panY + r * tileH);
                    context.fill((int) panX, yPos, (int) (panX + drawW), yPos + 1, 0x66FFFFFF);
                }

                for (int c = 0; c <= set.getCols(); c++) {
                    int xPos = (int) (panX + c * tileW);
                    context.fill(xPos, (int) panY, xPos + 1, (int) (panY + drawH), 0x66FFFFFF);
                }

                // Tile coordinates overlay if zoomed in enough
                if (zoom >= 0.4f) {
                    for (int r = 0; r < set.getRows(); r++) {
                        for (int c = 0; c < set.getCols(); c++) {
                            int cx = (int) (panX + c * tileW + 4);
                            int cy = (int) (panY + r * tileH + 4);
                            MapTile tile = set.getTile(r, c);
                            int color = (tile != null) ? 0xDDFFFFFF : 0xFFFF5555;
                            context.drawTextWithShadow(textRenderer, "[" + r + "," + c + "]", cx, cy, color);
                        }
                    }
                }
            }

            // Top Header Card
            renderHeaderCard(context, set, texW, texH);

            // Mouse hover inspector tooltip
            if (mouseX >= panX && mouseX < panX + drawW && mouseY >= panY && mouseY < panY + drawH) {
                float tileW = 128 * zoom;
                float tileH = 128 * zoom;
                int hCol = (int) ((mouseX - panX) / tileW);
                int hRow = (int) ((mouseY - panY) / tileH);

                if (hRow >= 0 && hRow < set.getRows() && hCol >= 0 && hCol < set.getCols()) {
                    renderTileTooltip(context, set, hRow, hCol, mouseX, mouseY);
                }
            }
        } else {
            context.drawCenteredTextWithShadow(textRenderer, "No map bundles found in player inventory.", width / 2, height / 2, 0xFFAAAAAA);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderHeaderCard(DrawContext context, MapArtSet set, int texW, int texH) {
        int cardW = Math.min(width - 24, 480);
        int cardX = (width - cardW) / 2;
        int cardY = 8;
        int cardH = 34;

        // Background
        context.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xDD1E2128);
        context.drawStrokedRectangle(cardX, cardY, cardW, cardH, 0xFF353945);

        // Title
        String title = set.getProjectId();
        MapArtManager mgr = MapArtManager.get();
        if (mgr.getProjectCount() > 1) {
            title += " (" + (mgr.getCurrentProjectIndex() + 1) + "/" + mgr.getProjectCount() + ")";
        }
        context.drawTextWithShadow(textRenderer, title, cardX + 10, cardY + 6, 0xFFFFFFFF);

        // Subtitle: Dimensions and pixel resolution
        String sub = set.getCols() + "x" + set.getRows() + " (" + set.getTotalExpected() + " maps • " + texW + "x" + texH + " px)";
        if (mgr.getProjectCount() > 1) {
            sub += " • Arrow keys to switch";
        }
        context.drawTextWithShadow(textRenderer, sub, cardX + 10, cardY + 18, 0xFFAAAAAA);

        // Status badge
        boolean complete = set.isComplete();
        String status = complete ? "[100% COMPLETE]" : "[" + set.getPresentCount() + "/" + set.getTotalExpected() + " - " + set.getCompletionPercentage() + "%]";
        int statusColor = complete ? 0xFF55FF55 : 0xFFFFAA00;
        int statusW = textRenderer.getWidth(status);
        context.drawTextWithShadow(textRenderer, status, cardX + cardW - statusW - 10, cardY + 6, statusColor);

        // Zoom text
        String zoomStr = "Zoom: " + Math.round(zoom * 100) + "%";
        int zoomW = textRenderer.getWidth(zoomStr);
        context.drawTextWithShadow(textRenderer, zoomStr, cardX + cardW - zoomW - 10, cardY + 18, 0xFF888888);
    }

    private void renderTileTooltip(DrawContext context, MapArtSet set, int row, int col, int mouseX, int mouseY) {
        MapTile tile = set.getTile(row, col);
        List<Text> lines = new ArrayList<>();

        if (tile != null) {
            lines.add(Text.literal("Tile: [" + row + ", " + col + "]").formatted(Formatting.AQUA, Formatting.BOLD));
            lines.add(Text.literal("Name: " + tile.originalName()).formatted(Formatting.WHITE));
            if (tile.mapId() != null) {
                lines.add(Text.literal("Map ID: #" + tile.mapId().id()).formatted(Formatting.GRAY));
            }
            lines.add(Text.literal("Status: Stored in Inventory Bundle").formatted(Formatting.GREEN));
        } else {
            lines.add(Text.literal("Tile: [" + row + ", " + col + "]").formatted(Formatting.RED, Formatting.BOLD));
            lines.add(Text.literal("Status: MISSING FROM INVENTORY").formatted(Formatting.DARK_RED));
            lines.add(Text.literal("Place bundle with piece in inventory").formatted(Formatting.GRAY));
        }

        context.drawTooltip(textRenderer, lines, mouseX + 8, mouseY + 8);
    }
}
