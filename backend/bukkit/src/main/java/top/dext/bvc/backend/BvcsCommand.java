package top.dext.bvc.backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.backend.voice.PlayerPlan;
import top.dext.bvc.backend.voice.PlayerSnapshot;
import top.dext.bvc.protocol.packet.BackendToProxy.PlayerFlags;

/**
 * {@code /bvcs}: see what the sub-server decided and reload the config.
 */
final class BvcsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("info", "player", "rooms", "reload");

    private final BvcBukkitPlugin plugin;

    BvcsCommand(BvcBukkitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            info(sender);
        } else if (sub.equals("player") && args.length > 1) {
            player(sender, args[1]);
        } else if (sub.equals("rooms")) {
            rooms(sender);
        } else if (sub.equals("reload")) {
            plugin.reload();
            send(sender, "&a配置已重新加载。");
        } else {
            send(sender, "&b/bvcs info &7- 连接与区域概况");
            send(sender, "&b/bvcs player <玩家> &7- 查看某个玩家的语音判定");
            send(sender, "&b/bvcs rooms &7- 列出插件创建的房间");
            send(sender, "&b/bvcs reload &7- 重新加载配置");
        }
        return true;
    }

    private void info(CommandSender sender) {
        ProxyLink link = plugin.link();
        VoiceEngine engine = plugin.engine();
        send(sender, "&bBetterVoiceChat 子服 " + plugin.getDescription().getVersion());
        send(sender, "&7BC 连接：" + (link.proxySeen() ? "&a已连接（BC 插件 " + link.proxyVersion() + "）"
                : "&e尚未收到 BC 消息（有玩家进服后才会建立）"));
        send(sender, "&7已绑定玩家：&f" + link.boundCount() + "&7，参与语音计算：&f" + engine.plannedCount());
        long tickAge = engine.tickAge();
        send(sender, "&7上次计算：&f" + (tickAge < 0 ? "还没有" : tickAge + " 毫秒前，耗时 " + engine.lastTickMicros() + " 微秒"));
        send(sender, "&7默认区域：&f" + describe(engine.rules().defaultZone));
        for (Map.Entry<String, ZoneSettings> world : engine.rules().worldZones.entrySet()) {
            send(sender, "&7世界 &f" + world.getKey() + "&7：&f" + describe(world.getValue()));
        }
        send(sender, "&7房间数：&f" + engine.rooms.size() + "&7，旁观者：&f" + engine.rules().spectators);
    }

    private void player(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            send(sender, "&c玩家不在线：" + name);
            return;
        }
        ProxyLink.Binding binding = plugin.link().binding(target.getUniqueId());
        if (binding == null) {
            send(sender, "&e" + name + " 还没有被 BC 绑定（BC 没装 BVC，或刚进服）。");
            return;
        }
        if (!binding.voiceEnabled) {
            send(sender, "&e" + name + " 没有连上语音（没装 Mod 或连接中）。编号 " + binding.id);
            return;
        }
        PlayerPlan plan = plugin.engine().planOf(target.getUniqueId());
        if (plan == null) {
            send(sender, "&e" + name + " 还没有被计算，请稍后再试。");
            return;
        }
        send(sender, "&b" + name + " &7(编号 " + plan.id + ")");
        PlayerSnapshot snapshot = plugin.engine().snapshotOf(target.getUniqueId());
        if (snapshot != null) {
            send(sender, String.format(Locale.ROOT, "&7采样位置：&f%s %.1f %.1f %.1f &7（%d 毫秒前）", snapshot.world,
                    snapshot.x, snapshot.y, snapshot.z, plugin.engine().sampleAge(target.getUniqueId())));
        }
        send(sender, "&7区域：&f" + (plan.zoneName.isEmpty() ? "默认" : plan.zoneName) + " &7- &f" + describe(plan.zone));
        send(sender, "&7房间编号：&f" + plan.roomId + "&7，分组：&f" + (plan.groupKey == null ? "无" : plan.groupKey));
        List<String> names = new ArrayList<String>();
        for (int id : plan.neighbors) {
            PlayerPlan neighbor = plugin.engine().planOf(id);
            Player player = neighbor == null ? null : Bukkit.getPlayer(neighbor.uuid);
            names.add(player == null ? "#" + id : player.getName());
        }
        send(sender, "&7范围内能听到他的人（" + names.size() + "）：&f" + (names.isEmpty() ? "-" : join(names)));
        send(sender, "&7优先级：&f" + plan.priority + "&7，能说话：&f" + yesNo(plan.flags & PlayerFlags.CAN_SPEAK)
                + "&7，能听：&f" + yesNo(plan.flags & PlayerFlags.CAN_HEAR)
                + "&7，能广播：&f" + yesNo(plan.flags & PlayerFlags.CAN_BROADCAST));
    }

    private void rooms(CommandSender sender) {
        Map<String, ZoneSettings> rooms = plugin.engine().rooms;
        if (rooms.isEmpty()) {
            send(sender, "&7当前没有房间。");
            return;
        }
        List<String> names = new ArrayList<String>(rooms.keySet());
        Collections.sort(names);
        for (String name : names) {
            send(sender, "&f" + name + " &7- " + describe(rooms.get(name)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(player.getName());
                }
            }
        }
        return out;
    }

    static String describe(ZoneSettings zone) {
        String mode;
        switch (zone.mode()) {
            case PROXIMITY:
                mode = "范围麦 " + (int) zone.radius() + " 格";
                break;
            case TEAM:
                mode = "组队麦";
                break;
            case FREE:
                mode = "自由麦";
                break;
            case TEAM_PROXIMITY:
                mode = "组队 + 范围 " + (int) zone.radius() + " 格";
                break;
            default:
                mode = "不开放语音";
                break;
        }
        return mode + (zone.activation() == top.dext.bvc.api.server.Activation.PTT_ONLY ? "，只允许按键说话" : "");
    }

    private static String yesNo(int bit) {
        return bit != 0 ? "是" : "否";
    }

    private static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size() && i < 30; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i));
        }
        if (names.size() > 30) {
            out.append(" ...");
        }
        return out.toString();
    }

    private static void send(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }
}
