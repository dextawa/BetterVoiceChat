package top.dext.bvc.proxy.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteScope;
import top.dext.bvc.proxy.BvcProxyCore;
import top.dext.bvc.proxy.config.Messages;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.session.VoiceSession;
import top.dext.bvc.proxy.storage.Stores;

/**
 * {@code /bvc} admin command, shared by both proxy platforms.
 */
public final class BvcCommand {

    public static final String PERMISSION = "bvc.command";
    private static final List<String> SUBCOMMANDS = List.of("mute", "unmute", "globalmute", "servermute", "status", "reload", "help");

    private final BvcProxyCore core;

    public BvcCommand(BvcProxyCore core) {
        this.core = core;
    }

    public void execute(CommandSource source, String[] args) {
        Messages messages = core.messages();
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(messages.get("no-permission"));
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("help") && SUBCOMMANDS.contains(sub) && !source.hasPermission(PERMISSION + "." + sub)) {
            source.sendMessage(messages.get("no-permission"));
            return;
        }
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (sub) {
            case "mute" -> mute(source, rest);
            case "unmute" -> unmute(source, rest);
            case "globalmute" -> globalMute(source, rest);
            case "servermute" -> serverMute(source, rest);
            case "status" -> status(source, rest);
            case "reload" -> reload(source);
            default -> help(source);
        }
    }

    public List<String> suggest(CommandSource source, String[] args) {
        if (!source.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];
        int position = args.length - 1;
        switch (sub) {
            case "mute", "unmute", "status":
                if (position == 1) {
                    return filter(onlineNames(), current);
                }
                if (position == 2 && !sub.equals("status")) {
                    List<String> scopes = new ArrayList<>(core.platform().serverNames());
                    scopes.add(MuteScope.GLOBAL_KEY);
                    return filter(scopes, current);
                }
                if (position == 3 && sub.equals("mute")) {
                    return filter(List.of("10m", "30m", "1h", "6h", "1d", "7d", "perm"), current);
                }
                return Collections.emptyList();
            case "globalmute":
                return position == 1 ? filter(List.of("on", "off"), current) : Collections.emptyList();
            case "servermute":
                if (position == 1) {
                    return filter(new ArrayList<>(core.platform().serverNames()), current);
                }
                return position == 2 ? filter(List.of("on", "off"), current) : Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ subcommands

    private void mute(CommandSource source, String[] args) {
        Messages messages = core.messages();
        if (args.length < 1) {
            help(source);
            return;
        }
        int index = 1;
        MuteScope scope = MuteScope.global();
        if (args.length > index && isScope(args[index])) {
            scope = MuteScope.parse(args[index]);
            index++;
        }
        long duration = Durations.PERMANENT;
        if (args.length > index) {
            long parsed = Durations.parse(args[index]);
            if (parsed != Durations.INVALID) {
                duration = parsed;
                index++;
            }
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, Math.min(index, args.length), args.length));
        MuteScope finalScope = scope;
        long finalDuration = duration;
        resolve(args[0]).thenAccept(target -> {
            if (target.isEmpty()) {
                source.sendMessage(messages.get("player-not-found", "player", args[0]));
                return;
            }
            Stores.KnownPlayer player = target.get();
            core.mutes().mute(player.uuid(), player.name(), finalScope, finalDuration, reason, source.name());
            source.sendMessage(messages.get("mute-done", "player", player.name(), "scope", describe(finalScope),
                    "duration", finalDuration == 0 ? messages.raw("duration-permanent") : Durations.format(finalDuration),
                    "reason", reason.isEmpty() ? "-" : reason));
        });
    }

    private void unmute(CommandSource source, String[] args) {
        Messages messages = core.messages();
        if (args.length < 1) {
            help(source);
            return;
        }
        MuteScope scope = args.length > 1 ? MuteScope.parse(args[1]) : MuteScope.global();
        resolve(args[0]).thenAccept(target -> {
            if (target.isEmpty()) {
                source.sendMessage(messages.get("player-not-found", "player", args[0]));
                return;
            }
            Stores.KnownPlayer player = target.get();
            boolean removed = core.mutes().unmute(player.uuid(), scope, source.name());
            source.sendMessage(messages.get(removed ? "unmute-done" : "unmute-none", "player", player.name(), "scope", describe(scope)));
        });
    }

    private void globalMute(CommandSource source, String[] args) {
        Boolean on = args.length > 0 ? parseSwitch(args[0]) : Boolean.valueOf(!core.mutes().isGlobalMuteEnabled());
        if (on == null) {
            help(source);
            return;
        }
        core.mutes().setGlobalMuteEnabled(on, source.name());
        String text = core.messages().get(on ? "globalmute-on" : "globalmute-off");
        source.sendMessage(text);
        for (PlatformPlayer player : core.platform().players()) {
            if (!isSame(player, source)) {
                player.sendMessage(text);
            }
        }
    }

    private void serverMute(CommandSource source, String[] args) {
        if (args.length < 1) {
            help(source);
            return;
        }
        String server = findServer(args[0]);
        if (server == null) {
            help(source);
            return;
        }
        Boolean on = args.length > 1 ? parseSwitch(args[1]) : Boolean.valueOf(!core.mutes().isServerMuted(server));
        if (on == null) {
            help(source);
            return;
        }
        core.mutes().setServerMuted(server, on, source.name());
        String text = core.messages().get(on ? "servermute-on" : "servermute-off", "server", server);
        source.sendMessage(text);
        for (VoiceSession session : core.sessions().onServer(server)) {
            if (!isSame(session.player(), source)) {
                session.player().sendMessage(text);
            }
        }
    }

    private void status(CommandSource source, String[] args) {
        Messages messages = core.messages();
        if (args.length < 1) {
            help(source);
            return;
        }
        Optional<? extends PlatformPlayer> online = core.platform().player(args[0]);
        if (online.isEmpty()) {
            source.sendMessage(messages.get("player-not-found", "player", args[0]));
            return;
        }
        VoiceSession session = core.sessions().get(online.get().uuid());
        source.sendMessage(messages.get("status-header", "player", online.get().name()));
        if (session == null) {
            return;
        }
        line(source, "状态", session.state() + (session.transport() == null ? "" : " / " + session.transport()));
        line(source, "Mod", session.modVersion().isEmpty() ? "-" : session.modVersion() + " (" + session.loader() + " " + session.minecraftVersion() + ")");
        line(source, "子服", String.valueOf(session.server()));
        line(source, "正在说话", session.isSpeaking() ? "是" : "否");
        line(source, "优先级", String.valueOf(session.proxyPriority()));
        List<Mute> mutes = core.mutes().activeMutes(session.uuid());
        if (mutes.isEmpty()) {
            line(source, "禁麦", "无");
        }
        for (Mute mute : mutes) {
            line(source, "禁麦", describe(mute.scope()) + " / " + (mute.isPermanent() ? messages.raw("duration-permanent")
                    : Durations.format(mute.expiresAt() - System.currentTimeMillis())) + " / " + mute.reason());
        }
    }

    private void reload(CommandSource source) {
        try {
            core.reload();
            source.sendMessage(core.messages().get("reload-done"));
        } catch (Exception e) {
            source.sendMessage("&c" + e.getMessage());
            core.logger().error("Reload failed", e);
        }
    }

    private void help(CommandSource source) {
        for (String line : core.messages().lines("help")) {
            source.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void line(CommandSource source, String key, String value) {
        source.sendMessage(core.messages().raw("status-line", "key", key, "value", value));
    }

    private CompletableFuture<Optional<Stores.KnownPlayer>> resolve(String name) {
        Optional<? extends PlatformPlayer> online = core.platform().player(name);
        if (online.isPresent()) {
            PlatformPlayer player = online.get();
            return CompletableFuture.completedFuture(Optional.of(new Stores.KnownPlayer(player.uuid(), player.name(), System.currentTimeMillis())));
        }
        try {
            UUID uuid = UUID.fromString(name);
            return CompletableFuture.completedFuture(Optional.of(new Stores.KnownPlayer(uuid, name, 0)));
        } catch (IllegalArgumentException notUuid) {
            return core.stores().findPlayer(name).exceptionally(error -> Optional.empty());
        }
    }

    private boolean isScope(String text) {
        return MuteScope.GLOBAL_KEY.equals(text) || findServer(text) != null;
    }

    private String findServer(String text) {
        for (String server : core.platform().serverNames()) {
            if (server.equalsIgnoreCase(text)) {
                return server;
            }
        }
        return null;
    }

    private String describe(MuteScope scope) {
        Messages messages = core.messages();
        return scope.isGlobal() ? messages.raw("scope-global") : messages.raw("scope-server", "server", scope.server());
    }

    private static boolean isSame(PlatformPlayer player, CommandSource source) {
        return source.uuid() != null && source.uuid().equals(player.uuid());
    }

    private static Boolean parseSwitch(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "开" -> Boolean.TRUE;
            case "off", "false", "disable", "关" -> Boolean.FALSE;
            default -> null;
        };
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (PlatformPlayer player : core.platform().players()) {
            names.add(player.name());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
