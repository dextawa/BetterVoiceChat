package top.dext.bvc.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.MuteKind;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.data.TransportPolicy;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.io.Buf;

class PacketRoundTripTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final byte[] OPUS = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE, 1, 2, 3};

    @Test
    void clientToProxy() {
        assertRoundTrips(ClientToProxy.REGISTRY, 7, Arrays.<Packet<ClientToProxy.Handler>>asList(
                new ClientToProxy.Hello(1, "0.1.0", "26.3", "fabric", Transport.STREAM.bit() | Transport.PLUGIN_MESSAGE.bit()),
                new ClientToProxy.Auth(17, new byte[91], new byte[32]),
                new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE),
                new ClientToProxy.MicAudio(123456, ClientToProxy.MicAudio.FLAG_BROADCAST, OPUS),
                new ClientToProxy.MicEnd(123457),
                new ClientToProxy.ClientStatus(true, false),
                new ClientToProxy.Ping(99L)));

        ClientToProxy.MicAudio mic = (ClientToProxy.MicAudio) ClientToProxy.REGISTRY.decode(
                ClientToProxy.REGISTRY.encode(new ClientToProxy.MicAudio(5, 1, OPUS)));
        assertEquals(true, mic.isBroadcast());
        assertArrayEquals(OPUS, mic.opus);
    }

    @Test
    void proxyToClient() {
        assertRoundTrips(ProxyToClient.REGISTRY, 11, Arrays.<Packet<ProxyToClient.Handler>>asList(
                new ProxyToClient.Welcome(1, 17, new byte[32], TransportPolicy.AUTO, "", 0, 5000),
                new ProxyToClient.AuthResult(true, new byte[91], ""),
                new ProxyToClient.TransportAck(Transport.STREAM),
                new ProxyToClient.VoiceState("lobby", "", VoiceMode.PROXIMITY, Activation.PTT_ONLY, 48f, 16000, 12f, 3, true, false),
                new ProxyToClient.VoiceAudio(17, 1000, AudioCategory.PROXIMITY, 0, OPUS),
                new ProxyToClient.VoiceEnd(17, 1001),
                new ProxyToClient.PlayerInfo(Collections.singletonList(new ProxyToClient.PlayerInfo.Entry(17, PLAYER, "Notch"))),
                new ProxyToClient.PlayerRemove(new int[] {17, 18}),
                new ProxyToClient.MuteStatus(true, MuteKind.SERVER_WIDE, "bedwars-1", "管理员开启了全服禁麦", 0L),
                new ProxyToClient.Pong(99L),
                new ProxyToClient.Notice("Hello")));

        ProxyToClient.VoiceState state = (ProxyToClient.VoiceState) ProxyToClient.REGISTRY.decode(ProxyToClient.REGISTRY.encode(
                new ProxyToClient.VoiceState("bw", "arena-2", VoiceMode.TEAM, Activation.ANY, 0f, 24000, 10f, 200, false, true)));
        assertEquals(VoiceMode.TEAM, state.mode);
        assertEquals(200, state.priority);
        assertEquals(false, state.canSpeak);
        assertEquals(true, state.canBroadcast);
    }

    @Test
    void backendToProxy() {
        assertRoundTrips(BackendToProxy.REGISTRY, 6, Arrays.<Packet<BackendToProxy.Handler>>asList(
                new BackendToProxy.BackendHello(1, "0.1.0", "Paper 26.3", 0x1234_5678_9ABCL),
                new BackendToProxy.Membership(1L, Arrays.asList(
                        new BackendToProxy.Membership.Entry(1, 1, 1),
                        new BackendToProxy.Membership.Entry(2, 1, 0))),
                new BackendToProxy.Neighbors(2L, Collections.singletonList(new BackendToProxy.Neighbors.Entry(1, new int[] {2, 3, 4}))),
                new BackendToProxy.PlayerFlags(3L, Collections.singletonList(
                        new BackendToProxy.PlayerFlags.Entry(1, 10, BackendToProxy.PlayerFlags.DEFAULT))),
                new BackendToProxy.ZoneInfo(Long.MAX_VALUE, Collections.singletonList(
                        new BackendToProxy.ZoneInfo.Entry(1, "", VoiceMode.FREE, Activation.ANY, 0f))),
                new BackendToProxy.MuteRequest(PLAYER, "", BackendToProxy.MuteRequest.UNMUTE, "", "Console")));
    }

    @Test
    void proxyToBackend() {
        assertRoundTrips(ProxyToBackend.REGISTRY, 4, Arrays.<Packet<ProxyToBackend.Handler>>asList(
                new ProxyToBackend.ProxyHello(1, "0.1.0", -5L),
                new ProxyToBackend.BindPlayers(Collections.singletonList(new ProxyToBackend.BindPlayers.Entry(PLAYER, 17, true))),
                new ProxyToBackend.UnbindPlayers(new int[] {17}),
                new ProxyToBackend.ResyncRequest()));
    }

    @Test
    void unknownIdIsRejected() {
        byte[] data = new Buf().writeVarInt(42).toByteArray();
        assertThrows(ProtocolException.class, () -> ClientToProxy.REGISTRY.decode(data));
    }

    @Test
    void trailingBytesAreIgnoredForForwardCompatibility() {
        byte[] data = ClientToProxy.REGISTRY.encode(new ClientToProxy.Ping(7L));
        byte[] extended = Arrays.copyOf(data, data.length + 4);
        assertEquals(7L, ((ClientToProxy.Ping) ClientToProxy.REGISTRY.decode(extended)).id);
    }

    /** Every id has a sample, and encode(decode(encode(p))) is byte-identical to encode(p). */
    private static <H> void assertRoundTrips(PacketRegistry<H> registry, int packetCount, List<Packet<H>> samples) {
        Set<Integer> covered = new HashSet<>();
        for (Packet<H> sample : samples) {
            byte[] encoded = registry.encode(sample);
            Packet<H> decoded = registry.decode(encoded);
            assertSame(sample.getClass(), decoded.getClass());
            assertArrayEquals(encoded, registry.encode(decoded), sample.getClass().getSimpleName());
            covered.add(registry.idOf(sample));
        }
        assertEquals(packetCount, covered.size(), registry.name() + " samples must cover every packet");
    }
}
