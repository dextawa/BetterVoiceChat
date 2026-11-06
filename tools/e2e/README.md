# 端到端测试

用真实的 Velocity + 子服（Paper，或装了服务端 Mod 的 Fabric / NeoForge / Forge）和机器人客户端测试整条链路：
机器人（BVC 协议）→ BC 插件 → 子服插件 / 服务端 Mod → BC 查表转发 → 另一个机器人。

两个机器人：BotA 走传输方式 B（插件消息），BotB 走传输方式 A（同端口的第二条连接，ECDH + AES）。
机器人用 [mineflayer](https://github.com/PrismarineJS/mineflayer)，行为和原版客户端一致（确认传送、确认区块等），BVC 的消息走它底层的连接。

## 场景

| 命令 | 内容 |
|---|---|
| `node e2e.js 25599 1.21.4` | 基础：握手、A/B 两种传输、子服下发区域、双向听到、顺序、结束包、心跳、不回声 |
| `node e2e.js 25599 1.21.4 zones <子服 config.yml 路径>` | 规则：范围内外（48 格边界）、配置重载切换组队麦、计分板队伍、队伍不限距离、退队、自由麦、旁观者两种策略、按世界设置区域 |
| `node e2e.js 25599 1.21.4 switch lobby,fabric,neoforge` | 换服：一个机器人用 `/server` 依次进入这些子服，每个子服都要下发自己的区域 |
| `node e2e.js 25599 1.21.4 priority` | 优先级 / 广播：OP 从子服权限拿到优先级 200 和广播权，远处普通说话听不到、广播能听到并带优先级；非 OP 的广播不转发 |
| `node report.js 25599 1.21.4` | 举报：被举报人说一段真实的 Opus（`tones/`），举报人 `/vreport`，然后通过 Web 面板接口检查证据包、回放音频、录音搜索、处理举报和审计日志 |

`zones` 会临时修改子服的 `config.yml`，结束时恢复。它通过 RCON 执行 `tp`、`team`、`gamemode`、`bvcs reload` 等命令。

## 准备

1. 构建：在仓库根目录执行 `./gradlew build`。
2. Paper（例如 1.21.4）：
   - `plugins/` 放入 `backend/bukkit/build/libs/BetterVoiceChat-Bukkit-*.jar`
   - `server.properties`：
     ```
     online-mode=false
     server-ip=127.0.0.1
     server-port=25601
     difficulty=peaceful
     enable-rcon=true
     rcon.port=25611
     rcon.password=<自己设一个>
     ```
3. Velocity（3.4+ 或 4.x）：
   - `plugins/` 放入 `proxy/velocity/build/libs/BetterVoiceChat-Velocity-*.jar`
   - 先启动一次生成 `velocity.toml`，然后：
     ```bash
     python configure_velocity.py path/to/velocity.toml
     ```
     它会把子服指向 `127.0.0.1:25601`、关闭正版验证和转发。另外把 `bind` 改成 `127.0.0.1:25599`，`login-ratelimit` 改成 `0`（两个机器人同时登录）。
4. 安装依赖并运行：
   ```bash
   npm install --ignore-scripts
   node e2e.js 25599 1.21.4
   ```

必须用环境变量 `BVC_RCON_PASSWORD` 传入上面设置的 RCON 密码；`BVC_HOST`、`BVC_RCON_PORT` 可覆盖默认值。

## 优先级场景

`priority` 依赖子服给 OP 的两个权限：`bvc.priority.200`（Bukkit 默认所有权限节点对 OP 生效）和 `bvc.broadcast`（默认谁都没有）。在 Paper 的 `permissions.yml` 里加：

```yaml
e2e.broadcaster:
  default: op
  children:
    bvc.broadcast: true
```

## 其他服务端

- **BungeeCord**：BC 端口换成 BungeeCord 的（例如 `node e2e.js 25598 1.21.4`）；Web 面板端口另设（`BVC_WEB_URL`）。
- **1.8–1.17 的子服**：机器人版本要和子服一致（`node e2e.js 25598 1.8.8`）。脚本会自动换用老版本的命令（`scoreboard teams`），地面高度用 y=4；1.8–1.12 没有跨世界传送命令，按世界设置那一项会跳过。
- **Folia**：没有 `/team`，设 `BVC_TEAMS=api`，脚本改用 `/bvcs group` 设置分组。
- **服务端 Mod**：没有权限插件，`priority` 场景不适用。

## 举报场景

`report.js` 需要 BC 的 Web 面板（默认 `http://127.0.0.1:8790`，可用 `BVC_WEB_URL` 覆盖）和一个面板账号。先在 BC 控制台执行 `bvc web e2eadmin <密码>` 创建账号，再用 `BVC_WEB_PASSWORD` 传入密码（`BVC_WEB_USER` 可改用户名）。为了不等太久，可以在 BC 的 `config.yml` 里把 `reports.before-seconds` / `after-seconds` 调小（例如 30 / 5），`cooldown-seconds` 设成 0。

## 测服务端 Mod

服务端 Mod 就是客户端 Mod 的同一个 jar（`client/modern/versions/<版本>-<加载器>/build/libs/`），放进 Fabric / NeoForge / Forge 独立服务端的 `mods/`（Fabric 另需 Fabric API）。配置文件在 `config/bettervoicechat/config.yml`。在 `velocity.toml` 的 `[servers]` 里加上这个子服，然后：

```bash
BVC_SERVER=fabric BVC_RCON_PORT=<该服的 rcon.port> node e2e.js 25599 1.21.4
BVC_SERVER=fabric BVC_RCON_PORT=<该服的 rcon.port> BVC_NETHER_WORLD=minecraft:the_nether BVC_NETHER_Y=129 node e2e.js 25599 1.21.4 zones path/to/config/bettervoicechat/config.yml
```

- `BVC_SERVER`：机器人进入的子服在 `velocity.toml` 里的名字（默认 `lobby`）。
- `BVC_NETHER_WORLD`：配置里下界的名字。Bukkit 用世界文件夹名 `world_nether`，Mod 用维度 ID `minecraft:the_nether`。
- `BVC_NETHER_Y`：传送到下界的高度。新生成的下界在 y=70 常常是实心的，用 129（下界顶层上方）更稳。
- Velocity 不转换版本：机器人的版本要和子服一致（例如 1.20.1 的 Fabric 子服用 `node e2e.js 25599 1.20.1`）。

## 注意

- 两个场景之间 Paper 会记住机器人的位置，所以脚本开始时会用 RCON 把它们传送到一起。
- 输出每一项 `PASS` / `FAIL`，全部通过时退出码为 0。
