package top.dext.bvc.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.api.MuteScope;
import top.dext.bvc.api.proxy.BvcProxy;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.data.TransportPolicy;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToBackend;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.session.VoiceSession;

class BvcProxyCoreTest {

    @TempDir
    Path data;

    private BvcProxyCore core;
    private FakePlatform platform;

    private void start(String configYaml) throws Exception {
        if (configYaml != null) {
            Files.writeString(data.resolve("config.yml"), configYaml);
        }
        platform = new FakePlatform(data);
        core = new BvcProxyCore(platform, new MutableClock(System.currentTimeMillis()));
        core.enable();
    }

    @AfterEach
    void stop() {
        if (core != null) {
            core.disable();
        }
    }

    private FakePlayer joinServer(String name, String server) {
        FakePlayer player = platform.add(name);
        core.onPlayerJoin(player);
        player.server = server;
        core.onServerConnected(player, server);
        return player;
    }

    private void fromClient(FakePlayer player, Packet<ClientToProxy.Handler> packet) {
        core.onClientMessage(player, ClientToProxy.REGISTRY.encode(packet));
    }

    private static ClientToProxy.Hello hello(int protocol) {
        return new ClientToProxy.Hello(protocol, "0.1.0", "26.3", "fabric", Transport.STREAM.bit() | Transport.PLUGIN_MESSAGE.bit());
    }

    @Test
    void writesDefaultFilesAndRegistersApi() throws Exception {
        start(null);
        assertTrue(Files.exists(data.resolve("config.yml")));
        assertTrue(Files.exists(data.resolve("messages.yml")));
        assertTrue(BvcProxy.isAvailable());
        core.disable();
        core = null;
        assertFalse(BvcProxy.isAvailable());
    }

    @Test
    void helloGetsWelcomeAndPluginMessageTransportConnects() throws Exception {
        start(null);
        FakePlayer alice = joinServer("Alice", "lobby");
        assertTrue(alice.toClient.isEmpty(), "nothing is sent to clients without the mod");
        ProxyToBackend.BindPlayers bind = (ProxyToBackend.BindPlayers) alice.toBackend.get(1);
        assertFalse(bind.entries.get(0).voiceEnabled);

        fromClient(alice, hello(BvcProtocol.VERSION));
        ProxyToClient.Welcome welcome = alice.received(ProxyToClient.Welcome.class).get(0);
        VoiceSession session = core.sessions().get(alice.uuid);
        assertEquals(session.id(), welcome.sessionId);
        assertEquals(TransportPolicy.AUTO, welcome.transportPolicy);
        assertEquals(BvcProtocol.TOKEN_LENGTH, welcome.token.length);
        assertEquals(VoiceSession.State.HANDSHAKING, session.state());

        alice.clear();
        fromClient(alice, new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE));
        assertEquals(VoiceSession.State.CONNECTED, session.state());
        assertEquals(Transport.PLUGIN_MESSAGE, alice.received(ProxyToClient.TransportAck.class).get(0).transport);
        assertEquals("lobby", alice.received(ProxyToClient.VoiceState.class).get(0).server);
        assertFalse(alice.received(ProxyToClient.MuteStatus.class).get(0).muted);
        ProxyToBackend.BindPlayers rebind = (ProxyToBackend.BindPlayers) alice.toBackend.get(0);
        assertTrue(rebind.entries.get(0).voiceEnabled, "backend learns the player now has voice");

