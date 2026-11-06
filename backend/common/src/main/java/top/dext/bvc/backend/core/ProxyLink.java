package top.dext.bvc.backend.core;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToBackend;

/**
 * The sub-server's side of the {@code bvc:server} / {@code bvc:proxy} conversation.
 * <p>
 * Plugin messages need a player connection to travel on, so outgoing packets ride on an online
 * player's connection, preferably one where the proxy registered {@code bvc:server}.
 */
public final class ProxyLink {

    /** Compact id and voice state the proxy assigned to a player. */
    public static final class Binding {
        public final int id;
        public final boolean voiceEnabled;

        Binding(int id, boolean voiceEnabled) {
            this.id = id;
            this.voiceEnabled = voiceEnabled;
        }
    }

    private final BackendLog logger;
    private final String pluginVersion;
    private final String software;
    private final long instanceId = new SecureRandom().nextLong();
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<UUID, Binding>();
    private final Map<UUID, BackendPlayer> online = new ConcurrentHashMap<UUID, BackendPlayer>();
    private final AtomicBoolean resyncRequested = new AtomicBoolean(true);
    private final AtomicBoolean versionWarned = new AtomicBoolean();

    private volatile long proxyInstance;
    private volatile boolean proxySeen;
    private volatile String proxyVersion = "";

    public ProxyLink(BackendLog logger, String pluginVersion, String software) {
        this.logger = logger;
        this.pluginVersion = pluginVersion;
        this.software = software;
    }

    // ------------------------------------------------------------------ players

    public void onJoin(BackendPlayer player) {
        online.put(player.uuid(), player);
    }

    public void onQuit(UUID uuid) {
        online.remove(uuid);
        bindings.remove(uuid);
    }

    public Binding binding(UUID uuid) {
        return bindings.get(uuid);
    }

    public boolean isOnline(UUID uuid) {
        return online.containsKey(uuid);
    }

    public int boundCount() {
        return bindings.size();
    }

    public boolean proxySeen() {
        return proxySeen;
    }

    public String proxyVersion() {
        return proxyVersion;
    }

    /** True once per resync request (proxy hello, backend start, explicit request). */
    public boolean consumeResyncRequest() {
        return resyncRequested.getAndSet(false);
    }

    public void requestResync() {
        resyncRequested.set(true);
    }

    // ------------------------------------------------------------------ incoming

    /** A {@code bvc:proxy} message arrived on {@code player}'s connection. Any thread. */
    public void onProxyMessage(BackendPlayer player, byte[] message) {
        Packet<ProxyToBackend.Handler> packet;
        try {
            packet = ProxyToBackend.REGISTRY.decode(message);
        } catch (ProtocolException e) {
            logger.warn("Bad bvc:proxy message: " + e.getMessage());
            return;
        }
        online.put(player.uuid(), player);
        packet.handle(new Incoming(player));
    }

    /** Handles one message; {@code via} is the connection it arrived on, reused for replies. */
    private final class Incoming implements ProxyToBackend.Handler {

        private final BackendPlayer via;

        Incoming(BackendPlayer via) {
            this.via = via;
        }

        @Override
        public void handle(ProxyToBackend.ProxyHello packet) {
            if (packet.protocolVersion != BvcProtocol.VERSION) {
                if (versionWarned.compareAndSet(false, true)) {
                    logger.warn("The proxy speaks BVC protocol " + packet.protocolVersion + " but this plugin speaks "
                            + BvcProtocol.VERSION + "; update so both match");
                }
                return;
            }
            proxyVersion = packet.pluginVersion;
            synchronized (ProxyLink.this) {
                if (proxySeen && proxyInstance == packet.instanceId) {
                    return;
                }
                proxySeen = true;
                proxyInstance = packet.instanceId;
            }
            send(via, new BackendToProxy.BackendHello(BvcProtocol.VERSION, pluginVersion,
                    software, instanceId));
            resyncRequested.set(true);
            logger.info("Linked with BetterVoiceChat proxy " + packet.pluginVersion);
        }

        @Override
        public void handle(ProxyToBackend.BindPlayers packet) {
            for (ProxyToBackend.BindPlayers.Entry entry : packet.entries) {
                bindings.put(entry.uuid, new Binding(entry.playerId, entry.voiceEnabled));
            }
        }

        @Override
        public void handle(ProxyToBackend.UnbindPlayers packet) {
            for (int id : packet.playerIds) {
                for (Map.Entry<UUID, Binding> entry : bindings.entrySet()) {
                    if (entry.getValue().id == id) {
                        bindings.remove(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        @Override
        public void handle(ProxyToBackend.ResyncRequest packet) {
            resyncRequested.set(true);
        }
    }

    // ------------------------------------------------------------------ outgoing

    /**
     * Sends through a suitable online player.
     *
     * @return false if nobody is online to carry it
     */
    public boolean send(Packet<BackendToProxy.Handler> packet) {
        BackendPlayer carrier = carrier();
        if (carrier == null) {
            return false;
        }
        send(carrier, packet);
        return true;
    }

    private void send(BackendPlayer carrier, Packet<BackendToProxy.Handler> packet) {
        carrier.sendPluginMessage(BvcProtocol.CHANNEL_SERVER, BackendToProxy.REGISTRY.encode(packet));
    }

    /** Prefers a connection where the proxy registered {@code bvc:server}; {@code null} if nobody is online. */
    BackendPlayer carrier() {
        BackendPlayer fallback = null;
        for (BackendPlayer player : online.values()) {
            if (!player.isOnline()) {
                continue;
            }
            if (player.listensOn(BvcProtocol.CHANNEL_SERVER)) {
                return player;
            }
            fallback = player;
        }
        return fallback;
    }
}
