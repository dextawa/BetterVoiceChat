package top.dext.bvc.backend;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

import top.dext.bvc.api.server.BvcServer;
import top.dext.bvc.backend.scheduler.BukkitSampling;
import top.dext.bvc.backend.scheduler.SamplingScheduler;
import top.dext.bvc.protocol.BvcProtocol;

public final class BvcBukkitPlugin extends JavaPlugin implements Listener {

    private static final String FOLIA_SAMPLING = "top.dext.bvc.backend.folia.FoliaSampling";

    private ProxyLink link;
    private VoiceEngine engine;
    private SamplingScheduler sampling;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BackendSettings settings = BackendSettings.read(getConfig(), getLogger());

        link = new ProxyLink(this, Bukkit.getName() + " " + Bukkit.getVersion());
        Messenger messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, BvcProtocol.CHANNEL_SERVER);
        messenger.registerIncomingPluginChannel(this, BvcProtocol.CHANNEL_PROXY, link);

        engine = new VoiceEngine(link, getLogger(), settings);
        engine.start();
        sampling = createSampling();
        sampling.start(engine::sample, settings.intervalTicks);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            link.onJoin(player);
            sampling.track(player);
        }

        BvcsCommand command = new BvcsCommand(this);
        PluginCommand bvcs = getCommand("bvcs");
        if (bvcs != null) {
            bvcs.setExecutor(command);
            bvcs.setTabCompleter(command);
        }

        BvcServer.register(new BukkitVoiceApi(engine, link));
        getLogger().info("Voice zone: " + BvcsCommand.describe(settings.rules.defaultZone)
                + (isFolia() ? " (Folia)" : ""));
    }

    @Override
    public void onDisable() {
        BvcServer.register(null);
        if (sampling != null) {
            sampling.stop();
        }
        if (engine != null) {
            engine.stop();
        }
        Messenger messenger = getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(this);
        messenger.unregisterIncomingPluginChannel(this);
    }

    void reload() {
        reloadConfig();
        BackendSettings settings = BackendSettings.read(getConfig(), getLogger());
        int oldInterval = engine.settings().intervalTicks;
        engine.apply(settings);
        if (settings.intervalTicks != oldInterval) {
            sampling.stop();
            engine.stop();
            engine.start();
            sampling = createSampling();
            sampling.start(engine::sample, settings.intervalTicks);
            for (Player player : Bukkit.getOnlinePlayers()) {
                sampling.track(player);
            }
        }
    }

    ProxyLink link() {
        return link;
    }

    VoiceEngine engine() {
        return engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        link.onJoin(event.getPlayer());
        sampling.track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sampling.untrack(player);
        link.onQuit(player);
        engine.forget(player.getUniqueId());
    }

    private SamplingScheduler createSampling() {
        if (isFolia()) {
            try {
                return (SamplingScheduler) Class.forName(FOLIA_SAMPLING).getConstructor(org.bukkit.plugin.Plugin.class)
                        .newInstance(this);
            } catch (ReflectiveOperationException | LinkageError e) {
                getLogger().log(Level.SEVERE, "Folia detected but its scheduler could not be loaded", e);
            }
        }
        return new BukkitSampling(this);
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