        alice.clear();
        fromClient(alice, new ClientToProxy.Ping(42));
        assertEquals(42L, alice.received(ProxyToClient.Pong.class).get(0).id);
    }

    @Test
    void incompatibleModGetsANotice() throws Exception {
        start(null);
        FakePlayer alice = joinServer("Alice", "lobby");
        fromClient(alice, hello(BvcProtocol.VERSION + 1));
        assertEquals(VoiceSession.State.INCOMPATIBLE, core.sessions().get(alice.uuid).state());
        assertEquals(1, alice.received(ProxyToClient.Notice.class).size());
        assertTrue(alice.received(ProxyToClient.Welcome.class).isEmpty());
    }

    @Test
    void pluginMessagesCanBeForbidden() throws Exception {
        start("transport:\n  policy: STREAM_ONLY\n");
        FakePlayer alice = joinServer("Alice", "lobby");
        fromClient(alice, hello(BvcProtocol.VERSION));
        fromClient(alice, new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE));
        assertEquals(VoiceSession.State.HANDSHAKING, core.sessions().get(alice.uuid).state());
        assertEquals(TransportPolicy.STREAM_ONLY, alice.received(ProxyToClient.Welcome.class).get(0).transportPolicy);
    }

    @Test
    void requireModKicksOnlyWhenConfiguredAndNotExempt() throws Exception {
        start("client:\n  require-mod: true\n  require-mod-exempt-servers: [auth]\n");
        FakePlayer noMod = joinServer("NoMod", "lobby");
        FakePlayer withMod = joinServer("WithMod", "lobby");
        fromClient(withMod, hello(BvcProtocol.VERSION));
        FakePlayer inAuth = joinServer("InAuth", "auth");
        FakePlayer bypass = joinServer("Staff", "lobby");
        bypass.permissions.add("bvc.bypass.requiremod");

        platform.runDelayed();

        assertNotNull(noMod.disconnectedWith);
        assertNull(withMod.disconnectedWith);
        assertNull(inAuth.disconnectedWith);
        assertNull(bypass.disconnectedWith);
    }

    @Test
    void withoutRequireModNobodyIsKicked() throws Exception {
        start(null);
        FakePlayer noMod = joinServer("NoMod", "lobby");
        platform.runDelayed();
        assertNull(noMod.disconnectedWith);
    }

    @Test
    void bedrockPlayersAreRecognised() {
        assertTrue(BvcProxyCore.isBedrock(new UUID(0, 12345)));
        assertFalse(BvcProxyCore.isBedrock(UUID.randomUUID()));
    }

    @Test
    void mutingNotifiesTheOnlinePlayer() throws Exception {
        start(null);
        FakePlayer alice = joinServer("Alice", "lobby");
        fromClient(alice, hello(BvcProtocol.VERSION));
        fromClient(alice, new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE));
        alice.clear();

        core.mutes().mute(alice.uuid, "Alice", MuteScope.global(), 0, "spam", "Console");

        List<ProxyToClient.MuteStatus> statuses = alice.received(ProxyToClient.MuteStatus.class);
        assertEquals(1, statuses.size());
        assertTrue(statuses.get(0).muted);
        assertEquals(1, alice.chat.size());
        assertTrue(alice.chat.get(0).contains("spam"));
    }

    @Test
    void muteCommandResolvesPlayersAndScopes() throws Exception {
        start(null);
        FakePlayer alice = joinServer("Alice", "lobby");
        core.command().execute(platform.console(), new String[] {"mute", "alice", "bedwars", "30m", "too", "loud"});

        var mute = core.mutes().activeMute(alice.uuid, "bedwars").orElseThrow();
        assertEquals("too loud", mute.reason());
        assertEquals("bedwars", mute.scope().server());
        assertFalse(mute.isPermanent());
        assertTrue(core.mutes().activeMute(alice.uuid, "lobby").isEmpty());

        core.command().execute(platform.console(), new String[] {"unmute", "Alice", "bedwars"});
        assertTrue(core.mutes().activeMute(alice.uuid, "bedwars").isEmpty());

        core.command().execute(platform.console(), new String[] {"servermute", "BEDWARS", "on"});
        assertTrue(core.mutes().isServerMuted("bedwars"));
        assertEquals(List.of("mute"), core.command().suggest(platform.console(), new String[] {"mu"}));
        assertEquals(List.of("bedwars"), core.command().suggest(platform.console(), new String[] {"mute", "Alice", "b"}));
    }

    @Test
    void quitTellsListenersToForgetTheSpeaker() throws Exception {
        start(null);
        FakePlayer alice = joinServer("Alice", "lobby");
        FakePlayer bob = joinServer("Bob", "lobby");
        for (FakePlayer player : List.of(alice, bob)) {
            fromClient(player, hello(BvcProtocol.VERSION));
            fromClient(player, new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE));
        }
        VoiceSession aliceSession = core.sessions().get(alice.uuid);
        core.sessions().get(bob.uuid).learnSpeaker(aliceSession.id());
        bob.clear();

        core.onPlayerQuit(alice);

        assertNull(core.sessions().get(alice.uuid));
        assertEquals(aliceSession.id(), bob.received(ProxyToClient.PlayerRemove.class).get(0).speakerIds[0]);
    }
}
