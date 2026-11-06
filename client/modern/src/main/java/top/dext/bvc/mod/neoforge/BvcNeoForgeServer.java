package top.dext.bvc.mod.neoforge;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.fml.loading.FMLPaths;
import top.dext.bvc.mod.BvcModClient;
import top.dext.bvc.mod.BvcPayload;
import top.dext.bvc.mod.server.BvcModServer;
//? if >=1.20.5 {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
//?} else {
/*import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
*///?}

/**
 * NeoForge dedicated-server side: only wires loader events to {@link BvcModServer}.
 */
//? if >=1.20.5 {
@Mod(value = BvcModClient.MOD_ID, dist = Dist.DEDICATED_SERVER)
//?}
public final class BvcNeoForgeServer {

    public BvcNeoForgeServer(IEventBus modBus) {
        //? if >=1.20.5 {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                // optional: players without the mod (and vanilla proxies) must still get in
                .optional()
                .playToServer(BvcPayload.PROXY_TYPE, BvcPayload.PROXY_CODEC,
                        (payload, context) -> BvcModServer.onProxyMessage((ServerPlayer) context.player(), payload.data()))
                .playToClient(BvcPayload.SERVER_TYPE, BvcPayload.SERVER_CODEC, (payload, context) -> { }));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> BvcModServer.onTick());
        //?} else {
        /*modBus.addListener((RegisterPayloadHandlerEvent event) -> event.registrar("bvc").versioned("1")
                .optional()
                .play(BvcPayload.PROXY_ID, BvcPayload::readProxy, (payload, context) -> context.player()
                        .ifPresent(player -> BvcModServer.onProxyMessage((ServerPlayer) player, payload.data())))
                .play(BvcPayload.SERVER_ID, BvcPayload::readServer, (payload, context) -> { }));
        NeoForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                BvcModServer.onTick();
            }
        });
        *///?}

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> BvcModServer.start(event.getServer(),
                FMLPaths.CONFIGDIR.get(), BvcNeoForgeServer::send));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> BvcModServer.stop());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> BvcModServer.onJoin((ServerPlayer) event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> BvcModServer.onQuit((ServerPlayer) event.getEntity()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> BvcModServer.registerCommand(event.getDispatcher()));
    }

    /** Plain vanilla packet: also reaches players whose client never negotiated the channel. */
    private static void send(ServerPlayer player, byte[] data) {
        //? if >=1.20.5 {
        BvcPayload payload = new BvcPayload(BvcPayload.SERVER_TYPE, data);
        //?} else {
        /*BvcPayload payload = new BvcPayload(BvcPayload.SERVER_ID, data);
        *///?}
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }
}
