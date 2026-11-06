package top.dext.bvc.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.io.Buf;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.ProxyToBackend;
import top.dext.bvc.proxy.routing.ServerRoutes;

/**
 * {@code security.backend-secret} and panel sessions that survive a restart.
 */
class SecurityTest {

    @TempDir
    Path data;

    private BvcProxyCore core;
    private FakePlatform platform;

    private void start(String yaml) throws Exception {
        Files.writeString(data.resolve("config.yml"), yaml + "\nweb:\n  port: 0\n");
        platform = new FakePlatform(data);
        core = new BvcProxyCore(platform);
        core.enable();
    }

    @AfterEach
    void stop() {
        if (core != null) {
            core.disable();
        }
    }

    private FakePlayer join(String name) {
        FakePlayer player = platform.add(name);
        core.onPlayerJoin(player);
        player.server = "lobby";
        core.onServerConnected(player, "lobby");
        return player;
    }

    private void fromBackend(FakePlayer via, top.dext.bvc.protocol.packet.Packet<BackendToProxy.Handler> packet) {
        core.onBackendMessage(via, "lobby", BackendToProxy.REGISTRY.encode(packet));
    }

    private static BackendToProxy.ZoneInfo freeZone(int id) {
        return new BackendToProxy.ZoneInfo(1, List.of(new BackendToProxy.ZoneInfo.Entry(id, "", VoiceMode.FREE, Activation.ANY, 0f)));
    }

    @Test
    void withASecretOnlyProvenSubServersAreHeard() throws Exception {
        start("security:\n  backend-secret: s3cret\n");
        FakePlayer alice = join("Alice");
        ProxyToBackend.ProxyHello hello = (ProxyToBackend.ProxyHello) alice.toBackend.get(0);
        assertTrue(BvcCrypto.verifyLinkProof("s3cret", "proxy", hello.instanceId, 0, hello.proof), "the proxy proves itself");
        int id = core.sessions().get(alice.uuid).id();

        // Wrong secret: the hello and the tables after it are ignored.
        fromBackend(alice, new BackendToProxy.BackendHello(BvcProtocol.VERSION, "x", "Fake", 7,
                BvcCrypto.linkProof("guess", "backend", hello.instanceId, 7)));
        fromBackend(alice, freeZone(id));
        ServerRoutes routes = core.routes().get("lobby");
        assertFalse(routes.backendPresent());

        // Right secret.
        fromBackend(alice, new BackendToProxy.BackendHello(BvcProtocol.VERSION, "x", "Paper", 8,
                BvcCrypto.linkProof("s3cret", "backend", hello.instanceId, 8)));
        fromBackend(alice, freeZone(id));
        assertTrue(routes.backendPresent());
        assertEquals(VoiceMode.FREE, routes.route(id).mode());
    }

    @Test
    void withoutASecretOldSubServersStillWork() throws Exception {
        start("");
        FakePlayer alice = join("Alice");
        assertEquals(0, ((ProxyToBackend.ProxyHello) alice.toBackend.get(0)).proof.length);
        // A hello without the proof field at all (an older sub-server plugin).
        Buf old = new Buf();
        old.writeVarInt(0).writeVarInt(BvcProtocol.VERSION).writeString("0.0.9").writeString("Paper").writeLong(3);
        core.onBackendMessage(alice, "lobby", old.toByteArray());
        assertTrue(core.routes().get("lobby").backendPresent());
    }

    @Test
    void panelSessionsSurviveARestart() throws Exception {
        start("");
        core.webAuth().issueLoginToken("staff", null, java.util.EnumSet.of(top.dext.bvc.proxy.web.WebAuth.Permission.REVIEW),
                System.currentTimeMillis()).get();
        core.webAuth().setPassword("staff", "longpassword").get();
        String session = core.webAuth().passwordLogin("staff", "longpassword", System.currentTimeMillis(), 3_600_000L).get()
                .orElseThrow();
        core.disable();

        core = new BvcProxyCore(platform);
        core.enable();
        assertEquals(Optional.of("staff"), core.webAuth().sessionUser(session, System.currentTimeMillis()));
        core.webAuth().logout(session);
        Thread.sleep(200);
        core.disable();
        core = new BvcProxyCore(platform);
        core.enable();
        assertTrue(core.webAuth().sessionUser(session, System.currentTimeMillis()).isEmpty());
        assertTrue(core.webAuth().sessionUser(session, System.currentTimeMillis() + 7_200_000L).isEmpty());
    }
}
