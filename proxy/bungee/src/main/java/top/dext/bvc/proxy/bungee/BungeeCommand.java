package top.dext.bvc.proxy.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import top.dext.bvc.proxy.BvcProxyCore;

final class BungeeCommand extends Command implements TabExecutor {

    private final BvcProxyCore core;
    private final BungeePlatform platform;

    BungeeCommand(BvcProxyCore core, BungeePlatform platform) {
        super("bvc", null, "bettervoicechat");
        this.core = core;
        this.platform = platform;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        core.command().execute(platform.wrapSource(sender), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return core.command().suggest(platform.wrapSource(sender), args);
    }
}
