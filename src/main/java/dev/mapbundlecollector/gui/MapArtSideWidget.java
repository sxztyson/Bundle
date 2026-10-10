package dev.mapbundlecollector.gui;

import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import dev.mapbundlecollector.modules.MapArtViewerModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.navigation.GuiNavigation;
import net.minecraft.client.gui.navigation.GuiNavigationPath;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class MapArtSideWidget implements Drawable, Element, Selectable {
    private final Screen parentScreen;
    private int screenX;
    private int screenY;
    private int backgroundWidth;
    private int backgroundHeight;

    private int widgetX;
    private int widgetY;
    private int widgetWidth;
    private int widgetHeight;

    private int btnX;
    private int btnY;
    private int btnW;
    private int btnH;

    private int loadedBtnX, loadedBtnY, loadedBtnW, loadedBtnH;
    private int topLoadedX, topLoadedY, topLoadedW, topLoadedH;

    private int clearBtnX, clearBtnY, clearBtnW, clearBtnH;
    private int topClearX, topClearY, topClearW, topClearH;

    private float previewZoom = 1.0f;
    private float previewPanX = 0f;
    private float previewPanY = 0f;

    private int lastMapBoxX, lastMapBoxY, lastMapBoxW, lastMapBoxH;
    private int zoomInX, zoomInY, zoomInW, zoomInH;
    private int zoomOutX, zoomOutY, zoomOutW, zoomOutH;
    private int zoomResetX, zoomResetY, zoomResetW, zoomResetH;

    private int prevX, prevY, prevW, prevH;
    private int nextX, nextY, nextW, nextH;

    private boolean isTopBadgeMode = false;
    private boolean focused = false;

    public MapArtSideWidget(Screen parentScreen) {
        this.parentScreen = parentScreen;
    }

    public void updateBounds(int screenX, int screenY, int backgroundWidth, int backgroundHeight) {
        this.screenX = screenX;
        this.screenY = screenY;
        this.backgroundWidth = backgroundWidth;
        this.backgroundHeight = backgroundHeight;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module == null || !module.isActive() || !module.sidePanel.get()) return;

        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return;

        MapArtSet set = manager.getActiveSet();
        Identifier texId = manager.getTextureId();
        if (set == null || texId == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer textRenderer = mc.textRenderer;

        // Calculate available space on both sides
        int spaceRight = parentScreen.width - (screenX + backgroundWidth);
        int spaceLeft = screenX;

        // Aspect ratio of the map (cols x rows)
        int texW = manager.getTextureWidth();
        int texH = manager.getTextureHeight();
        float aspect = (float) texH / (float) Math.max(1, texW);

        MapArtViewerModule.DisplayMode dMode = module.displayMode.get();
        boolean forceTop = (dMode == MapArtViewerModule.DisplayMode.TopBadge);
        boolean smartUseTop = (dMode == MapArtViewerModule.DisplayMode.Smart && spaceRight < 95);

        int projectCount = manager.getProjectCount();
        int currentIndex = manager.getCurrentProjectIndex() + 1;

        if (forceTop || smartUseTop) {
            // Render as an elegant Top Banner above the inventory GUI - never overlaps slots!
            isTopBadgeMode = true;
            widgetX = screenX;
            widgetY = Math.max(2, screenY - 24);
            widgetWidth = backgroundWidth;
            widgetHeight = 22;

            btnX = widgetX;
            btnY = widgetY;
            btnW = widgetWidth;
            btnH = widgetHeight;

            boolean hover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            int bg = hover ? 0xEE273B5E : 0xF0151820;
            int border = hover ? 0xFF5C94F0 : 0xFF2D323E;

            context.fill(btnX, btnY, btnX + btnW, btnY + btnH, bg);
            context.drawStrokedRectangle(btnX, btnY, btnW, btnH, border);

            // Title
            String title = set.getProjectId();
            if (textRenderer.getWidth(title) > 70) {
                title = textRenderer.trimToWidth(title, 65) + "..";
            }
            context.drawTextWithShadow(textRenderer, "🗺 " + title, btnX + 5, btnY + 7, 0xFFFFFFFF);

            // Dimension (Cols x Rows)
            String sub = set.getCols() + "x" + set.getRows();
            int subColor = set.isComplete() ? 0xFF55FF55 : 0xFFFFAA00;
            context.drawTextWithShadow(textRenderer, sub, btnX + 80, btnY + 7, subColor);

            // Arrow buttons if multiple maps exist
            if (projectCount > 1) {
                prevW = 12; prevH = 12;
                prevX = btnX + 112;
                prevY = btnY + 5;

                nextW = 12; nextH = 12;
                nextX = btnX + 148;
                nextY = btnY + 5;

                boolean prevHov = mouseX >= prevX && mouseX < prevX + prevW && mouseY >= prevY && mouseY < prevY + prevH;
                boolean nextHov = mouseX >= nextX && mouseX < nextX + nextW && mouseY >= nextY && mouseY < nextY + nextH;

                context.fill(prevX, prevY, prevX + prevW, prevY + prevH, prevHov ? 0xFF4A6DA7 : 0xFF2D3648);
                context.fill(nextX, nextY, nextX + nextW, nextY + nextH, nextHov ? 0xFF4A6DA7 : 0xFF2D3648);

                context.drawCenteredTextWithShadow(textRenderer, "◀", prevX + prevW / 2, prevY + 2, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(textRenderer, currentIndex + "/" + projectCount, prevX + 24, prevY + 2, 0xFFAAAAAA);
                context.drawCenteredTextWithShadow(textRenderer, "▶", nextX + nextW / 2, nextY + 2, 0xFFFFFFFF);
            }

            // Render button prompt
            String action = "[Render ↗]";
            int actionW = textRenderer.getWidth(action);
            int actionX = btnX + btnW - actionW - 5;
            boolean actionHover = mouseX >= actionX && mouseX < actionX + actionW && mouseY >= btnY + 4 && mouseY < btnY + 18;
            context.drawTextWithShadow(textRenderer, action, actionX, btnY + 7, actionHover ? 0xFFFFFFFF : 0xFF6CA8FF);

            // Loaded toggle prompt
            boolean isKeepLoaded = module.keepLoaded.get();
            String loadedStr = isKeepLoaded ? "[Loaded: ON]" : "[Loaded: OFF]";
            int loadedStrW = textRenderer.getWidth(loadedStr);
            topLoadedX = actionX - loadedStrW - 6;
            topLoadedY = btnY + 4;
            topLoadedW = loadedStrW + 4;
            topLoadedH = 14;

            boolean topLoadedHov = mouseX >= topLoadedX && mouseX < topLoadedX + topLoadedW && mouseY >= topLoadedY && mouseY < topLoadedY + topLoadedH;
            int loadedColor = isKeepLoaded ? (topLoadedHov ? 0xFF80FF80 : 0xFF55FF55) : (topLoadedHov ? 0xFFCCCCCC : 0xFF888888);
            context.drawTextWithShadow(textRenderer, loadedStr, topLoadedX + 2, btnY + 7, loadedColor);

            // Clear prompt
            String clearStr = "[Clear]";
            int clearStrW = textRenderer.getWidth(clearStr);
            topClearX = topLoadedX - clearStrW - 6;
            topClearY = btnY + 4;
            topClearW = clearStrW + 4;
            topClearH = 14;

            boolean topClearHov = mouseX >= topClearX && mouseX < topClearX + topClearW && mouseY >= topClearY && mouseY < topClearY + topClearH;
            context.drawTextWithShadow(textRenderer, clearStr, topClearX + 2, btnY + 7, topClearHov ? 0xFFFF7777 : 0xFFAA5555);

            if (topLoadedHov) {
                renderLoadedTooltip(context, textRenderer, isKeepLoaded, mouseX, mouseY);
            }
            return;
        }

        isTopBadgeMode = false;

        // Side Card Mode: Fit nicely into available space
        int maxAvailableWidth = Math.max(60, spaceRight - 10);
        int pSize = Math.min(module.previewSize.get(), maxAvailableWidth - 16);

        int renderMapW = pSize;
        int renderMapH = Math.round(pSize * aspect);

        // Clamp height so it fits within screen height
        int maxH = Math.max(60, parentScreen.height - screenY - 70);
        if (renderMapH > maxH) {
            renderMapH = maxH;
            renderMapW = Math.max(30, Math.round(maxH / aspect));
        }

        widgetWidth = Math.max(renderMapW + 16, 90);

        // Position: strictly adjacent to inventory GUI, never inside it
        if (module.position.get() == MapArtViewerModule.Position.Left && spaceLeft >= widgetWidth + 4) {
            widgetX = screenX - widgetWidth - 4;
        } else {
            widgetX = screenX + backgroundWidth + 4;
            if (widgetX + widgetWidth > parentScreen.width - 2) {
                widgetX = Math.max(screenX + backgroundWidth + 2, parentScreen.width - widgetWidth - 2);
            }
        }

        widgetY = Math.max(6, screenY);

        int totalBtnW = widgetWidth - 14;
        int gap = 3;
        btnW = (int) (totalBtnW * 0.52);
        btnX = widgetX + 7;
        btnH = 18;

        loadedBtnW = totalBtnW - btnW - gap;
        loadedBtnX = btnX + btnW + gap;
        loadedBtnH = 18;

        int headerH = 28;
        int missingInfoH = (!set.isComplete() && module.showMissing.get()) ? 14 : 0;
        widgetHeight = headerH + renderMapH + missingInfoH + btnH + 16;

        // Container card background
        context.fill(widgetX, widgetY, widgetX + widgetWidth, widgetY + widgetHeight, 0xF0151820);
        context.drawStrokedRectangle(widgetX, widgetY, widgetWidth, widgetHeight, 0xFF2D323E);

        // Header: Clear button [✖]
        clearBtnW = 14; clearBtnH = 14;
        clearBtnX = widgetX + widgetWidth - 18;
        clearBtnY = widgetY + 4;

        boolean clearHov = mouseX >= clearBtnX - 1 && mouseX < clearBtnX + clearBtnW + 1 && mouseY >= clearBtnY - 1 && mouseY < clearBtnY + clearBtnH + 1;
        context.fill(clearBtnX, clearBtnY, clearBtnX + clearBtnW, clearBtnY + clearBtnH, clearHov ? 0xFF992222 : 0xFF2A2E38);
        context.drawCenteredTextWithShadow(textRenderer, "✖", clearBtnX + clearBtnW / 2, clearBtnY + 3, clearHov ? 0xFFFFAAAA : 0xFF888888);

        // Header Title
        String rawTitle = set.getProjectId();
        int maxTitleWidth = (projectCount > 1) ? (widgetWidth - 62) : (widgetWidth - 24);
        if (textRenderer.getWidth(rawTitle) > maxTitleWidth) {
            rawTitle = textRenderer.trimToWidth(rawTitle, maxTitleWidth - 6) + "..";
        }
        context.drawTextWithShadow(textRenderer, rawTitle, widgetX + 7, widgetY + 6, 0xFFFFFFFF);

        // Multiple Maps: Arrow buttons in header
        if (projectCount > 1) {
            prevW = 12; prevH = 12;
            nextW = 12; nextH = 12;

            nextX = clearBtnX - 16;
            nextY = widgetY + 5;

            prevX = nextX - 32;
            prevY = widgetY + 5;

            boolean prevHov = mouseX >= prevX && mouseX < prevX + prevW && mouseY >= prevY && mouseY < prevY + prevH;
            boolean nextHov = mouseX >= nextX && mouseX < nextX + nextW && mouseY >= nextY && mouseY < nextY + nextH;

            context.fill(prevX, prevY, prevX + prevW, prevY + prevH, prevHov ? 0xFF4A6DA7 : 0xFF28303F);
            context.fill(nextX, nextY, nextX + nextW, nextY + nextH, nextHov ? 0xFF4A6DA7 : 0xFF28303F);

            context.drawCenteredTextWithShadow(textRenderer, "◀", prevX + prevW / 2, prevY + 2, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, currentIndex + "/" + projectCount, prevX + 16, prevY + 2, 0xFFAAAAAA);
            context.drawCenteredTextWithShadow(textRenderer, "▶", nextX + nextW / 2, nextY + 2, 0xFFFFFFFF);
        } else {
            prevW = 0; nextW = 0;
        }

        // Completion subtext: Cols x Rows (Width x Height)
        String sub = set.getCols() + "x" + set.getRows() + " • " + set.getPresentCount() + "/" + set.getTotalExpected();
        int subColor = set.isComplete() ? 0xFF55FF55 : 0xFFFFAA00;
        context.drawTextWithShadow(textRenderer, sub, widgetX + 7, widgetY + 17, subColor);

        // Stitched Map Preview Box
        int mapBoxX = widgetX + 7 + (widgetWidth - 14 - renderMapW) / 2;
        int mapBoxY = widgetY + headerH + 2;

        lastMapBoxX = mapBoxX;
        lastMapBoxY = mapBoxY;
        lastMapBoxW = renderMapW;
        lastMapBoxH = renderMapH;

        // Map frame border
        context.fill(mapBoxX - 1, mapBoxY - 1, mapBoxX + renderMapW + 1, mapBoxY + renderMapH + 1, 0xFF353945);

        // Zoomable clipped view
        context.enableScissor(mapBoxX, mapBoxY, mapBoxX + renderMapW, mapBoxY + renderMapH);

        int curDrawW = (int) (renderMapW * previewZoom);
        int curDrawH = (int) (renderMapH * previewZoom);
        int drawX = mapBoxX + (int) previewPanX;
        int drawY = mapBoxY + (int) previewPanY;

        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                texId,
                drawX, drawY,
                0.0f, 0.0f,
                curDrawW, curDrawH,
                texW, texH,
                texW, texH
        );

        context.disableScissor();

        // Overlay zoom controls in top-right corner of preview
        zoomInW = 11; zoomInH = 11;
        zoomInX = mapBoxX + renderMapW - 13;
        zoomInY = mapBoxY + 2;

        zoomResetW = 24; zoomResetH = 11;
        zoomResetX = zoomInX - 26;
        zoomResetY = mapBoxY + 2;

        zoomOutW = 11; zoomOutH = 11;
        zoomOutX = zoomResetX - 13;
        zoomOutY = mapBoxY + 2;

        boolean zInHov = mouseX >= zoomInX && mouseX < zoomInX + zoomInW && mouseY >= zoomInY && mouseY < zoomInY + zoomInH;
        boolean zOutHov = mouseX >= zoomOutX && mouseX < zoomOutX + zoomOutW && mouseY >= zoomOutY && mouseY < zoomOutY + zoomOutH;
        boolean zResHov = mouseX >= zoomResetX && mouseX < zoomResetX + zoomResetW && mouseY >= zoomResetY && mouseY < zoomResetY + zoomResetH;

        context.fill(zoomOutX - 2, zoomOutY - 1, zoomInX + zoomInW + 2, zoomInY + zoomInH + 1, 0xCC151820);

        context.fill(zoomOutX, zoomOutY, zoomOutX + zoomOutW, zoomOutY + zoomOutH, zOutHov ? 0xFF4A6DA7 : 0xFF282E3B);
        context.drawCenteredTextWithShadow(textRenderer, "-", zoomOutX + zoomOutW / 2, zoomOutY + 1, 0xFFFFFFFF);

        String zText = Math.round(previewZoom * 100) + "%";
        context.fill(zoomResetX, zoomResetY, zoomResetX + zoomResetW, zoomResetY + zoomResetH, zResHov ? 0xFF4A6DA7 : 0xFF202632);
        context.drawCenteredTextWithShadow(textRenderer, zText, zoomResetX + zoomResetW / 2, zoomResetY + 1, (previewZoom != 1.0f) ? 0xFF80FF80 : 0xFFAAAAAA);

        context.fill(zoomInX, zoomInY, zoomInX + zoomInW, zoomInY + zoomInH, zInHov ? 0xFF4A6DA7 : 0xFF282E3B);
        context.drawCenteredTextWithShadow(textRenderer, "+", zoomInX + zoomInW / 2, zoomInY + 1, 0xFFFFFFFF);

        if (clearHov) {
            context.drawTooltip(textRenderer, List.of(Text.literal("Clear loaded maps from cache").formatted(Formatting.RED)), mouseX, mouseY);
        }

        // Missing indicators text
        int currentY = mapBoxY + renderMapH + 4;
        if (!set.isComplete() && module.showMissing.get()) {
            List<int[]> missing = set.getMissingTiles();
            String missText = "Missing " + missing.size() + " pcs";
            if (!missing.isEmpty() && missing.size() <= 2) {
                StringBuilder sb = new StringBuilder("Miss: ");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("[").append(missing.get(i)[0]).append(",").append(missing.get(i)[1]).append("]");
                }
                missText = sb.toString();
            }
            context.drawTextWithShadow(textRenderer, missText, widgetX + 7, currentY, 0xFFFF6666);
            currentY += 14;
        }

        // "Render" button
        btnY = currentY;
        loadedBtnY = currentY;

        boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        int btnBg = btnHover ? 0xFF3D68B2 : 0xFF2A4373;
        int btnBorder = btnHover ? 0xFF5C94F0 : 0xFF3E60A0;

        context.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
        context.drawStrokedRectangle(btnX, btnY, btnW, btnH, btnBorder);

        String btnLabel = btnW >= 56 ? "Render" : "View";
        context.drawCenteredTextWithShadow(textRenderer, btnLabel, btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

        // "Loaded" button right next to Render!
        boolean isKeepLoaded = module.keepLoaded.get();
        boolean loadedHover = mouseX >= loadedBtnX && mouseX < loadedBtnX + loadedBtnW && mouseY >= loadedBtnY && mouseY < loadedBtnY + loadedBtnH;

        int lBg = isKeepLoaded ? (loadedHover ? 0xFF2B6B38 : 0xFF1D4D26) : (loadedHover ? 0xFF3C414D : 0xFF262932);
        int lBorder = isKeepLoaded ? (loadedHover ? 0xFF5FE072 : 0xFF2F8A43) : (loadedHover ? 0xFF6C7382 : 0xFF3D424E);

        context.fill(loadedBtnX, loadedBtnY, loadedBtnX + loadedBtnW, loadedBtnY + loadedBtnH, lBg);
        context.drawStrokedRectangle(loadedBtnX, loadedBtnY, loadedBtnW, loadedBtnH, lBorder);

        String loadedLabel = isKeepLoaded ? (loadedBtnW >= 54 ? "Loaded ✓" : "✓ Load") : (loadedBtnW >= 54 ? "Loaded ✕" : "✕ Load");
        int loadedTxtColor = isKeepLoaded ? 0xFF90FF90 : 0xFFAAAAAA;
        context.drawCenteredTextWithShadow(textRenderer, loadedLabel, loadedBtnX + loadedBtnW / 2, loadedBtnY + 5, loadedTxtColor);

        if (loadedHover) {
            renderLoadedTooltip(context, textRenderer, isKeepLoaded, mouseX, mouseY);
        }
    }

    private void renderLoadedTooltip(DrawContext context, TextRenderer textRenderer, boolean isKeepLoaded, int mouseX, int mouseY) {
        List<Text> tooltip = new ArrayList<>();
        if (isKeepLoaded) {
            tooltip.add(Text.literal("Keep Loaded: ON").formatted(Formatting.GREEN, Formatting.BOLD));
            tooltip.add(Text.literal("Maps stay in memory when bundles").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("are removed from your inventory.").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("Supports huge 50x50 maparts!").formatted(Formatting.YELLOW));
            tooltip.add(Text.literal("• Click: Toggle OFF").formatted(Formatting.WHITE));
            tooltip.add(Text.literal("• Shift-Click: Clear cache").formatted(Formatting.RED));
        } else {
            tooltip.add(Text.literal("Keep Loaded: OFF").formatted(Formatting.GRAY, Formatting.BOLD));
            tooltip.add(Text.literal("Only maps in current inventory are shown.").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("• Click: Turn ON").formatted(Formatting.GREEN));
        }
        context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module == null || !module.isActive() || !module.sidePanel.get()) return false;
        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return false;

        return mouseX >= widgetX && mouseX < widgetX + widgetWidth && mouseY >= widgetY && mouseY < widgetY + widgetHeight;
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module == null || !module.isActive() || !module.sidePanel.get()) return false;

        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        double mx = click.x();
        double my = click.y();
        boolean isShift = click.hasShift() || isShiftPressed() || click.button() != 0;

        // Arrow buttons click handling
        if (manager.getProjectCount() > 1) {
            if (mx >= prevX - 2 && mx < prevX + prevW + 2 && my >= prevY - 2 && my < prevY + prevH + 2) {
                manager.prevProject();
                try {
                    mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            }
            if (mx >= nextX - 2 && mx < nextX + nextW + 2 && my >= nextY - 2 && my < nextY + nextH + 2) {
                manager.nextProject();
                try {
                    mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            }
        }

        if (isTopBadgeMode) {
            // Top Badge: Clear button
            if (mx >= topClearX - 2 && mx < topClearX + topClearW + 2 && my >= topClearY - 2 && my < topClearY + topClearH + 2) {
                module.clearLoaded();
                return true;
            }
            // Top Badge: Loaded toggle / shift-clear
            if (mx >= topLoadedX && mx < topLoadedX + topLoadedW && my >= topLoadedY && my < topLoadedY + topLoadedH) {
                if (isShift) {
                    module.clearLoaded();
                } else {
                    module.toggleKeepLoaded();
                    if (mc.player != null) {
                        boolean state = module.keepLoaded.get();
                        mc.inGameHud.getChatHud().addMessage(Text.literal(
                            state ? "§a[Map Art]§r Keep Loaded: §aON§r (maps stay in memory)"
                                  : "§e[Map Art]§r Keep Loaded: §cOFF§r (live inventory only)"));
                        try {
                            mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                                net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
                        } catch (Exception ignored) {}
                    }
                }
                return true;
            }
            // Top Badge: Render button
            if (mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH) {
                mc.setScreen(new MapArtRenderScreen(parentScreen));
                return true;
            }
            return false;
        }

        // Side Card: Header Clear [✖] button
        if (mx >= clearBtnX - 2 && mx < clearBtnX + clearBtnW + 3 && my >= clearBtnY - 2 && my < clearBtnY + clearBtnH + 3) {
            module.clearLoaded();
            return true;
        }

        // Side Card: Mini Zoom buttons
        if (mx >= zoomInX && mx < zoomInX + zoomInW && my >= zoomInY && my < zoomInY + zoomInH) {
            previewZoom = Math.min(previewZoom + 0.25f, 5.0f);
            return true;
        }
        if (mx >= zoomOutX && mx < zoomOutX + zoomOutW && my >= zoomOutY && my < zoomOutY + zoomOutH) {
            previewZoom = Math.max(previewZoom - 0.25f, 0.5f);
            if (previewZoom <= 1.0f) {
                previewPanX = 0;
                previewPanY = 0;
            }
            return true;
        }
        if (mx >= zoomResetX && mx < zoomResetX + zoomResetW && my >= zoomResetY && my < zoomResetY + zoomResetH) {
            previewZoom = 1.0f;
            previewPanX = 0;
            previewPanY = 0;
            return true;
        }

        // Side Card: Loaded toggle button (Shift-click or Right-click clears cache!)
        if (mx >= loadedBtnX && mx < loadedBtnX + loadedBtnW && my >= loadedBtnY && my < loadedBtnY + loadedBtnH) {
            if (isShift) {
                module.clearLoaded();
            } else {
                module.toggleKeepLoaded();
                if (mc.player != null) {
                    boolean state = module.keepLoaded.get();
                    mc.inGameHud.getChatHud().addMessage(Text.literal(
                        state ? "§a[Map Art]§r Keep Loaded: §aON§r (maps stay in memory)"
                              : "§e[Map Art]§r Keep Loaded: §cOFF§r (live inventory only)"));
                    try {
                        mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                            net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
                    } catch (Exception ignored) {}
                }
            }
            return true;
        }

        // Side Card: Render button
        if (mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH) {
            mc.setScreen(new MapArtRenderScreen(parentScreen));
            return true;
        }

        // Double click on map box resets zoom, single click opens fullscreen viewer
        if (mx >= lastMapBoxX && mx < lastMapBoxX + lastMapBoxW && my >= lastMapBoxY && my < lastMapBoxY + lastMapBoxH) {
            if (doubled) {
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
                return true;
            }
            mc.setScreen(new MapArtRenderScreen(parentScreen));
            return true;
        }

        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= lastMapBoxX && mouseX < lastMapBoxX + lastMapBoxW && mouseY >= lastMapBoxY && mouseY < lastMapBoxY + lastMapBoxH) {
            if (verticalAmount > 0) {
                previewZoom = Math.min(previewZoom + 0.25f, 5.0f);
            } else if (verticalAmount < 0) {
                previewZoom = Math.max(previewZoom - 0.25f, 0.5f);
                if (Math.abs(previewZoom - 1.0f) < 0.15f) {
                    previewZoom = 1.0f;
                    previewPanX = 0;
                    previewPanY = 0;
                }
            }
            if (previewZoom <= 1.0f) {
                previewPanX = 0;
                previewPanY = 0;
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        double mx = click.x();
        double my = click.y();
        if (mx >= lastMapBoxX && mx < lastMapBoxX + lastMapBoxW && my >= lastMapBoxY && my < lastMapBoxY + lastMapBoxH) {
            if (previewZoom > 1.0f) {
                previewPanX += (float) offsetX;
                previewPanY += (float) offsetY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        int k = input.key();
        boolean isShift = input.hasShift() || isShiftPressed();
        boolean isCtrl = input.hasCtrl();

        boolean isLeftKey = input.isLeft() || k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_KP_4 || k == GLFW.GLFW_KEY_LEFT_BRACKET;
        boolean isRightKey = input.isRight() || k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_KP_6 || k == GLFW.GLFW_KEY_RIGHT_BRACKET;
        boolean isUpKey = input.isUp() || k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_KP_8 || k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD;
        boolean isDownKey = input.isDown() || k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_KP_2 || k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT;

        // Left Arrow: Switch project (when multiple), otherwise pan or notify
        if (isLeftKey) {
            if (manager.getProjectCount() > 1 && !isCtrl) {
                manager.prevProject();
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
                try {
                    mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            } else if (previewZoom > 1.0f) {
                previewPanX += 20f;
                return true;
            }
            return false;
        }

        // Right Arrow: Switch project (when multiple), otherwise pan or notify
        if (isRightKey) {
            if (manager.getProjectCount() > 1 && !isCtrl) {
                manager.nextProject();
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
                try {
                    mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            } else if (previewZoom > 1.0f) {
                previewPanX -= 20f;
                return true;
            }
            return false;
        }

        // Tab: Cycle projects when multiple exist
        if (k == GLFW.GLFW_KEY_TAB && manager.getProjectCount() > 1) {
            if (isShift) {
                manager.prevProject();
            } else {
                manager.nextProject();
            }
            previewZoom = 1.0f;
            previewPanX = 0;
            previewPanY = 0;
            return true;
        }

        // Up / Down Arrow: Zoom in / Zoom out on preview (or pan if Ctrl is held)
        if (isUpKey) {
            if (previewZoom > 1.0f && isCtrl) {
                previewPanY += 20f;
            } else {
                previewZoom = Math.min(previewZoom + 0.25f, 5.0f);
            }
            return true;
        }
        if (isDownKey) {
            if (previewZoom > 1.0f && isCtrl) {
                previewPanY -= 20f;
            } else {
                previewZoom = Math.max(previewZoom - 0.25f, 0.5f);
                if (previewZoom <= 1.05f && previewZoom >= 0.95f) {
                    previewZoom = 1.0f;
                    previewPanX = 0;
                    previewPanY = 0;
                }
            }
            return true;
        }

        return false;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public GuiNavigationPath getNavigationPath(GuiNavigation navigation) {
        return null;
    }

    @Override
    public SelectionType getType() {
        return focused ? SelectionType.FOCUSED : SelectionType.NONE;
    }

    @Override
    public void appendNarrations(NarrationMessageBuilder builder) {
    }

    private boolean isShiftPressed() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }
}
