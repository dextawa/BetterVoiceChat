package top.dext.bvc.backend;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

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
final class ProxyLink implements PluginMessageListener {

    /** Compact id and voice state the proxy assigned to a player. */
    static final class Binding {
        final int id;
        final boolean voiceEnabled;

        Binding(int id, boolean voiceEnabled) {
            this.id = id;
            this.voiceEnabled = voiceEnabled;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final String software;
    private final long instanceId = new SecureRandom().nextLong();
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<UUID, Binding>();
    private final Map<UUID, Player> online = new ConcurrentHashMap<UUID, Player>();
    private final AtomicBoolean resyncRequested = new AtomicBoolean(true);
    private final AtomicBoolean versionWarned = new AtomicBoolean();

    private volatile long proxyInstance;
    private volatile boolean proxySeen;
    private volatile String proxyVersion = "";

    ProxyLink(Plugin plugin, String software) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.software = software;
    }

    // ------------------------------------------------------------------ players

    void onJoin(Player player) {
        online.put(player.getUniqueId(), player);
    }

    void onQuit(Player player) {
        online.remove(player.getUniqueId());
        bindings.remove(player.getUniqueId());
    }

    Binding binding(UUID uuid) {
        return bindings.get(uuid);
    }

    boolean isOnline(UUID uuid) {
        return online.containsKey(uuid);
    }

    Iterable<Player> onlinePlayers() {
        return online.values();
    }

    int boundCount() {
        return bindings.size();
    }

    boolean proxySeen() {
        return proxySeen;
    }

    String proxyVersion() {
        return proxyVersion;
    }

    /** True once per resync request (proxy hello, backend start, explicit request). */
    boolean consumeResyncRequest() {
        return resyncRequested.getAndSet(false);
    }

    void requestResync() {
        resyncRequested.set(true);
    }

    // ------------------------------------------------------------------ incoming

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BvcProtocol.CHANNEL_PROXY.equals(channel)) {
            return;
        }
        Packet<ProxyToBackend.Handler> packet;
        try {
            packet = ProxyToBackend.REGISTRY.decode(message);
        } catch (ProtocolException e) {
            logger.warning("Bad bvc:proxy message: " + e.getMessage());
            return;
        }
        online.put(player.getUniqueId(), player);
        packet.handle(new Incoming(player));
    }

    /** Handles one message; {@code via} is the connection it arrived on, reused for replies. */
    private final class Incoming implements ProxyToBackend.Handler {

        private final Player via;

        Incoming(Player via) {
            this.via = via;
        }

        @Override
        public void handle(ProxyToBackend.ProxyHello packet) {
            if (packet.protocolVersion != BvcProtocol.VERSION) {
                if (versionWarned.compareAndSet(false, true)) {
                    logger.warning("The proxy speaks BVC protocol " + packet.protocolVersion + " but this plugin speaks "
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
            send(via, new BackendToProxy.BackendHello(BvcProtocol.VERSION, plugin.getDescription().getVersion(),
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
    boolean send(Packet<BackendToProxy.Handler> packet) {
        Player carrier = carrier();
        if (carrier == null) {
            return false;
        }
        send(carrier, packet);
        return true;
    }

    private void send(Player carrier, Packet<BackendToProxy.Handler> packet) {
        carrier.sendPluginMessage(plugin, BvcProtocol.CHANNEL_SERVER, BackendToProxy.REGISTRY.encode(packet));
    }

    /** Prefers a connection where the proxy registered {@code bvc:server}; {@code null} if nobody is online. */
    Player carrier() {
        Player fallback = null;
        for (Player player : online.values()) {
            if (!player.isOnline()) {
                continue;
            }
            if (player.getListeningPluginChannels().contains(BvcProtocol.CHANNEL_SERVER)) {
                return player;
            }
            fallback = player;
        }
        return fallback;
    }
}
