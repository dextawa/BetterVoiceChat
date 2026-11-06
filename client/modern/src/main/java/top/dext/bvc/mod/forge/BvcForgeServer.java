package top.dext.bvc.mod.forge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.20.2 {
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
//?} else {
/*import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
*///?}
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
//? if >=1.16 {
import net.minecraftforge.event.RegisterCommandsEvent;
//?}
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import top.dext.bvc.mod.BvcPayload;
import top.dext.bvc.mod.server.BvcModServer;
//? if >=1.20.2 {
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
//?} else if >=1.18 {
/*import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
*///?} else if >=1.17 {
/*import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import net.minecraftforge.fmllegacy.network.NetworkEvent;
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
import net.minecraftforge.fmllegacy.network.event.EventNetworkChannel;
*///?} else {
/*import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
*///?}

/**
 * Forge dedicated-server side, started by {@link BvcForge} on dedicated servers: only wires
 * loader events to {@link BvcModServer}.
 */
final class BvcForgeServer {

    private BvcForgeServer() {
    }

    static void init() {
        // Players without the mod (and vanilla proxies) never announce a version: accept missing.
        //? if >=1.20.2 {
        EventNetworkChannel proxy = channel(BvcPayload.PROXY_ID);
        proxy.addListener((CustomPayloadEvent event) -> {
            ServerPlayer player = event.getSource().getSender();
            if (player != null) {
                BvcModServer.onProxyMessage(player, BvcPayload.bytes(event.getPayload()));
            }
            event.getSource().setPacketHandled(true);
        });
        channel(BvcPayload.SERVER_ID);
        //?} else {
        /*EventNetworkChannel proxy = NetworkRegistry.newEventChannel(BvcPayload.PROXY_ID, () -> "1",
                acceptMissingOr1(), acceptMissingOr1());
        proxy.addListener((NetworkEvent.ClientCustomPayloadEvent event) -> {
            // Called on the network thread; the buffer is released afterwards.
            NetworkEvent.Context context = event.getSource().get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                BvcModServer.onProxyMessage(player, BvcPayload.bytes(event.getPayload()));
            }
            context.setPacketHandled(true);
        });
        NetworkRegistry.newEventChannel(BvcPayload.SERVER_ID, () -> "1",
                acceptMissingOr1(), acceptMissingOr1());
        *///?}

        //? if >=1.18 {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> BvcModServer.start(event.getServer(),
                FMLPaths.CONFIGDIR.get(), BvcForgeServer::send));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> BvcModServer.stop());
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener((FMLServerStartedEvent event) -> BvcModServer.start(event.getServer(),
                FMLPaths.CONFIGDIR.get(), BvcForgeServer::send));
        MinecraftForge.EVENT_BUS.addListener((FMLServerStoppingEvent event) -> BvcModServer.stop());
        *///?}
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                BvcModServer.onTick();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> BvcModServer.onJoin((ServerPlayer) event.getEntity()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> BvcModServer.onQuit((ServerPlayer) event.getEntity()));
        //? if >=1.16 {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> BvcModServer.registerCommand(event.getDispatcher()));
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.fml.event.server.FMLServerStartingEvent event) ->
                BvcModServer.registerCommand(event.getCommandDispatcher()));
        *///?}
    }

    //? if <1.20.2 {
    /*/^* Accepts peers without the channel (vanilla, no BVC) or with version 1. ^/
    static java.util.function.Predicate<String> acceptMissingOr1() {
        //? if >=1.16 {
        return NetworkRegistry.acceptMissingOr("1");
        //?} else {
        /^return version -> "1".equals(version) || NetworkRegistry.ABSENT.equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
        ^///?}
    }
    *///?}

    private static void send(ServerPlayer player, byte[] data) {
        //? if >=1.20.2 {
        player.connection.send(new ClientboundCustomPayloadPacket(new BvcPayload(BvcPayload.SERVER_ID, data)));
        //?} else {
        /*FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        player.connection.send(new ClientboundCustomPayloadPacket(BvcPayload.SERVER_ID, buf));
        *///?}
    }

    //? if >=1.20.2 {
    private static EventNetworkChannel channel(net.minecraft.resources.ResourceLocation id) {
        return ChannelBuilder.named(id).optional()
                .acceptedVersions(Channel.VersionTest.ACCEPT_MISSING.or(Channel.VersionTest.ACCEPT_VANILLA)
                        .or(Channel.VersionTest.exact(1)))
                .networkProtocolVersion(1).eventNetworkChannel();
    }
    //?}
}
