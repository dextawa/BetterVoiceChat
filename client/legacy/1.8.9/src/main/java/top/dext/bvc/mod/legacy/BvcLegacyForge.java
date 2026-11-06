package top.dext.bvc.mod.legacy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.input.Keyboard;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

/**
 * Forge 1.8.9 entry point: wires Forge events and the {@code bvc:voice} channel to
 * {@link LegacyVoice}, and draws its HUD lines.
 */
@Mod(modid = LegacyVoice.MOD_ID, name = "BetterVoiceChat", useMetadata = true, clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public final class BvcLegacyForge implements LegacyVoice.Game {

    private LegacyVoice voice;
    private FMLEventChannel channel;
    private KeyBinding talkKey;
    private KeyBinding broadcastKey;
    private KeyBinding muteKey;
    private KeyBinding deafenKey;
    private KeyBinding settingsKey;
    private boolean devSettingsPending = Boolean.getBoolean("bvc.devSettings");
    private int devSettingsTicks;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        voice = new LegacyVoice(this, event.getModConfigurationDirectory());

        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(LegacyVoice.CHANNEL);
        channel.register(this);

        talkKey = key("key.bettervoicechat.talk", Keyboard.KEY_V);
        broadcastKey = key("key.bettervoicechat.broadcast", Keyboard.KEY_B);
        muteKey = key("key.bettervoicechat.mute", Keyboard.KEY_M);
        deafenKey = key("key.bettervoicechat.deafen", Keyboard.KEY_N);
        settingsKey = key("key.bettervoicechat.settings", Keyboard.KEY_K);

        FMLCommonHandler.instance().bus().register(this); // tick events
        MinecraftForge.EVENT_BUS.register(this); // HUD
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                voice.shutdown();
            }
        }, "BVC-Shutdown"));
    }

    private static KeyBinding key(String name, int code) {
        KeyBinding key = new KeyBinding(name, code, LegacyVoice.KEY_CATEGORY);
        ClientRegistry.registerKeyBinding(key);
        return key;
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        // Network thread; the buffer is released afterwards, so copy it. The core accepts any thread.
        ByteBuf buf = event.packet.payload();
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        voice.onPayload(data);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient handler = mc.getNetHandler();
        voice.updateConnection(handler, handler == null ? null : handler.getNetworkManager().getRemoteAddress(),
                mc.getCurrentServerData() == null ? "" : mc.getCurrentServerData().serverIP);

        voice.onKeys(talkKey.isKeyDown(), broadcastKey.isKeyDown(), presses(muteKey), presses(deafenKey));
        boolean devOpen = devSettingsPending && voice.voice().status().isConnected() && ++devSettingsTicks > 40;
        if (devOpen) {
            devSettingsPending = false; // dev runs only: -Dbvc.devSettings
        }
        if ((devOpen || presses(settingsKey) > 0) && mc.currentScreen == null) {
            mc.displayGuiScreen(new LegacySettingsScreen(voice.voice()));
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            voice.clearPositions();
            return;
        }
        Map<UUID, double[]> others = new HashMap<UUID, double[]>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player != mc.thePlayer) {
                others.put(player.getUniqueID(), new double[] {player.posX, player.posY + player.getEyeHeight(), player.posZ});
            }
        }
        voice.updatePositions(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw, others);
    }

    private static int presses(KeyBinding key) {
        int count = 0;
        while (key.isPressed()) {
            count++;
        }
        return count;
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        int height = new ScaledResolution(mc).getScaledHeight();
        List<LegacyVoice.Line> status = voice.statusLines();
        int y = height - 38 - font.FONT_HEIGHT * 2;
        for (LegacyVoice.Line line : status) {
            draw(font, line, y);
            y += font.FONT_HEIGHT + 3;
        }
        int top = 4;
        for (LegacyVoice.Line line : voice.speakerLines()) {
            draw(font, line, top);
            top += font.FONT_HEIGHT + 3;
        }
    }

    private static void draw(FontRenderer font, LegacyVoice.Line line, int y) {
        int x = 4;
        Gui.drawRect(x - 2, y - 2, x + font.getStringWidth(line.text) + 2, y + font.FONT_HEIGHT, LegacyVoice.BACKGROUND);
        font.drawString(line.text, x, y, line.color);
    }

    // ------------------------------------------------------------------ LegacyVoice.Game

    @Override
    public void sendPayload(byte[] data) {
        channel.sendToServer(new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(data)), LegacyVoice.CHANNEL));
    }

    @Override
    public boolean connected() {
        return Minecraft.getMinecraft().getNetHandler() != null;
    }

    @Override
    public void runOnGameThread(Runnable task) {
        Minecraft.getMinecraft().addScheduledTask(task);
    }

    @Override
    public String translate(String key, Object... args) {
        return I18n.format(key, args);
    }

    @Override
    public void chat(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(text));
        }
    }

    @Override
    public String minecraftVersion() {
        return "1.8.9";
    }

    @Override
    public int minecraftProtocol() {
        return 47;
    }
}
