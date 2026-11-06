package top.dext.bvc.proxy.backend;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import top.dext.bvc.api.MuteScope;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToBackend;
import top.dext.bvc.proxy.mute.MuteManager;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.routing.RouteRegistry;
import top.dext.bvc.proxy.routing.ServerRoutes;
import top.dext.bvc.proxy.routing.VoiceRouter;
import top.dext.bvc.proxy.session.SessionManager;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * Talks to the BVC plugin on each sub-server: applies its routing decisions and tells it which
 * compact id belongs to which player.
 */
public final class BackendBridge {

    /** BindPlayers entries per message; keeps payloads well under Bukkit's 32 KB limit on 1.8. */
    private static final int BIND_CHUNK = 500;

    private final SessionManager sessions;
    private final RouteRegistry routes;
    private final VoiceRouter router;
    private final MuteManager mutes;
    private final BvcLogger logger;
    private final String pluginVersion;
    /** Shared secret ({@code security.backend-secret}); empty = sub-servers are trusted as before. */
    private final Supplier<String> secret;
    /** Sub-servers whose BackendHello carried a valid proof (only used with a secret). */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedUnverified = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> helloRetry = new ConcurrentHashMap<>();
    /** Random per proxy start; tells backends when the proxy forgot their tables. */
    private final long instanceId = new SecureRandom().nextLong();

    public BackendBridge(SessionManager sessions, RouteRegistry routes, VoiceRouter router, MuteManager mutes,
                         BvcLogger logger, String pluginVersion) {
        this(sessions, routes, router, mutes, logger, pluginVersion, () -> "");
    }

    public BackendBridge(SessionManager sessions, RouteRegistry routes, VoiceRouter router, MuteManager mutes,
                         BvcLogger logger, String pluginVersion, Supplier<String> secret) {
        this.secret = secret;
        this.sessions = sessions;
        this.routes = routes;
        this.router = router;
        this.mutes = mutes;
        this.logger = logger;
        this.pluginVersion = pluginVersion;
    }

    /** A plugin message arrived from sub-server {@code server} through {@code via}'s connection. */
    public void onBackendMessage(String server, PlatformPlayer via, byte[] data) {
        Packet<BackendToProxy.Handler> packet;
        try {
            packet = BackendToProxy.REGISTRY.decode(data);
        } catch (ProtocolException e) {
            logger.warn("Bad bvc:server message from " + server + ": " + e.getMessage());
            return;
        }
        if (!(packet instanceof BackendToProxy.BackendHello)) {
            ServerRoutes table = routes.get(server);
            if (table == null || !table.backendPresent()) {
                // Tables from a sub-server whose BackendHello never arrived (the server dropped it
                // before this connection registered bvc:server): ask again, at most every 2 s.
                long now = System.currentTimeMillis();
                Long last = helloRetry.get(server);
                if (last == null || now - last > 2000) {
                    helloRetry.put(server, now);
                    sendHello(via);
                }
            }
            if (!secret.get().isEmpty() && !verified.contains(server)) {
                return; // tables only from a sub-server that proved it knows the secret
            }
        }
        packet.handle(new Handler(server, via));
    }

    /** The player just finished connecting to {@code server}. */
    public void onPlayerConnected(VoiceSession session, String server) {
        PlatformPlayer player = session.player();
        sendHello(player);
        sendBind(player, List.of(session));
    }

    private void sendHello(PlatformPlayer player) {
        String key = secret.get();
        byte[] proof = key.isEmpty() ? new byte[0] : BvcCrypto.linkProof(key, "proxy", instanceId, 0);
        player.sendToBackend(BvcProtocol.CHANNEL_PROXY,
                ProxyToBackend.REGISTRY.encode(new ProxyToBackend.ProxyHello(BvcProtocol.VERSION, pluginVersion, instanceId, proof)));
    }

    /** The player's voice connection came up or went down; the backend skips players without voice. */
    public void onVoiceChanged(VoiceSession session) {
        if (session.server() != null) {
            sendBind(session.player(), List.of(session));
        }
    }

    /** Sends bindings for everyone on the sub-server (after the backend (re)started). */
    public void bindAll(String server, PlatformPlayer via) {
        List<VoiceSession> all = new ArrayList<>(sessions.onServer(server));
        for (int i = 0; i < all.size(); i += BIND_CHUNK) {
            sendBind(via, all.subList(i, Math.min(all.size(), i + BIND_CHUNK)));
        }
    }

