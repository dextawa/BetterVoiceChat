package top.dext.bvc.proxy.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import top.dext.bvc.proxy.BvcProxyCore;
import top.dext.bvc.proxy.config.Messages;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;

/**
 * {@code /vreport <player> [reason]}. Open to every player: Velocity has no permissions without
 * a plugin, and reporting must not depend on one. The cooldown limits abuse.
 */
public final class ReportCommand {

    /** Staff with this permission are told about new reports. */
    public static final String NOTIFY_PERMISSION = "bvc.report.notify";
    private static final String BYPASS_COOLDOWN = "bvc.report.nocooldown";

    private final BvcProxyCore core;

    public ReportCommand(BvcProxyCore core) {
        this.core = core;
    }

    public void execute(CommandSource source, String[] args) {
        Messages messages = core.messages();
        if (source.uuid() == null) {
            source.sendMessage(messages.get("report-players-only"));
            return;
        }
        if (args.length < 1) {
            source.sendMessage(messages.get("report-usage", "command", core.config().reports().aliases().get(0)));
            return;
        }
        Optional<PlatformPlayer> reporter = core.platform().player(source.uuid());
        String server = reporter.map(PlatformPlayer::currentServer).orElse(null);
        if (server == null) {
            source.sendMessage(messages.get("report-not-on-server"));
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        core.stores().findPlayer(args[0]).thenAccept(target -> {
            if (target.isEmpty()) {
                source.sendMessage(messages.get("player-not-found", "player", args[0]));
                return;
            }
            core.reports().report(source.uuid(), source.name(), target.get().uuid(), target.get().name(), server, reason,
                    core.clock().millis(), source.hasPermission(BYPASS_COOLDOWN)).thenAccept(result -> {
                        switch (result) {
                            case CREATED -> source.sendMessage(messages.get("report-done", "player", target.get().name()));
                            case COOLDOWN -> source.sendMessage(messages.get("report-cooldown"));
                            case SELF -> source.sendMessage(messages.get("report-self"));
                            case DISABLED -> source.sendMessage(messages.get("report-disabled"));
                        }
                    }).exceptionally(error -> {
                        core.logger().error("Report failed", error);
                        source.sendMessage(messages.get("report-failed"));
                        return null;
                    });
        });
    }

    public List<String> suggest(CommandSource source, String[] args) {
        if (args.length > 1) {
            return Collections.emptyList();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (PlatformPlayer player : core.platform().players()) {
            if (player.name().toLowerCase(Locale.ROOT).startsWith(prefix) && !player.uuid().equals(source.uuid())) {
                out.add(player.name());
            }
        }
        return out;
    }
}
