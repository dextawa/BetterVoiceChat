package top.dext.bvc.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else if >=1.20 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
//? if <1.19 {
/*import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
*///?}
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
    //?} else if >=1.20 {
    /*public void render(GuiGraphics graphics) {
    *///?} else {
    /*public void render(PoseStack graphics) {
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
            drawBoxed(graphics, font, literal("▶ ").append(speaking.name), 4, top, color);
            top += font.lineHeight + 3;
        }
    }

    private static final class Line {
        final Component text;
        final int color;

        Line(Component text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static Line stateLine(ClientStatus status) {
        switch (status.phase) {
            case CONNECTING:
                return new Line(tr("hud.bettervoicechat.connecting"), GRAY);
            case RECONNECTING:
                return new Line(tr("hud.bettervoicechat.reconnecting"), YELLOW);
            case INCOMPATIBLE:
                return new Line(tr("hud.bettervoicechat.incompatible"), RED);
            default:
                break;
        }
        ProxyToClient.MuteStatus mute = status.mute;
        if (mute != null && mute.muted) {
            Component text = tr("hud.bettervoicechat.muted");
            if (mute.expiresAt > 0) {
                long minutes = Math.max(1, (mute.expiresAt - System.currentTimeMillis() + 59_999) / 60_000);
                text = tr("hud.bettervoicechat.muted_for", minutes);
            }
            return new Line(text, RED);
        }
        if (!status.inputAvailable) {
            return new Line(tr("hud.bettervoicechat.no_microphone"), RED);
        }
        if (status.deafened) {
            return new Line(tr("hud.bettervoicechat.deafened"), RED);
        }
        if (status.microphoneMuted) {
            return new Line(tr("hud.bettervoicechat.mic_off"), GRAY);
        }
        if (status.voice != null && !status.voice.canSpeak) {
            return new Line(tr("hud.bettervoicechat.cannot_speak"), GRAY);
        }
        if (status.talking) {
            return new Line(tr("hud.bettervoicechat.talking"), GREEN);
        }
        return new Line(tr(status.activation == ActivationMode.VOICE
                ? "hud.bettervoicechat.listening_voice" : "hud.bettervoicechat.ready"), WHITE);
    }

    private static Component zoneText(ClientStatus status) {
        ProxyToClient.VoiceState state = status.voice;
        Component mode;
        switch (state.mode) {
            case PROXIMITY:
                mode = tr("hud.bettervoicechat.mode.proximity", (int) state.radius);
                break;
            case TEAM:
                mode = tr("hud.bettervoicechat.mode.team");
                break;
            case FREE:
                mode = tr("hud.bettervoicechat.mode.free");
                break;
            case TEAM_PROXIMITY:
                mode = tr("hud.bettervoicechat.mode.team_proximity", (int) state.radius);
                break;
            default:
                mode = tr("hud.bettervoicechat.mode.disabled");
                break;
        }
        Component activation = tr("hud.bettervoicechat.activation." + status.activation.name().toLowerCase());
        return literal("").append(mode).append(" · ").append(activation);
    }

    //? if >=26 {
    private static void drawBoxed(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
    //?} else if >=1.20 {
    /*private static void drawBoxed(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
    *///?} else {
    /*private static void drawBoxed(PoseStack graphics, Font font, Component text, int x, int y, int color) {
    *///?}
        int width = font.width(text);
        //? if >=1.20 {
        graphics.fill(x - 2, y - 2, x + width + 2, y + font.lineHeight, BACKGROUND);
        //?} else {
        /*GuiComponent.fill(graphics, x - 2, y - 2, x + width + 2, y + font.lineHeight, BACKGROUND);
        *///?}
        //? if >=26 {
        graphics.text(font, text, x, y, color, false);
        //?} else if >=1.20 {
        /*graphics.drawString(font, text, x, y, color, false);
        *///?} else {
        /*font.draw(graphics, text, x, y, color);
        *///?}
    }

    // Text components: 1.19 replaced TextComponent / TranslatableComponent with factory methods.

    static MutableComponent literal(String text) {
        //? if >=1.19 {
        return literal(text);
        //?} else {
        /*return new TextComponent(text);
        *///?}
    }

    static MutableComponent tr(String key, Object... args) {
        //? if >=1.19 {
        return tr(key, args);
        //?} else {
        /*return new TranslatableComponent(key, args);
        *///?}
    }
}
