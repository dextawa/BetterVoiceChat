package top.dext.bvc.mod.neoforge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.dext.bvc.mod.BvcModClient;
import top.dext.bvc.mod.BvcPayload;
//? if >=26 {
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
//?} else if >=1.20.5 {
/*import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
*///?} else {
/*import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterGuiOverlaysEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
*///?}

/**
 * NeoForge entry point: only wires loader events to {@link BvcModClient}.
 */
//? if >=1.20.5 {
@Mod(value = BvcModClient.MOD_ID, dist = Dist.CLIENT)
//?} else {
/*@Mod(BvcModClient.MOD_ID)
*///?}
public final class BvcNeoForge {

    public BvcNeoForge(IEventBus modBus) {
        //? if >=26 {
        BvcModClient client = BvcModClient.init("neoforge", payload -> ClientPacketDistributor.sendToServer(payload));
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                // optional: servers without BVC (and vanilla servers) must still accept us
                .optional()
                .playBidirectional(BvcPayload.TYPE, BvcPayload.CODEC, (payload, context) -> client.onPayload(payload)));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            KeyMapping.Category category = new KeyMapping.Category(Identifier.fromNamespaceAndPath(BvcModClient.MOD_ID, "main"));
            event.registerCategory(category);
            for (KeyMapping key : client.createKeys(category)) {
                event.register(key);
            }
        });
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                Identifier.fromNamespaceAndPath(BvcModClient.MOD_ID, "hud"), (graphics, delta) -> client.hud().render(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> client.onTick(Minecraft.getInstance()));
        //?} else if >=1.20.5 {
        /*BvcModClient client = BvcModClient.init("neoforge", payload -> PacketDistributor.sendToServer(payload));
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                // optional: servers without BVC (and vanilla servers) must still accept us
                .optional()
                .playBidirectional(BvcPayload.TYPE, BvcPayload.CODEC, (payload, context) -> client.onPayload(payload)));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping key : client.createKeys(BvcModClient.KEY_CATEGORY)) {
                event.register(key);
            }
        });
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                new ResourceLocation(BvcModClient.MOD_ID, "hud"), (graphics, delta) -> client.hud().render(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> client.onTick(Minecraft.getInstance()));
        *///?} else {
        /*if (FMLEnvironment.dist != Dist.CLIENT) {
            return; // client-only mod; nothing to do on a dedicated server
        }
        BvcModClient client = BvcModClient.init("neoforge", payload -> PacketDistributor.SERVER.noArg().send(payload));
        modBus.addListener((RegisterPayloadHandlerEvent event) -> event.registrar("1")
                // optional: servers without BVC (and vanilla servers) must still accept us
                .optional()
                // handlers run on the network thread in 20.4
                .play(BvcPayload.ID, BvcPayload::read, (payload, context) -> context.workHandler().execute(() -> client.onPayload(payload))));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping key : client.createKeys(BvcModClient.KEY_CATEGORY)) {
                event.register(key);
            }
        });
        modBus.addListener((RegisterGuiOverlaysEvent event) -> event.registerAboveAll(
                new ResourceLocation(BvcModClient.MOD_ID, "hud"), (gui, graphics, delta, width, height) -> client.hud().render(graphics)));
        NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                client.onTick(Minecraft.getInstance());
            }
        });
        *///?}

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> client.onJoin(event.getConnection()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> client.onDisconnect());
    }
}
