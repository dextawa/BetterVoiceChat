package top.dext.bvc.proxy.bungee;

import java.util.List;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import top.dext.bvc.proxy.BvcProxyCore;

final class BungeeReportCommand extends Command implements TabExecutor {

    private final BvcProxyCore core;
    private final BungeePlatform platform;

    BungeeReportCommand(BvcProxyCore core, BungeePlatform platform) {
        super(core.config().reports().aliases().get(0), null, aliases(core));
        this.core = core;
        this.platform = platform;
    }

    private static String[] aliases(BvcProxyCore core) {
        List<String> names = core.config().reports().aliases();
        return names.subList(1, names.size()).toArray(new String[0]);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        core.reportCommand().execute(platform.wrapSource(sender), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return core.reportCommand().suggest(platform.wrapSource(sender), args);
    }
}
