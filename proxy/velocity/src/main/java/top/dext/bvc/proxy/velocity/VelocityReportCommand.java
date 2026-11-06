package top.dext.bvc.proxy.velocity;

import java.util.List;

import com.velocitypowered.api.command.SimpleCommand;

import top.dext.bvc.proxy.BvcProxyCore;

final class VelocityReportCommand implements SimpleCommand {

    private final BvcProxyCore core;
    private final VelocityPlatform platform;

    VelocityReportCommand(BvcProxyCore core, VelocityPlatform platform) {
        this.core = core;
        this.platform = platform;
    }

    @Override
    public void execute(Invocation invocation) {
        core.reportCommand().execute(platform.wrapSource(invocation.source()), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        // Velocity passes no trailing empty argument when the user typed a space; add it.
        if (args.length == 0) {
            args = new String[] {""};
        }
        return core.reportCommand().suggest(platform.wrapSource(invocation.source()), args);
    }
}
