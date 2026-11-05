package top.dext.bvc.proxy.routing;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.proxy.FakePlayer;
import top.dext.bvc.proxy.MutableClock;
import top.dext.bvc.proxy.TestLogger;
import top.dext.bvc.proxy.backend.BackendBridge;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.mute.MuteManager;
import top.dext.bvc.proxy.session.SessionManager;
import top.dext.bvc.proxy.session.VoiceSession;
import top.dext.bvc.proxy.storage.Database;
import top.dext.bvc.proxy.storage.Stores;

/**
 * Router + backend bridge + mutes wired together without a proxy platform.
 */
final class RouterHarness implements AutoCloseable {

    static final byte[] OPUS = {1, 2, 3, 4};

    final MutableClock clock = new MutableClock(1_000_000L);
    final SessionManager sessions = new SessionManager();
    final RouteRegistry routes = new RouteRegistry();
    final Database database;
    final MuteManager mutes;
    final VoiceRouter router;
    final BackendBridge backend;
    private final AtomicInteger sequence = new AtomicInteger();
    private long backendSequence = 1;
    volatile BvcConfig config;

    RouterHarness() throws SQLException {
        this("");
    }

    RouterHarness(String yaml) throws SQLException {
        config = BvcConfig.parse(yaml);
        database = Database.openInMemory("router-" + UUID.randomUUID());
        TestLogger logger = new TestLogger();
        mutes = new MuteManager(new Stores(database), clock, logger, uuid -> {
            VoiceSession session = sessions.get(uuid);
            if (session != null) {
                routerRef().pushMuteStatus(session);
            }
        }, () -> {
        });
        router = new VoiceRouter(sessions, routes, mutes, () -> config, clock);
        backend = new BackendBridge(sessions, routes, router, mutes, logger, "test");
    }

    private VoiceRouter routerRef() {
        return router;
    }

    /** A player with the mod, voice connected over plugin messages, on {@code server}. */
    VoiceSession join(String name, String server) {
        FakePlayer player = new FakePlayer(name);
        player.server = server;
        VoiceSession session = sessions.create(player, clock.millis());
        session.onHello("test", "26.3", "fabric", 3);
        session.usePluginMessages();
        sessions.moveToServer(session, server);
        routes.getOrCreate(server);
        return session;
    }

    static FakePlayer player(VoiceSession session) {
        return (FakePlayer) session.player();
    }

    void backendHello(String server, VoiceSession via) {
        send(server, via, new BackendToProxy.BackendHello(BvcProtocol.VERSION, "test", "Paper", 42L));
    }

    /** Puts players in a room and group. */
    void membership(String server, int room, int group, VoiceSession... members) {
        List<BackendToProxy.Membership.Entry> entries = new ArrayList<>();
        for (VoiceSession member : members) {
            entries.add(new BackendToProxy.Membership.Entry(member.id(), room, group));
        }
        send(server, members[0], new BackendToProxy.Membership(backendSequence++, entries));
    }

    void neighbors(String server, VoiceSession speaker, VoiceSession... listeners) {
        int[] ids = new int[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            ids[i] = listeners[i].id();
        }
        send(server, speaker, new BackendToProxy.Neighbors(backendSequence++,
                List.of(new BackendToProxy.Neighbors.Entry(speaker.id(), ids))));
    }

    void flags(String server, VoiceSession player, int priority, int flags) {
        send(server, player, new BackendToProxy.PlayerFlags(backendSequence++,
                List.of(new BackendToProxy.PlayerFlags.Entry(player.id(), priority, flags))));
    }

    void zone(String server, VoiceSession player, VoiceMode mode) {
        send(server, player, new BackendToProxy.ZoneInfo(backendSequence++,
                List.of(new BackendToProxy.ZoneInfo.Entry(player.id(), "", mode, Activation.ANY, 48f))));
    }

    void send(String server, VoiceSession via, top.dext.bvc.protocol.packet.Packet<BackendToProxy.Handler> packet) {
        backend.onBackendMessage(server, via.player(), BackendToProxy.REGISTRY.encode(packet));
    }

    void speak(VoiceSession speaker) {
        speak(speaker, false);
    }

    void speak(VoiceSession speaker, boolean broadcast) {
        router.onMicAudio(speaker, new ClientToProxy.MicAudio(sequence.incrementAndGet(),
                broadcast ? ClientToProxy.MicAudio.FLAG_BROADCAST : 0, OPUS));
    }

    /** Standard setup: backend present, everyone in room 0 with the given mode. */
    void setupServer(String server, VoiceMode mode, VoiceSession... players) {
        backendHello(server, players[0]);
        for (VoiceSession player : players) {
            zone(server, player, mode);
        }
    }

    void clearAll() {
        for (VoiceSession session : sessions.all()) {
            player(session).clear();
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
