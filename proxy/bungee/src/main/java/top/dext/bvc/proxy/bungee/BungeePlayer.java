package top.dext.bvc.proxy.bungee;

import java.net.SocketAddress;
import java.util.UUID;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import top.dext.bvc.proxy.platform.PlatformPlayer;

final class BungeePlayer implements PlatformPlayer {

    private final ProxiedPlayer player;

    BungeePlayer(ProxiedPlayer player) {
        this.player = player;
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String text) {
        player.sendMessage(BungeePlatform.text(text));
    }

    @Override
    public String currentServer() {
        Server server = player.getServer();
        return server == null ? null : server.getInfo().getName();
    }

    @Override
    public void sendPluginMessage(String channel, byte[] data) {
        player.sendData(channel, data);
    }

    @Override
    public boolean sendToBackend(String channel, byte[] data) {
        Server server = player.getServer();
        if (server == null) {
            return false;
        }
        server.sendData(channel, data);
        return true;
    }

    @Override
    public void disconnect(String text) {
        player.disconnect(BungeePlatform.text(text));
    }

    @Override
    public boolean isOnline() {
        return player.isConnected();
    }

    @Override
    public SocketAddress remoteAddress() {
        return player.getSocketAddress();
    }
}
