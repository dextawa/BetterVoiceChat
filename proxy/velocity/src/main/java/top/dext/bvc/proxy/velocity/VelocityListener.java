package top.dext.bvc.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import top.dext.bvc.proxy.BvcProxyCore;

final class VelocityListener {

    private final BvcProxyCore core;
    private final VelocityPlatform platform;

    VelocityListener(BvcProxyCore core, VelocityPlatform platform) {
        this.core = core;
        this.platform = platform;
    }

    @Subscribe(priority = 16_000)
    public void onPostLogin(PostLoginEvent event) {
        core.onPlayerJoin(platform.wrap(event.getPlayer()));
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(connection ->
                core.onServerConnected(platform.wrap(player), connection.getServerInfo().getName()));
    }

    @Subscribe(priority = -16_000)
    public void onDisconnect(DisconnectEvent event) {
        core.onPlayerQuit(platform.wrap(event.getPlayer()));
        platform.forget(event.getPlayer());
    }

    /**
     * Synchronous (async = false) so audio frames keep their order and skip the event executor.
     * BVC channels are always consumed here, never forwarded.
     */
    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onPluginMessage(PluginMessageEvent event) {
        String id = event.getIdentifier().getId();
        boolean voice = id.equals(BvcVelocityPlugin.VOICE.getId());
        boolean server = id.equals(BvcVelocityPlugin.SERVER.getId());
        if (!voice && !server && !id.equals(BvcVelocityPlugin.PROXY.getId())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (voice && event.getSource() instanceof Player player) {
            core.onClientMessage(platform.wrap(player), event.getData());
        } else if (server && event.getSource() instanceof ServerConnection connection) {
            core.onBackendMessage(platform.wrap(connection.getPlayer()), connection.getServerInfo().getName(), event.getData());
        }
        // Anything else (a client faking bvc:server, a backend sending bvc:voice...) is dropped.
    }
}
