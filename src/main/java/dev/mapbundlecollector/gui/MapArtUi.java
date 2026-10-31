package dev.mapbundlecollector.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

// Null-safe wrappers for the chat + UI-click-sound patterns that used to be inlined (with
// identical try/catch blocks) all over the GUI and module code. Callers supply the "[Map Art]"
// prefix and color codes in the message itself.
public final class MapArtUi {
    private MapArtUi() {}

    public static void chat(String message) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.gui == null || mc.gui.hud == null) return;
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
        } catch (Exception ignored) {
            // Never let feedback break the caller.
        }
    }

    public static void clickSound(float pitch) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                SoundEvents.UI_BUTTON_CLICK, pitch));
        } catch (Exception ignored) {
            // Sounds are best-effort.
        }
    }
}
