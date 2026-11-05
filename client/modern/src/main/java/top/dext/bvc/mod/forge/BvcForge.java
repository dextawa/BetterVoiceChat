package top.dext.bvc.mod.forge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import io.netty.buffer.Unpooled;
import top.dext.bvc.mod.BvcModClient;
import top.dext.bvc.mod.BvcPayload;

/**
 * Forge entry point: only wires loader events to {@link BvcModClient}.
 */
@Mod(BvcModClient.MOD_ID)
public final class BvcForge {

    public BvcForge() {
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
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping key : client.createKeys(BvcModClient.KEY_CATEGORY)) {
                event.register(key);
            }
        });
        modBus.addListener((RegisterGuiOverlaysEvent event) -> event.registerAboveAll("hud",
                (gui, graphics, delta, width, height) -> client.hud().render(graphics)));

        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.onJoin(event.getConnection()));
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.onDisconnect());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                client.onTick(Minecraft.getInstance());
            }
        });
    }
}
