package top.dext.bvc.proxy.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.proxy.BvcProxyCore;

public final class BvcBungeePlugin extends Plugin implements Listener {

    private BvcProxyCore core;
    private BungeePlatform platform;

    @Override
    public void onEnable() {
        platform = new BungeePlatform(this);
        core = new BvcProxyCore(platform);
        try {
            core.enable();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "BetterVoiceChat failed to start", e);
            core = null;
            return;
        }
        getProxy().registerChannel(BvcProtocol.CHANNEL_VOICE);
        getProxy().registerChannel(BvcProtocol.CHANNEL_SERVER);
        getProxy().registerChannel(BvcProtocol.CHANNEL_PROXY);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, platform));
        try {
            BungeeInjector.inject(getProxy(), core);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().warning("Could not hook the Minecraft port; stream transport (A) is unavailable, clients will use"
                    + " plugin messages (B). Cause: " + e);
        }
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPostLogin(PostLoginEvent event) {
        core.onPlayerJoin(platform.wrap(event.getPlayer()));
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        ProxiedPlayer player = event.getPlayer();
        Server server = player.getServer();
        if (server != null) {
            core.onServerConnected(platform.wrap(player), server.getInfo().getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDisconnect(PlayerDisconnectEvent event) {
        core.onPlayerQuit(platform.wrap(event.getPlayer()));
        platform.forget(event.getPlayer());
    }

    /** BVC channels are always consumed here, never forwarded. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginMessage(PluginMessageEvent event) {
        String tag = event.getTag();
        boolean voice = BvcProtocol.CHANNEL_VOICE.equals(tag);
        boolean server = BvcProtocol.CHANNEL_SERVER.equals(tag);
        if (!voice && !server && !BvcProtocol.CHANNEL_PROXY.equals(tag)) {
            return;
        }
        event.setCancelled(true);

        if (voice && event.getSender() instanceof ProxiedPlayer player) {
            core.onClientMessage(platform.wrap(player), event.getData());
        } else if (server && event.getSender() instanceof Server backend && event.getReceiver() instanceof ProxiedPlayer player) {
            core.onBackendMessage(platform.wrap(player), backend.getInfo().getName(), event.getData());
        }
        // Anything else (a client faking bvc:server, a backend sending bvc:voice...) is dropped.
    }
}
