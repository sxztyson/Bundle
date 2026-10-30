package dev.mapbundlecollector.mixin;

import dev.mapbundlecollector.mapart.MapArtManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The server only sends map data for maps it considers "visible" (held, in an item frame,
// or explicitly previewed) - never for maps sitting inside bundles. This mixin taps every
// map packet the client does receive, whatever its source, so the manager can persist those
// colors by map id and render bundled tiles that were seen at least once.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMapItemData", at = @At("TAIL"))
    private void onMapItemData(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        MapItemSavedData state = level.getMapData(packet.mapId());
        if (state == null || state.colors == null) return;
        MapArtManager.get().onMapDataReceived(packet.mapId().id(), state.colors);
    }
}
