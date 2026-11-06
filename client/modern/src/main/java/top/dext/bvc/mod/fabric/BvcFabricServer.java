package top.dext.bvc.mod.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import top.dext.bvc.mod.BvcPayload;
import top.dext.bvc.mod.server.BvcModServer;
//? if >=1.19 {
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//?} else {
/*import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
*///?}
//? if >=1.20.5 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
*///?}

/**
 * Fabric server side (both environments; {@link BvcModServer} ignores integrated servers):
 * only wires loader events to {@link BvcModServer}.
 */
public final class BvcFabricServer implements ModInitializer {

    @Override
    public void onInitialize() {
        //? if >=26 {
        PayloadTypeRegistry.serverboundPlay().register(BvcPayload.PROXY_TYPE, BvcPayload.PROXY_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BvcPayload.SERVER_TYPE, BvcPayload.SERVER_CODEC);
        //?} else if >=1.20.5 {
        /*PayloadTypeRegistry.playC2S().register(BvcPayload.PROXY_TYPE, BvcPayload.PROXY_CODEC);
        PayloadTypeRegistry.playS2C().register(BvcPayload.SERVER_TYPE, BvcPayload.SERVER_CODEC);
        *///?}

        //? if >=1.20.5 {
        ServerPlayNetworking.registerGlobalReceiver(BvcPayload.PROXY_TYPE,
                (payload, context) -> BvcModServer.onProxyMessage(context.player(), payload.data()));
        //?} else {
        /*// Old channel API: called on the network thread, and the buffer is released afterwards.
        ServerPlayNetworking.registerGlobalReceiver(BvcPayload.PROXY_ID,
                (server, player, handler, buf, responseSender) -> BvcModServer.onProxyMessage(player, BvcPayload.bytes(buf)));
        *///?}

        ServerLifecycleEvents.SERVER_STARTED.register(server -> BvcModServer.start(server,
                FabricLoader.getInstance().getConfigDir(), (player, data) -> {
                    //? if >=1.20.5 {
                    ServerPlayNetworking.send(player, new BvcPayload(BvcPayload.SERVER_TYPE, data));
                    //?} else {
                    /*FriendlyByteBuf buf = PacketByteBufs.create();
                    buf.writeBytes(data);
                    ServerPlayNetworking.send(player, BvcPayload.SERVER_ID, buf);
                    *///?}
                }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BvcModServer.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> BvcModServer.onTick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BvcModServer.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BvcModServer.onQuit(handler.player));

        //? if >=1.19 {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BvcModServer.registerCommand(dispatcher));
        //?} else {
        /*CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> BvcModServer.registerCommand(dispatcher));
        *///?}
    }
}
