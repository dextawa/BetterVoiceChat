package top.dext.bvc.mod.forge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import io.netty.buffer.Unpooled;
import top.dext.bvc.mod.BvcModClient;
import top.dext.bvc.mod.BvcPayload;
//? if >=1.19 {
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//?} else {
/*import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?}
//? if >=1.17 {
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
//?} else {
/*import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
*///?}
//? if <1.20 {
/*import net.minecraftforge.fml.ModLoadingContext;
*///?}
//? if >=1.17 && <1.20 {
/*import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
*///?}
//? if <1.17 {
/*import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
*///?} else if <1.19 {
/*import net.minecraftforge.client.ClientRegistry;
*///?}

/**
 * Forge entry point: only wires loader events to {@link BvcModClient}.
 */
@Mod(BvcModClient.MOD_ID)
public final class BvcForge {

    public BvcForge() {
        // Client-only: never mark servers as incompatible in the server list (newer Forge reads
        // displayTest from mods.toml instead).
        //? if <1.17 {
        /*ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (version, fromServer) -> true));
        *///?} else if <1.20 {
        /*ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (version, fromServer) -> true));
        *///?}
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return; // client-only mod; nothing to do on a dedicated server
        }
        BvcModClient client = BvcModClient.init("forge", payload -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(payload.data().length));
            payload.write(buf);
            Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(BvcPayload.ID, buf));
        });

        // Servers without BVC (and vanilla servers) never announce a version: accept missing.
        EventNetworkChannel channel = NetworkRegistry.newEventChannel(BvcPayload.ID, () -> "1",
                NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));
        channel.addListener((NetworkEvent.ServerCustomPayloadEvent event) -> {
            // Called on the network thread; the buffer is released afterwards.
            BvcPayload payload = BvcPayload.read(event.getPayload());
            NetworkEvent.Context context = event.getSource().get();
            context.enqueueWork(() -> client.onPayload(payload));
            context.setPacketHandled(true);
        });

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        //? if >=1.19 {
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping key : client.createKeys(BvcModClient.KEY_CATEGORY)) {
                event.register(key);
            }
        });
        modBus.addListener((RegisterGuiOverlaysEvent event) -> event.registerAboveAll("hud",
                (gui, graphics, delta, width, height) -> client.hud().render(graphics)));
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.onJoin(event.getConnection()));
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.onDisconnect());
        //?} else {
        /*modBus.addListener((FMLClientSetupEvent event) -> {
            for (KeyMapping key : client.createKeys(BvcModClient.KEY_CATEGORY)) {
                ClientRegistry.registerKeyBinding(key);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post event) -> {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                client.hud().render(event.getMatrixStack());
            }
        });
        //? if >=1.17 {
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedInEvent event) -> client.onJoin(event.getConnection()));
        //?} else {
        /^MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedInEvent event) -> client.onJoin(event.getNetworkManager()));
        ^///?}
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedOutEvent event) -> client.onDisconnect());
        *///?}
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                client.onTick(Minecraft.getInstance());
            }
        });
    }
}
