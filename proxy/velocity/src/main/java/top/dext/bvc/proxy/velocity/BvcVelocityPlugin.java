package top.dext.bvc.proxy.velocity;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.proxy.BvcProxyCore;

@Plugin(
        id = "bettervoicechat",
        name = "BetterVoiceChat",
        version = BuildInfo.VERSION,
        description = "TCP voice chat for Minecraft networks: mutes, replays, reports",
        authors = {"dext.top"},
        dependencies = {
                @Dependency(id = "viaversion", optional = true),
                @Dependency(id = "geyser", optional = true),
                @Dependency(id = "floodgate", optional = true)
        })
public final class BvcVelocityPlugin {

    static final MinecraftChannelIdentifier VOICE = MinecraftChannelIdentifier.from(BvcProtocol.CHANNEL_VOICE);
    static final MinecraftChannelIdentifier SERVER = MinecraftChannelIdentifier.from(BvcProtocol.CHANNEL_SERVER);
    static final MinecraftChannelIdentifier PROXY = MinecraftChannelIdentifier.from(BvcProtocol.CHANNEL_PROXY);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private BvcProxyCore core;

    @Inject
    public BvcVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        VelocityPlatform platform = new VelocityPlatform(this, proxy, logger, dataDirectory, BuildInfo.VERSION);
        core = new BvcProxyCore(platform);
        try {
            core.enable();
        } catch (Exception e) {
            logger.error("BetterVoiceChat failed to start", e);
            core = null;
            return;
        }
        proxy.getChannelRegistrar().register(VOICE, SERVER, PROXY);
        proxy.getEventManager().register(this, new VelocityListener(core, platform));
        CommandMeta meta = proxy.getCommandManager().metaBuilder("bvc").aliases("bettervoicechat").plugin(this).build();
        proxy.getCommandManager().register(meta, new VelocityCommand(core, platform));
        try {
            VelocityInjector.inject(proxy, core);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("Could not hook the Minecraft port; stream transport (A) is unavailable, clients will use"
                    + " plugin messages (B). Cause: " + e);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.disable();
        }
    }
}
