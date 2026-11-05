package top.dext.bvc.proxy;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToBackend;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.platform.PlatformPlayer;

/**
 * Records everything sent to it, decoded, for assertions.
 */
public final class FakePlayer implements PlatformPlayer {

    public final UUID uuid;
    public final String name;
    public final Set<String> permissions = new HashSet<>();
    public final List<Packet<ProxyToClient.Handler>> toClient = new CopyOnWriteArrayList<>();
    public final List<Packet<ProxyToBackend.Handler>> toBackend = new CopyOnWriteArrayList<>();
    public final List<String> chat = new CopyOnWriteArrayList<>();
    public volatile String server;
    public volatile String disconnectedWith;

    public FakePlayer(String name) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    @Override
    public void sendMessage(String text) {
        chat.add(text);
    }

    @Override
    public String currentServer() {
        return server;
    }

    @Override
    public void sendPluginMessage(String channel, byte[] data) {
        if (BvcProtocol.CHANNEL_VOICE.equals(channel)) {
            toClient.add(ProxyToClient.REGISTRY.decode(data));
        }
    }

    @Override
    public boolean sendToBackend(String channel, byte[] data) {
        if (server == null) {
            return false;
        }
        if (BvcProtocol.CHANNEL_PROXY.equals(channel)) {
            toBackend.add(ProxyToBackend.REGISTRY.decode(data));
        }
        return true;
    }

    @Override
    public void disconnect(String text) {
        disconnectedWith = text;
    }

    @Override
    public boolean isOnline() {
        return disconnectedWith == null;
    }

    @Override
    public SocketAddress remoteAddress() {
        return new InetSocketAddress("127.0.0.1", 50000);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> received(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Packet<ProxyToClient.Handler> packet : toClient) {
            if (type.isInstance(packet)) {
                out.add((T) packet);
            }
        }
        return out;
    }

    public void clear() {
        toClient.clear();
        toBackend.clear();
        chat.clear();
    }
}
