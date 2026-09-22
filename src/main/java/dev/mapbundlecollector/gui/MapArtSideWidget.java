package dev.mapbundlecollector.gui;

import dev.mapbundlecollector.mapart.MapArtManager;
import dev.mapbundlecollector.mapart.MapArtSet;
import dev.mapbundlecollector.modules.MapArtViewerModule;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class MapArtSideWidget implements Renderable, GuiEventListener, NarratableEntry {
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module == null || !module.isActive() || !module.sidePanel.get()) return;

        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return;

        MapArtSet set = manager.getActiveSet();
        if (set == null) return;

        Identifier texId = manager.getTextureId();
        if (texId == null) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        float aspect = (float) set.getRows() / (float) set.getCols();

        int spaceRight = parentScreen.width - (screenX + backgroundWidth);
        int spaceLeft = screenX;
        int spaceTop = screenY;

        MapArtViewerModule.DisplayMode displayMode = module.displayMode.get();
        boolean forceTop = (displayMode == MapArtViewerModule.DisplayMode.TopBadge);
        boolean forceSide = (displayMode == MapArtViewerModule.DisplayMode.SideCard);

        if (forceTop || (!forceSide && spaceRight < 90 && spaceLeft < 90)) {
            isTopBadgeMode = true;
            renderTopBadge(graphics, font, set, manager, module, mouseX, mouseY, spaceTop);
            return;
        }

        isTopBadgeMode = false;

        int maxAvailableWidth = Math.max(60, spaceRight - 10);
        int pSize = Math.min(module.previewSize.get(), maxAvailableWidth - 16);

        int renderMapW = pSize;
        int renderMapH = Math.round(pSize * aspect);

        int maxH = Math.max(60, parentScreen.height - screenY - 70);
        if (renderMapH > maxH) {
            renderMapH = maxH;
            renderMapW = Math.max(30, Math.round(maxH / aspect));
        }

        widgetWidth = Math.max(renderMapW + 16, 90);

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

        graphics.fill(widgetX, widgetY, widgetX + widgetWidth, widgetY + widgetHeight, 0xF0151820);
        graphics.outline(widgetX, widgetY, widgetWidth, widgetHeight, 0xFF2D323E);

        clearBtnW = 14; clearBtnH = 14;
        clearBtnX = widgetX + widgetWidth - 18;
        clearBtnY = widgetY + 4;

        boolean clearHov = mouseX >= clearBtnX - 1 && mouseX < clearBtnX + clearBtnW + 1 && mouseY >= clearBtnY - 1 && mouseY < clearBtnY + clearBtnH + 1;
        graphics.fill(clearBtnX, clearBtnY, clearBtnX + clearBtnW, clearBtnY + clearBtnH, clearHov ? 0xFF992222 : 0xFF2A2E38);
        graphics.centeredText(font, "✖", clearBtnX + clearBtnW / 2, clearBtnY + 3, clearHov ? 0xFFFFAAAA : 0xFF888888);

        String rawTitle = set.getProjectId();
        int projectCount = manager.getProjectCount();
        int currentIndex = manager.getCurrentProjectIndex() + 1;
        int maxTitleWidth = (projectCount > 1) ? (widgetWidth - 62) : (widgetWidth - 24);
        if (font.width(rawTitle) > maxTitleWidth) {
            rawTitle = font.plainSubstrByWidth(rawTitle, maxTitleWidth - 6) + "..";
        }
        graphics.text(font, rawTitle, widgetX + 7, widgetY + 6, 0xFFFFFFFF, true);

        if (projectCount > 1) {
            prevW = 12; prevH = 12;
            nextW = 12; nextH = 12;

            nextX = clearBtnX - 16;
            nextY = widgetY + 5;

            prevX = nextX - 32;
            prevY = widgetY + 5;

            boolean prevHov = mouseX >= prevX && mouseX < prevX + prevW && mouseY >= prevY && mouseY < prevY + prevH;
            boolean nextHov = mouseX >= nextX && mouseX < nextX + nextW && mouseY >= nextY && mouseY < nextY + nextH;

            graphics.fill(prevX, prevY, prevX + prevW, prevY + prevH, prevHov ? 0xFF4A6DA7 : 0xFF28303F);
            graphics.fill(nextX, nextY, nextX + nextW, nextY + nextH, nextHov ? 0xFF4A6DA7 : 0xFF28303F);

            graphics.centeredText(font, "◀", prevX + prevW / 2, prevY + 2, 0xFFFFFFFF);
            graphics.centeredText(font, currentIndex + "/" + projectCount, prevX + 16, prevY + 2, 0xFFAAAAAA);
            graphics.centeredText(font, "▶", nextX + nextW / 2, nextY + 2, 0xFFFFFFFF);
        } else {
            prevW = 0; nextW = 0;
        }

        String sub = set.getCols() + "x" + set.getRows() + " • " + set.getPresentCount() + "/" + set.getTotalExpected();
        int subColor = set.isComplete() ? 0xFF55FF55 : 0xFFFFAA00;
        graphics.text(font, sub, widgetX + 7, widgetY + 17, subColor, true);

        int mapBoxX = widgetX + 7 + (widgetWidth - 14 - renderMapW) / 2;
        int mapBoxY = widgetY + headerH + 2;

        lastMapBoxX = mapBoxX;
        lastMapBoxY = mapBoxY;
        lastMapBoxW = renderMapW;
        lastMapBoxH = renderMapH;

        graphics.fill(mapBoxX - 1, mapBoxY - 1, mapBoxX + renderMapW + 1, mapBoxY + renderMapH + 1, 0xFF353945);

        graphics.enableScissor(mapBoxX, mapBoxY, mapBoxX + renderMapW, mapBoxY + renderMapH);

        int curDrawW = (int) (renderMapW * previewZoom);
        int curDrawH = (int) (renderMapH * previewZoom);
        int drawX = mapBoxX + (int) previewPanX;
        int drawY = mapBoxY + (int) previewPanY;

        graphics.blit(texId, drawX, drawY, curDrawW, curDrawH, 0.0f, 0.0f, 1.0f, 1.0f);

        graphics.disableScissor();

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

        graphics.fill(zoomOutX - 2, zoomOutY - 1, zoomInX + zoomInW + 2, zoomInY + zoomInH + 1, 0xCC151820);

        graphics.fill(zoomOutX, zoomOutY, zoomOutX + zoomOutW, zoomOutY + zoomOutH, zOutHov ? 0xFF4A6DA7 : 0xFF282E3B);
        graphics.centeredText(font, "-", zoomOutX + zoomOutW / 2, zoomOutY + 1, 0xFFFFFFFF);

        String zText = Math.round(previewZoom * 100) + "%";
        graphics.fill(zoomResetX, zoomResetY, zoomResetX + zoomResetW, zoomResetY + zoomResetH, zResHov ? 0xFF4A6DA7 : 0xFF202632);
        graphics.centeredText(font, zText, zoomResetX + zoomResetW / 2, zoomResetY + 1, (previewZoom != 1.0f) ? 0xFF80FF80 : 0xFFAAAAAA);

        graphics.fill(zoomInX, zoomInY, zoomInX + zoomInW, zoomInY + zoomInH, zInHov ? 0xFF4A6DA7 : 0xFF282E3B);
        graphics.centeredText(font, "+", zoomInX + zoomInW / 2, zoomInY + 1, 0xFFFFFFFF);

        if (clearHov) {
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal("Clear loaded maps from cache").withStyle(ChatFormatting.RED)), mouseX, mouseY);
        }

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
            graphics.text(font, missText, widgetX + 7, currentY, 0xFFFF6666, true);
            currentY += 14;
        }

        btnY = currentY;
        loadedBtnY = currentY;

        boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        int btnBg = btnHover ? 0xFF3D68B2 : 0xFF2A4373;
        int btnBorder = btnHover ? 0xFF5C94F0 : 0xFF3E60A0;

        graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
        graphics.outline(btnX, btnY, btnW, btnH, btnBorder);

        String btnLabel = btnW >= 56 ? "Render" : "View";
        graphics.centeredText(font, btnLabel, btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

        boolean isKeepLoaded = module.keepLoaded.get();
        boolean loadedHover = mouseX >= loadedBtnX && mouseX < loadedBtnX + loadedBtnW && mouseY >= loadedBtnY && mouseY < loadedBtnY + loadedBtnH;

        int lBg = isKeepLoaded ? (loadedHover ? 0xFF2B6B38 : 0xFF1D4D26) : (loadedHover ? 0xFF3C414D : 0xFF262932);
        int lBorder = isKeepLoaded ? (loadedHover ? 0xFF5FE072 : 0xFF2F8A43) : (loadedHover ? 0xFF6C7382 : 0xFF3D424E);

        graphics.fill(loadedBtnX, loadedBtnY, loadedBtnX + loadedBtnW, loadedBtnY + loadedBtnH, lBg);
        graphics.outline(loadedBtnX, loadedBtnY, loadedBtnW, loadedBtnH, lBorder);

        String loadedLabel = isKeepLoaded ? (loadedBtnW >= 54 ? "Loaded ✓" : "✓ Load") : (loadedBtnW >= 54 ? "Loaded ✕" : "✕ Load");
        int loadedTxtColor = isKeepLoaded ? 0xFF90FF90 : 0xFFAAAAAA;
        graphics.centeredText(font, loadedLabel, loadedBtnX + loadedBtnW / 2, loadedBtnY + 5, loadedTxtColor);

        if (loadedHover) {
            renderLoadedTooltip(graphics, font, isKeepLoaded, mouseX, mouseY);
        }
    }

    private void renderTopBadge(GuiGraphicsExtractor graphics, Font font, MapArtSet set, MapArtManager manager, MapArtViewerModule module, int mouseX, int mouseY, int spaceTop) {
        String title = set.getProjectId();
        String countText = set.getCols() + "x" + set.getRows() + " (" + set.getPresentCount() + "/" + set.getTotalExpected() + ")";

        int projectCount = manager.getProjectCount();
        int currentIndex = manager.getCurrentProjectIndex() + 1;

        int arrowsW = (projectCount > 1) ? 52 : 0;
        int titleW = font.width(title);
        int countW = font.width(countText);
        btnW = 56;
        btnH = 16;
        topLoadedW = 52;
        topLoadedH = 16;
        topClearW = 14;
        topClearH = 14;

        widgetWidth = titleW + countW + arrowsW + btnW + topLoadedW + topClearW + 42;
        widgetHeight = 22;

        widgetX = screenX + (backgroundWidth - widgetWidth) / 2;
        widgetY = Math.max(2, screenY - widgetHeight - 3);

        graphics.fill(widgetX, widgetY, widgetX + widgetWidth, widgetY + widgetHeight, 0xF0151820);
        graphics.outline(widgetX, widgetY, widgetWidth, widgetHeight, 0xFF2D323E);

        int curX = widgetX + 6;

        topClearX = curX;
        topClearY = widgetY + 4;
        boolean clearHov = mouseX >= topClearX - 1 && mouseX < topClearX + topClearW + 1 && mouseY >= topClearY - 1 && mouseY < topClearY + topClearH + 1;
        graphics.fill(topClearX, topClearY, topClearX + topClearW, topClearY + topClearH, clearHov ? 0xFF992222 : 0xFF2A2E38);
        graphics.centeredText(font, "✖", topClearX + topClearW / 2, topClearY + 3, clearHov ? 0xFFFFAAAA : 0xFF888888);
        curX += topClearW + 5;

        graphics.text(font, title, curX, widgetY + 7, 0xFFFFFFFF, true);
        curX += titleW + 6;

        if (projectCount > 1) {
            prevW = 12; prevH = 12;
            nextW = 12; nextH = 12;
            prevX = curX;
            prevY = widgetY + 5;
            nextX = prevX + 36;
            nextY = widgetY + 5;

            boolean prevHov = mouseX >= prevX && mouseX < prevX + prevW && mouseY >= prevY && mouseY < prevY + prevH;
            boolean nextHov = mouseX >= nextX && mouseX < nextX + nextW && mouseY >= nextY && mouseY < nextY + nextH;

            graphics.fill(prevX, prevY, prevX + prevW, prevY + prevH, prevHov ? 0xFF4A6DA7 : 0xFF28303F);
            graphics.fill(nextX, nextY, nextX + nextW, nextY + nextH, nextHov ? 0xFF4A6DA7 : 0xFF28303F);

            graphics.centeredText(font, "◀", prevX + prevW / 2, prevY + 2, 0xFFFFFFFF);
            graphics.centeredText(font, currentIndex + "/" + projectCount, prevX + 18, prevY + 2, 0xFFAAAAAA);
            graphics.centeredText(font, "▶", nextX + nextW / 2, nextY + 2, 0xFFFFFFFF);

            curX += arrowsW + 6;
        }

        int countColor = set.isComplete() ? 0xFF55FF55 : 0xFFFFAA00;
        graphics.text(font, countText, curX, widgetY + 7, countColor, true);
        curX += countW + 8;

        btnX = curX;
        btnY = widgetY + 3;
        boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        graphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnHover ? 0xFF3D68B2 : 0xFF2A4373);
        graphics.outline(btnX, btnY, btnW, btnH, btnHover ? 0xFF5C94F0 : 0xFF3E60A0);
        graphics.centeredText(font, "Render", btnX + btnW / 2, btnY + 4, 0xFFFFFFFF);
        curX += btnW + 4;

        topLoadedX = curX;
        topLoadedY = widgetY + 3;
        boolean isKeepLoaded = module.keepLoaded.get();
        boolean loadedHover = mouseX >= topLoadedX && mouseX < topLoadedX + topLoadedW && mouseY >= topLoadedY && mouseY < topLoadedY + topLoadedH;

        int lBg = isKeepLoaded ? (loadedHover ? 0xFF2B6B38 : 0xFF1D4D26) : (loadedHover ? 0xFF3C414D : 0xFF262932);
        int lBorder = isKeepLoaded ? (loadedHover ? 0xFF5FE072 : 0xFF2F8A43) : (loadedHover ? 0xFF6C7382 : 0xFF3D424E);

        graphics.fill(topLoadedX, topLoadedY, topLoadedX + topLoadedW, topLoadedY + topLoadedH, lBg);
        graphics.outline(topLoadedX, topLoadedY, topLoadedW, topLoadedH, lBorder);

        String lText = isKeepLoaded ? "Loaded ✓" : "Loaded ✕";
        int lColor = isKeepLoaded ? 0xFF90FF90 : 0xFFAAAAAA;
        graphics.centeredText(font, lText, topLoadedX + topLoadedW / 2, topLoadedY + 4, lColor);

        if (loadedHover) {
            renderLoadedTooltip(graphics, font, isKeepLoaded, mouseX, mouseY);
        }
        if (clearHov) {
            graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal("Clear loaded maps from cache").withStyle(ChatFormatting.RED)), mouseX, mouseY);
        }
    }

    private void renderLoadedTooltip(GuiGraphicsExtractor graphics, Font font, boolean isKeepLoaded, int mouseX, int mouseY) {
        List<Component> tooltip = new ArrayList<>();
        if (isKeepLoaded) {
            tooltip.add(Component.literal("Keep Loaded: ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            tooltip.add(Component.literal("Maps stay in memory when bundles").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("are removed from your inventory.").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Supports huge 50x50 maparts!").withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("• Click: Toggle OFF").withStyle(ChatFormatting.WHITE));
            tooltip.add(Component.literal("• Shift-Click: Clear cache").withStyle(ChatFormatting.RED));
        } else {
            tooltip.add(Component.literal("Keep Loaded: OFF").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
            tooltip.add(Component.literal("Only maps in current inventory are shown.").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("• Click: Turn ON").withStyle(ChatFormatting.GREEN));
        }
        graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
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
    public boolean mouseReleased(MouseButtonEvent event) {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        MapArtViewerModule module = Modules.get().get(MapArtViewerModule.class);
        if (module == null || !module.isActive() || !module.sidePanel.get()) return false;

        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return false;

        Minecraft mc = Minecraft.getInstance();
        double mx = event.x();
        double my = event.y();
        boolean isShift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 || isShiftPressed() || event.button() != 0;

        if (manager.getProjectCount() > 1) {
            if (mx >= prevX - 2 && mx < prevX + prevW + 2 && my >= prevY - 2 && my < prevY + prevH + 2) {
                manager.prevProject();
                try {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            }
            if (mx >= nextX - 2 && mx < nextX + nextW + 2 && my >= nextY - 2 && my < nextY + nextH + 2) {
                manager.nextProject();
                try {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            }
        }

        if (isTopBadgeMode) {
            if (mx >= topClearX - 2 && mx < topClearX + topClearW + 2 && my >= topClearY - 2 && my < topClearY + topClearH + 2) {
                module.clearLoaded();
                return true;
            }
            if (mx >= topLoadedX && mx < topLoadedX + topLoadedW && my >= topLoadedY && my < topLoadedY + topLoadedH) {
                if (isShift) {
                    module.clearLoaded();
                } else {
                    module.toggleKeepLoaded();
                    if (mc.player != null) {
                        boolean state = module.keepLoaded.get();
                        ChatUtils.sendMsg("Map Art", Component.literal(
                            state ? "§aKeep Loaded: §aON§r (maps stay in memory)"
                                  : "§eKeep Loaded: §cOFF§r (live inventory only)"));
                        try {
                            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
                        } catch (Exception ignored) {}
                    }
                }
                return true;
            }
            if (mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH) {
                mc.gui.setScreen(new MapArtRenderScreen(parentScreen));
                return true;
            }
            return false;
        }

        if (mx >= clearBtnX - 2 && mx < clearBtnX + clearBtnW + 3 && my >= clearBtnY - 2 && my < clearBtnY + clearBtnH + 3) {
            module.clearLoaded();
            return true;
        }

        if (mx >= zoomInX && mx < zoomInX + zoomInW && my >= zoomInY && my < zoomInY + zoomInH) {
            previewZoom = Math.min(previewZoom + 0.25f, 5.0f);
            return true;
        }
        if (mx >= zoomOutX && mx < zoomOutX + zoomOutW && my >= zoomOutY && my < zoomOutY + zoomOutH) {
            previewZoom = Math.max(previewZoom - 0.25f, 0.5f);
            if (previewZoom <= 1.05f && previewZoom >= 0.95f) {
                previewZoom = 1.0f;
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

        if (mx >= loadedBtnX && mx < loadedBtnX + loadedBtnW && my >= loadedBtnY && my < loadedBtnY + loadedBtnH) {
            if (isShift) {
                module.clearLoaded();
            } else {
                module.toggleKeepLoaded();
                if (mc.player != null) {
                    boolean state = module.keepLoaded.get();
                    ChatUtils.sendMsg("Map Art", Component.literal(
                        state ? "§aKeep Loaded: §aON§r (maps stay in memory)"
                              : "§eKeep Loaded: §cOFF§r (live inventory only)"));
                    try {
                        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
                    } catch (Exception ignored) {}
                }
            }
            return true;
        }

        if (mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH) {
            mc.gui.setScreen(new MapArtRenderScreen(parentScreen));
            return true;
        }

        if (mx >= lastMapBoxX && mx < lastMapBoxX + lastMapBoxW && my >= lastMapBoxY && my < lastMapBoxY + lastMapBoxH) {
            mc.gui.setScreen(new MapArtRenderScreen(parentScreen));
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= lastMapBoxX && mouseX < lastMapBoxX + lastMapBoxW && mouseY >= lastMapBoxY && mouseY < lastMapBoxY + lastMapBoxH) {
            if (verticalAmount > 0) {
                previewZoom = Math.min(previewZoom + 0.25f, 5.0f);
            } else if (verticalAmount < 0) {
                previewZoom = Math.max(previewZoom - 0.25f, 0.5f);
            }
            if (previewZoom <= 1.05f && previewZoom >= 0.95f) {
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double offsetX, double offsetY) {
        double mx = event.x();
        double my = event.y();
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
    public boolean keyPressed(KeyEvent event) {
        MapArtManager manager = MapArtManager.get();
        if (!manager.hasValidMapArt()) return false;

        Minecraft mc = Minecraft.getInstance();
        int k = event.key();
        boolean isShift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 || isShiftPressed();
        boolean isCtrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;

        boolean isLeftKey = k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_KP_4 || k == GLFW.GLFW_KEY_LEFT_BRACKET;
        boolean isRightKey = k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_KP_6 || k == GLFW.GLFW_KEY_RIGHT_BRACKET;
        boolean isUpKey = k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_KP_8 || k == GLFW.GLFW_KEY_EQUAL || k == GLFW.GLFW_KEY_KP_ADD;
        boolean isDownKey = k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_KP_2 || k == GLFW.GLFW_KEY_MINUS || k == GLFW.GLFW_KEY_KP_SUBTRACT;

        if (isLeftKey) {
            if (manager.getProjectCount() > 1 && !isCtrl) {
                manager.prevProject();
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
                try {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            } else if (previewZoom > 1.0f) {
                previewPanX += 20f;
                return true;
            }
            return false;
        }

        if (isRightKey) {
            if (manager.getProjectCount() > 1 && !isCtrl) {
                manager.nextProject();
                previewZoom = 1.0f;
                previewPanX = 0;
                previewPanY = 0;
                try {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1f));
                } catch (Exception ignored) {}
                return true;
            } else if (previewZoom > 1.0f) {
                previewPanX -= 20f;
                return true;
            }
            return false;
        }

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
    public NarrationPriority narrationPriority() {
        return focused ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
    }

    private boolean isShiftPressed() {
        long handle = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
               GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }
}
