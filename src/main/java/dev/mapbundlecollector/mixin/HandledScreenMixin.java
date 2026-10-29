package dev.mapbundlecollector.mixin;

import dev.mapbundlecollector.gui.MapArtSideWidget;
import dev.mapbundlecollector.mapart.MapArtManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Shadow
    protected int imageHeight;

    @Unique
    private MapArtSideWidget mapArtSideWidget;

    protected HandledScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (minecraft != null && minecraft.player != null) {
            MapArtManager.get().update(minecraft.player);
        }

        mapArtSideWidget = new MapArtSideWidget(this);
        mapArtSideWidget.updateBounds(leftPos, topPos, imageWidth, imageHeight);
        addRenderableWidget(mapArtSideWidget);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (mapArtSideWidget != null) {
            mapArtSideWidget.updateBounds(leftPos, topPos, imageWidth, imageHeight);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(net.minecraft.client.input.KeyEvent input, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (mapArtSideWidget != null && mapArtSideWidget.keyPressed(input)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (mapArtSideWidget != null && mapArtSideWidget.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDragged(net.minecraft.client.input.MouseButtonEvent click, double offsetX, double offsetY, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (mapArtSideWidget != null && mapArtSideWidget.mouseDragged(click, offsetX, offsetY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (mapArtSideWidget != null && mapArtSideWidget.mouseClicked(click, doubled)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void onMouseReleased(net.minecraft.client.input.MouseButtonEvent click, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (mapArtSideWidget != null && mapArtSideWidget.mouseReleased(click)) {
            cir.setReturnValue(true);
        }
    }
}
