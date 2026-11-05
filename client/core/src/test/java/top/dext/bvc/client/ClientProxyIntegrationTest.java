package top.dext.bvc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.dext.bvc.client.InProcessNetwork.await;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.api.MuteScope;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.data.VoiceMode;

/**
 * Two complete client cores talking through the complete proxy core: handshake, both
 * transports, Opus, mixing and server-side rules, without Minecraft.
 */
class ClientProxyIntegrationTest {

    /** Peak above this on the listener's speakers = they hear the tone. */
    private static final float HEARD = 2000;
    private static final float SILENT = 50;

    @TempDir
    Path data;

    private InProcessNetwork net;

    @AfterEach
    void close() {
        if (net != null) {
            net.close();
        }
    }

    private InProcessNetwork.Client[] connectedPair(String proxyConfig, Transport expected) throws Exception {
        net = new InProcessNetwork(data, proxyConfig);
        InProcessNetwork.Client alice = net.join("Alice");
        InProcessNetwork.Client bob = net.join("Bob");
        assertTrue(await(() -> alice.status().isConnected() && bob.status().isConnected(), 10_000),
                "both connect: " + alice.status().phase + "/" + bob.status().phase);
        assertEquals(expected, alice.status().transport);
        assertEquals(expected, bob.status().transport);
        net.backendHello();
        return new InProcessNetwork.Client[] {alice, bob};
    }

    private static boolean hears(InProcessNetwork.Client listener) {
        float[] peaks = listener.audio.recentPeaks(5);
        return Math.max(peaks[0], peaks[1]) > HEARD;
    }

    private static boolean silent(InProcessNetwork.Client listener) {
        float[] peaks = listener.audio.recentPeaks(10);
        return Math.max(peaks[0], peaks[1]) < SILENT;
    }

    @Test
    void talkingOverTheEncryptedStream() throws Exception {
        InProcessNetwork.Client[] pair = connectedPair(null, Transport.STREAM);
        InProcessNetwork.Client alice = pair[0];
        InProcessNetwork.Client bob = pair[1];
        net.zone(VoiceMode.FREE, 0, alice, bob);
        assertTrue(await(() -> alice.status().voice != null && alice.status().voice.mode == VoiceMode.FREE, 5_000),
                "zone from the sub-server reaches the client");

        alice.audio.speaking = true;
        alice.voice.setTalkKey(true);
        assertTrue(await(() -> hears(bob), 5_000), "Bob hears Alice");
        assertTrue(alice.status().talking);
        ClientStatus bobStatus = bob.status();
        assertEquals(1, bobStatus.speaking.size());
        assertEquals("Alice", bobStatus.speaking.get(0).name);
        assertEquals(AudioCategory.GROUP, bobStatus.speaking.get(0).category);

        alice.voice.setTalkKey(false);
        assertTrue(await(() -> silent(bob), 3_000), "releasing push-to-talk stops the audio");
        assertTrue(await(() -> !alice.status().talking, 1_000));
    }

    @Test
    void talkingOverPluginMessages() throws Exception {
        InProcessNetwork.Client[] pair = connectedPair("transport:\n  policy: PLUGIN_MESSAGE_ONLY\n", Transport.PLUGIN_MESSAGE);
        InProcessNetwork.Client alice = pair[0];
        InProcessNetwork.Client bob = pair[1];
        net.zone(VoiceMode.FREE, 0, alice, bob);
        assertTrue(await(() -> bob.status().voice != null, 5_000));

        bob.audio.speaking = true;
        bob.voice.setTalkKey(true);
        assertTrue(await(() -> hears(alice), 5_000), "Alice hears Bob over plugin messages");
    }

    @Test
    void serverMuteSilencesAndIsShown() throws Exception {
        InProcessNetwork.Client[] pair = connectedPair(null, Transport.STREAM);
        InProcessNetwork.Client alice = pair[0];
        InProcessNetwork.Client bob = pair[1];
        net.zone(VoiceMode.FREE, 0, alice, bob);
        assertTrue(await(() -> alice.status().voice != null, 5_000));
        alice.audio.speaking = true;
        alice.voice.setTalkKey(true);
        assertTrue(await(() -> hears(bob), 5_000));

        net.core.mutes().mute(alice.player.uuid, "Alice", MuteScope.global(), 0, "spam", "Console");
        assertTrue(await(() -> alice.status().mute.muted, 3_000), "client shows the mute");
        assertEquals("spam", alice.status().mute.reason);
        assertTrue(await(() -> silent(bob), 3_000), "muted player is not heard");
        assertTrue(await(() -> !alice.status().talking, 1_000), "client stops sending while muted");
    }

    @Test
    void proximityVoicesArePositioned() throws Exception {
        InProcessNetwork.Client[] pair = connectedPair(null, Transport.STREAM);
        InProcessNetwork.Client alice = pair[0];
        InProcessNetwork.Client bob = pair[1];
        net.zone(VoiceMode.PROXIMITY, 20, alice, bob);
        net.neighbors(alice, bob);
        net.neighbors(bob, alice);
        // Bob faces south at the origin; Alice stands 5 blocks east of him, i.e. on his left.
        bob.voice.positions().update(0, 64, 0, 0f, java.util.Map.of(alice.player.uuid, new double[] {5, 64, 0}));
        assertTrue(await(() -> alice.status().voice != null && alice.status().voice.mode == VoiceMode.PROXIMITY, 5_000));

        alice.audio.speaking = true;
        alice.voice.setTalkKey(true);
        assertTrue(await(() -> hears(bob), 5_000));
        Thread.sleep(300);
        float[] peaks = bob.audio.recentPeaks(10);
        assertTrue(peaks[0] > peaks[1] * 1.5f, "louder in the left ear: L=" + peaks[0] + " R=" + peaks[1]);
        assertEquals(AudioCategory.PROXIMITY, bob.status().speaking.get(0).category);
    }

    @Test
    void lostStreamReconnects() throws Exception {
        InProcessNetwork.Client[] pair = connectedPair(null, Transport.STREAM);
        InProcessNetwork.Client alice = pair[0];
        net.core.sessions().get(alice.player.uuid).stream().close();
        assertTrue(await(() -> alice.status().phase != ClientStatus.Phase.CONNECTED, 3_000), "client notices the drop");
        assertTrue(await(() -> alice.status().isConnected() && alice.status().transport == Transport.STREAM, 10_000),
                "client reconnects on its own: " + alice.status().phase);
    }

    @Test
    void serverWithoutBvcIsReportedUnavailable() throws Exception {
        net = new InProcessNetwork(data, null);
        InProcessNetwork.Client lonely = net.new Client("Lonely");
        // No proxy session: nobody answers the Hello.
        lonely.voice.onJoinServer(net.address, "localhost");
        assertTrue(await(() -> lonely.status().phase == ClientStatus.Phase.UNAVAILABLE, VoiceClient.WELCOME_TIMEOUT_MILLIS + 3_000));
        lonely.close();
    }
}
