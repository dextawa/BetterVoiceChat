package top.dext.bvc.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.api.proxy.BvcProxy;
import top.dext.bvc.api.proxy.VoiceListener;
import top.dext.bvc.api.proxy.VoicePlayer;
import top.dext.bvc.api.proxy.VoiceReport;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.packet.ClientToProxy;

/**
 * The public proxy API: events, player state, reports.
 */
class ProxyApiTest {

    @TempDir
    Path data;

    private BvcProxyCore core;

    @AfterEach
    void stop() {
        if (core != null) {
            core.disable();
        }
    }

    @Test
    void listenersSeeVoiceAndPlayerStateIsExposed() throws Exception {
        Files.writeString(data.resolve("config.yml"), "web:\n  enabled: false\n");
        FakePlatform platform = new FakePlatform(data);
        core = new BvcProxyCore(platform, new MutableClock(System.currentTimeMillis()));
        core.enable();
        List<String> events = new CopyOnWriteArrayList<>();
        BvcProxy.get().addListener(new VoiceListener() {
            @Override
            public void onVoiceConnected(VoicePlayer player) {
                events.add("up:" + player.name());
            }

            @Override
            public void onVoiceDisconnected(VoicePlayer player) {
                events.add("down:" + player.name());
            }
        });

        FakePlayer alice = platform.add("Alice");
        alice.permissions.add("bvc.priority.100");
        core.onPlayerJoin(alice);
        alice.server = "lobby";
        core.onServerConnected(alice, "lobby");
        core.onClientMessage(alice, ClientToProxy.REGISTRY.encode(new ClientToProxy.Hello(BvcProtocol.VERSION, "0.1.0", "26.3",
                "fabric", Transport.PLUGIN_MESSAGE.bit())));
        core.onClientMessage(alice, ClientToProxy.REGISTRY.encode(new ClientToProxy.SelectTransport(Transport.PLUGIN_MESSAGE)));
        core.onClientMessage(alice, ClientToProxy.REGISTRY.encode(new ClientToProxy.ClientStatus(true, false)));

        VoicePlayer view = BvcProxy.get().player(alice.uuid).orElseThrow();
        assertEquals(100, view.priority());
        assertEquals("PLUGIN_MESSAGE", view.transportName());
        assertTrue(view.isMicrophoneMuted());

        core.onPlayerQuit(alice);
        assertEquals(List.of("up:Alice", "down:Alice"), events);
    }

    @Test
    void reportServiceFilesAndListsReports() throws Exception {
        Files.writeString(data.resolve("config.yml"), "web:\n  enabled: false\nreports:\n  after-seconds: 0\n");
        FakePlatform platform = new FakePlatform(data);
        core = new BvcProxyCore(platform);
        core.enable();
        List<VoiceReport> ready = new CopyOnWriteArrayList<>();
        BvcProxy.get().addListener(new VoiceListener() {
            @Override
            public void onReport(VoiceReport report) {
                ready.add(report);
            }
        });
        FakePlayer a = platform.add("A");
        FakePlayer b = platform.add("B");
        long id = BvcProxy.get().reportService().report(a.uuid, "A", b.uuid, "B", "lobby", "spam").get(10, TimeUnit.SECONDS);
        assertTrue(id > 0);
        assertEquals(-1L, BvcProxy.get().reportService().report(a.uuid, "A", a.uuid, "A", "lobby", "").get(10, TimeUnit.SECONDS));

        for (int i = 0; i < 100 && ready.isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertEquals(1, ready.size());
        assertEquals(id, ready.get(0).id());
        assertEquals("OPEN", BvcProxy.get().reportService().reports("open", 10).get(10, TimeUnit.SECONDS).get(0).status());

        BvcProxy.get().reportService().setStatus(id, "accepted", "bot", "ok").get(10, TimeUnit.SECONDS);
        assertEquals("ACCEPTED", BvcProxy.get().reportService().reports(null, 10).get(10, TimeUnit.SECONDS).get(0).status());
    }
}
