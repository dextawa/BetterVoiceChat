package top.dext.bvc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.MuteKind;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.ProxyToClient;

class TalkRulesTest {

    private static ProxyToClient.VoiceState state(VoiceMode mode, Activation activation, boolean canSpeak, boolean canBroadcast) {
        return new ProxyToClient.VoiceState("lobby", "", mode, activation, 48, 16000, 12, 0, canSpeak, canBroadcast);
    }

    private static final ProxyToClient.MuteStatus UNMUTED = ProxyToClient.MuteStatus.unmuted();

    @Test
    void pushToTalkOnlyWhileHeld() {
        ProxyToClient.VoiceState s = state(VoiceMode.PROXIMITY, Activation.ANY, true, false);
        assertEquals(1, VoiceClient.decide(s, UNMUTED, false, ActivationMode.PUSH_TO_TALK, true, false, false, false));
        assertEquals(0, VoiceClient.decide(s, UNMUTED, false, ActivationMode.PUSH_TO_TALK, false, false, true, false));
    }

    @Test
    void voiceAndToggleModes() {
        ProxyToClient.VoiceState s = state(VoiceMode.FREE, Activation.ANY, true, false);
        assertEquals(1, VoiceClient.decide(s, UNMUTED, false, ActivationMode.VOICE, false, false, true, false));
        assertEquals(0, VoiceClient.decide(s, UNMUTED, false, ActivationMode.VOICE, true, false, false, false));
        assertEquals(1, VoiceClient.decide(s, UNMUTED, false, ActivationMode.TOGGLE, false, true, false, false));
    }

    @Test
    void serverRulesAlwaysWin() {
        ProxyToClient.MuteStatus muted = new ProxyToClient.MuteStatus(true, MuteKind.PLAYER, "*", "", 0);
        ProxyToClient.VoiceState ok = state(VoiceMode.PROXIMITY, Activation.ANY, true, true);
        assertEquals(0, VoiceClient.decide(ok, muted, false, ActivationMode.PUSH_TO_TALK, true, false, false, true), "muted by server");
        assertEquals(0, VoiceClient.decide(state(VoiceMode.PROXIMITY, Activation.ANY, false, false), UNMUTED, false,
                ActivationMode.PUSH_TO_TALK, true, false, false, false), "server says cannot speak");
        assertEquals(0, VoiceClient.decide(state(VoiceMode.DISABLED, Activation.ANY, true, false), UNMUTED, false,
                ActivationMode.PUSH_TO_TALK, true, false, false, false), "voice disabled here");
        assertEquals(0, VoiceClient.decide(ok, UNMUTED, true, ActivationMode.PUSH_TO_TALK, true, false, false, false), "own mic muted");
        assertEquals(0, VoiceClient.decide(null, UNMUTED, false, ActivationMode.PUSH_TO_TALK, true, false, false, false), "no state yet");
    }

    @Test
    void broadcastNeedsServerPermission() {
        assertEquals(2, VoiceClient.decide(state(VoiceMode.PROXIMITY, Activation.ANY, true, true), UNMUTED, false,
                ActivationMode.PUSH_TO_TALK, false, false, false, true));
        assertEquals(0, VoiceClient.decide(state(VoiceMode.PROXIMITY, Activation.ANY, true, false), UNMUTED, false,
                ActivationMode.PUSH_TO_TALK, false, false, false, true));
    }

    @Test
    void settingsRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("bettervoicechat.properties");
        ClientSettings settings = ClientSettings.load(file);
        UUID loud = UUID.randomUUID();
        UUID annoying = UUID.randomUUID();
        settings.setActivation(ActivationMode.VOICE);
        settings.setVoiceThresholdDb(-38f);
        settings.setInputDevice("USB Mic");
        settings.setPlayerVolume(loud, 0.4f);
        settings.setPlayerMuted(annoying, true);
        settings.save();

        ClientSettings loaded = ClientSettings.load(file);
        assertEquals(ActivationMode.VOICE, loaded.activation());
        assertEquals(-38f, loaded.voiceThresholdDb());
        assertEquals("USB Mic", loaded.inputDevice());
        assertEquals(0.4f, loaded.playerVolume(loud));
        assertTrue(loaded.isPlayerMuted(annoying));
        assertEquals(0f, loaded.playerVolume(annoying));
        assertFalse(loaded.isPlayerMuted(loud));
        assertEquals(1f, loaded.playerVolume(UUID.randomUUID()));
    }
}
