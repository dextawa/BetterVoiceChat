package top.dext.bvc.mod.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.fabricmc.loader.api.FabricLoader;
import top.dext.bvc.mod.BvcModClient;
import top.dext.bvc.mod.BvcPayload;
//? if >=26 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}
//? if >=1.20.5 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
*///?}

/**
 * Fabric entry point: only wires loader events to {@link BvcModClient}.
 */
public final class BvcFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        //? if >=26 {
        PayloadTypeRegistry.clientboundPlay().register(BvcPayload.TYPE, BvcPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BvcPayload.TYPE, BvcPayload.CODEC);
        //?} else if >=1.20.5 {
        /*PayloadTypeRegistry.playS2C().register(BvcPayload.TYPE, BvcPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BvcPayload.TYPE, BvcPayload.CODEC);
        *///?}

        //? if >=1.20.5 {
        BvcModClient client = BvcModClient.init("fabric", FabricLoader.getInstance().getConfigDir(), ClientPlayNetworking::send);
        ClientPlayNetworking.registerGlobalReceiver(BvcPayload.TYPE, (payload, context) -> client.onPayload(payload));
        //?} else {
        /*BvcModClient client = BvcModClient.init("fabric", FabricLoader.getInstance().getConfigDir(), payload -> {
            FriendlyByteBuf buf = PacketByteBufs.create();
            payload.write(buf);
            ClientPlayNetworking.send(BvcPayload.ID, buf);
        });
        // Old channel API: called on the network thread, and the buffer is released afterwards.
        ClientPlayNetworking.registerGlobalReceiver(BvcPayload.ID, (mc, handler, buf, sender) -> {
            BvcPayload payload = BvcPayload.read(buf);
            mc.execute(() -> client.onPayload(payload));
        });
        *///?}

        //? if >=1.21.9 {
        KeyMapping[] keys = client.createKeys(KeyMapping.Category.register(BvcModClient.id("main")));
        //?} else {
        /*KeyMapping[] keys = client.createKeys(BvcModClient.KEY_CATEGORY);
        *///?}
        for (KeyMapping key : keys) {
            //? if >=26 {
            KeyMappingHelper.registerKeyMapping(key);
            //?} else {
            /*KeyBindingHelper.registerKeyBinding(key);
            *///?}
        }

        //? if >=1.21.6 {
        HudElementRegistry.addLast(BvcModClient.id("hud"), (graphics, delta) -> client.hud().render(graphics));
        //?} else {
        /*HudRenderCallback.EVENT.register((graphics, delta) -> client.hud().render(graphics));
        *///?}

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> client.onJoin(handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client::onTick);
    }
}
