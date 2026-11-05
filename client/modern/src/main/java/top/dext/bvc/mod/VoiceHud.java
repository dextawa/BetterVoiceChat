package top.dext.bvc.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.network.chat.Component;
import top.dext.bvc.client.ActivationMode;
import top.dext.bvc.client.ClientStatus;
import top.dext.bvc.client.VoiceClient;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.packet.ProxyToClient;

/**
 * Small overlay: own microphone state and zone at the bottom left, who is talking at the top
 * left. Shows only what the server decided; players cannot change mode or team here.
 */
public final class VoiceHud {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GOLD = 0xFFFFAA00;
    private static final int BACKGROUND = 0x80000000;
    private static final int MAX_SPEAKERS = 8;

    private final VoiceClient voice;

    VoiceHud(VoiceClient voice) {
        this.voice = voice;
    }

    //? if >=26 {
    public void render(GuiGraphicsExtractor graphics) {
    //?} else {
    /*public void render(GuiGraphics graphics) {
    *///?}
        Minecraft mc = Minecraft.getInstance();
        // F1 (hidden HUD) is handled by the game: HUD layers are not drawn at all then.
        if (!voice.settings().hudEnabled()) {
            return;
        }
        ClientStatus status = voice.status();
        if (status.phase == ClientStatus.Phase.IDLE || status.phase == ClientStatus.Phase.UNAVAILABLE) {
            return;
        }
        Font font = mc.font;
        int height = mc.getWindow().getGuiScaledHeight();
        int y = height - 38 - font.lineHeight * 2;

        Line state = stateLine(status);
        drawBoxed(graphics, font, state.text, 4, y, state.color);
        if (status.isConnected() && status.voice != null) {
            drawBoxed(graphics, font, zoneText(status), 4, y + font.lineHeight + 3, GRAY);
        }

        int top = 4;
        int shown = 0;
        for (ClientStatus.Speaking speaking : status.speaking) {
            if (shown++ == MAX_SPEAKERS) {
                break;
            }
            int color = speaking.category == AudioCategory.BROADCAST ? GOLD
                    : speaking.category == AudioCategory.GROUP ? GREEN : WHITE;
            drawBoxed(graphics, font, Component.literal("▶ ").append(speaking.name), 4, top, color);
            top += font.lineHeight + 3;
        }
    }

    private record Line(Component text, int color) {
    }

    private static Line stateLine(ClientStatus status) {
        switch (status.phase) {
            case CONNECTING:
                return new Line(Component.translatable("hud.bettervoicechat.connecting"), GRAY);
            case RECONNECTING:
                return new Line(Component.translatable("hud.bettervoicechat.reconnecting"), YELLOW);
            case INCOMPATIBLE:
                return new Line(Component.translatable("hud.bettervoicechat.incompatible"), RED);
            default:
                break;
        }
        ProxyToClient.MuteStatus mute = status.mute;
        if (mute != null && mute.muted) {
            Component text = Component.translatable("hud.bettervoicechat.muted");
            if (mute.expiresAt > 0) {
                long minutes = Math.max(1, (mute.expiresAt - System.currentTimeMillis() + 59_999) / 60_000);
                text = Component.translatable("hud.bettervoicechat.muted_for", minutes);
            }
            return new Line(text, RED);
        }
        if (!status.inputAvailable) {
            return new Line(Component.translatable("hud.bettervoicechat.no_microphone"), RED);
        }
        if (status.deafened) {
            return new Line(Component.translatable("hud.bettervoicechat.deafened"), RED);
        }
        if (status.microphoneMuted) {
            return new Line(Component.translatable("hud.bettervoicechat.mic_off"), GRAY);
        }
        if (status.voice != null && !status.voice.canSpeak) {
            return new Line(Component.translatable("hud.bettervoicechat.cannot_speak"), GRAY);
        }
        if (status.talking) {
            return new Line(Component.translatable("hud.bettervoicechat.talking"), GREEN);
        }
        return new Line(Component.translatable(status.activation == ActivationMode.VOICE
                ? "hud.bettervoicechat.listening_voice" : "hud.bettervoicechat.ready"), WHITE);
    }

    private static Component zoneText(ClientStatus status) {
        ProxyToClient.VoiceState state = status.voice;
        Component mode = switch (state.mode) {
            case PROXIMITY -> Component.translatable("hud.bettervoicechat.mode.proximity", (int) state.radius);
            case TEAM -> Component.translatable("hud.bettervoicechat.mode.team");
            case FREE -> Component.translatable("hud.bettervoicechat.mode.free");
            case TEAM_PROXIMITY -> Component.translatable("hud.bettervoicechat.mode.team_proximity", (int) state.radius);
            default -> Component.translatable("hud.bettervoicechat.mode.disabled");
        };
        Component activation = Component.translatable("hud.bettervoicechat.activation." + status.activation.name().toLowerCase());
        return Component.empty().append(mode).append(" · ").append(activation);
    }

    //? if >=26 {
    private static void drawBoxed(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
    //?} else {
    /*private static void drawBoxed(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
    *///?}
        int width = font.width(text);
        graphics.fill(x - 2, y - 2, x + width + 2, y + font.lineHeight, BACKGROUND);
        //? if >=26 {
        graphics.text(font, text, x, y, color, false);
        //?} else {
        /*graphics.drawString(font, text, x, y, color, false);
        *///?}
    }
}