    private static void sendBind(PlatformPlayer via, List<VoiceSession> batch) {
        List<ProxyToBackend.BindPlayers.Entry> entries = new ArrayList<>(batch.size());
        for (VoiceSession session : batch) {
            entries.add(new ProxyToBackend.BindPlayers.Entry(session.uuid(), session.id(), session.isVoiceConnected()));
        }
        via.sendToBackend(BvcProtocol.CHANNEL_PROXY, ProxyToBackend.REGISTRY.encode(new ProxyToBackend.BindPlayers(entries)));
    }

    /** Only players currently on that sub-server may appear in its tables. */
    private VoiceSession onServer(String server, int playerId) {
        VoiceSession session = sessions.get(playerId);
        return session != null && server.equals(session.server()) ? session : null;
    }

    private final class Handler implements BackendToProxy.Handler {

        private final String server;
        private final PlatformPlayer via;

        Handler(String server, PlatformPlayer via) {
            this.server = server;
            this.via = via;
        }

        @Override
        public void handle(BackendToProxy.BackendHello packet) {
            if (packet.protocolVersion != BvcProtocol.VERSION) {
                logger.warn("Sub-server " + server + " runs BVC protocol " + packet.protocolVersion
                        + " but the proxy speaks " + BvcProtocol.VERSION + "; update the plugin there");
                return;
            }
            String key = secret.get();
            if (!key.isEmpty()) {
                if (!BvcCrypto.verifyLinkProof(key, "backend", instanceId, packet.instanceId, packet.proof)) {
                    if (verified.remove(server) || !warnedUnverified.contains(server)) {
                        warnedUnverified.add(server);
                        logger.warn("Sub-server " + server + " did not prove the shared secret (security.backend-secret);"
                                + " its voice tables are ignored. Set the same secret in its config.yml.");
                    }
                    return;
                }
                verified.add(server);
                warnedUnverified.remove(server);
            }
            ServerRoutes table = routes.getOrCreate(server);
            if (table.onBackendHello(packet.pluginVersion, packet.software, packet.instanceId)) {
                logger.info("Sub-server " + server + " connected (BVC " + packet.pluginVersion + ", " + packet.software + ")");
            }
            bindAll(server, via);
            for (VoiceSession session : sessions.onServer(server)) {
                router.pushVoiceState(session);
            }
        }

        @Override
        public void handle(BackendToProxy.Membership packet) {
            ServerRoutes table = routes.getOrCreate(server);
            for (BackendToProxy.Membership.Entry entry : packet.entries) {
                if (onServer(server, entry.playerId) != null) {
                    table.applyMembership(packet.sequence, entry.playerId, entry.roomId, entry.groupId);
                }
            }
        }

        @Override
        public void handle(BackendToProxy.Neighbors packet) {
            ServerRoutes table = routes.getOrCreate(server);
            for (BackendToProxy.Neighbors.Entry entry : packet.entries) {
                if (onServer(server, entry.speakerId) != null) {
                    table.applyNeighbors(packet.sequence, entry.speakerId, entry.listenerIds);
                }
            }
        }

        @Override
        public void handle(BackendToProxy.PlayerFlags packet) {
            ServerRoutes table = routes.getOrCreate(server);
            for (BackendToProxy.PlayerFlags.Entry entry : packet.entries) {
                VoiceSession session = onServer(server, entry.playerId);
                if (session != null) {
                    table.applyFlags(packet.sequence, entry.playerId, entry.priority, entry.flags);
                    router.pushVoiceState(session);
                }
            }
        }

        @Override
        public void handle(BackendToProxy.ZoneInfo packet) {
            ServerRoutes table = routes.getOrCreate(server);
            for (BackendToProxy.ZoneInfo.Entry entry : packet.entries) {
                VoiceSession session = onServer(server, entry.playerId);
                if (session != null && table.applyZone(packet.sequence, entry.playerId, entry.room, entry.mode,
                        entry.activation, entry.radius)) {
                    router.pushVoiceState(session);
                }
            }
        }

        @Override
        public void handle(BackendToProxy.MuteRequest packet) {
            MuteScope scope = packet.scope.isEmpty() ? MuteScope.server(server) : MuteScope.parse(packet.scope);
            String operator = packet.operator.isEmpty() ? server : packet.operator;
            if (packet.isUnmute()) {
                mutes.unmute(packet.target, scope, operator);
            } else {
                VoiceSession target = sessions.get(packet.target);
                String name = target != null ? target.name() : packet.target.toString().substring(0, 8);
                mutes.mute(packet.target, name, scope, Math.max(0, packet.durationMillis), packet.reason, operator);
            }
        }
    }
}
