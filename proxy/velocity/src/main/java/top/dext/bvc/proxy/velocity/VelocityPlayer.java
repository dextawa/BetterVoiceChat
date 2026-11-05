package top.dext.bvc.proxy.velocity;

import java.net.SocketAddress;
import java.util.Optional;
import java.util.UUID;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import top.dext.bvc.proxy.platform.PlatformPlayer;

final class VelocityPlayer implements PlatformPlayer {

    private final Player player;

    VelocityPlayer(Player player) {
        this.player = player;
    }

    Player handle() {
        return player;
    }

    @Override
    public String name() {
        return player.getUsername();
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
        player.sendMessage(VelocityPlatform.text(text));
    }

    @Override
    public String currentServer() {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
    }

    @Override
    public void sendPluginMessage(String channel, byte[] data) {
        player.sendPluginMessage(VelocityPlatform.channel(channel), data);
    }

    @Override
    public boolean sendToBackend(String channel, byte[] data) {
        Optional<ServerConnection> connection = player.getCurrentServer();
        return connection.isPresent() && connection.get().sendPluginMessage(VelocityPlatform.channel(channel), data);
    }

    @Override
    public void disconnect(String text) {
        player.disconnect(VelocityPlatform.text(text));
    }

    @Override
    public boolean isOnline() {
        return player.isActive();
    }

    @Override
    public SocketAddress remoteAddress() {
        return player.getRemoteAddress();
    }
}
