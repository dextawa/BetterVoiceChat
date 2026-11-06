# BetterVoiceChat (BVC)

给 BungeeCord / Velocity 群组服用的 Minecraft 语音插件，用来替代 Simple Voice Chat。

- 只用 TCP，和 Minecraft **共用同一个端口**，不额外开放 UDP 端口，继承现有 CDN / 高防
- 服务器决定语音模式（范围麦 / 组队麦 / 自由麦），支持按键说话
- BC 统一禁麦：单人、单个子服、全服，可定时；只提供 API 接口，LiteBans 等插件可自行对接
- 滚动录音（总量上限 1GB）+ 举报证据包 + Web 面板审核
- 指挥优先级、自动压低他人音量、子服内广播
- 声音永远不跨子服

完整方案见 [docs/DESIGN.md](docs/DESIGN.md)。

## 当前进度

版本 0.1.0。各部分都有单元测试；“实测”指在真实服务端 / 真实客户端里跑过端到端测试（`tools/e2e`）。

| 部分 | 状态 |
|---|---|
| 协议（包格式、端口复用握手、加密） | ✅ |
| BC 插件：Velocity | ✅ 实测（Velocity 4.3.1）：基础、规则、优先级 / 广播、换服、举报与 Web 面板 |
| BC 插件：BungeeCord | ✅ 实测（BungeeCord 26.1）：基础、规则、优先级 / 广播、举报与 Web 面板 |
| 子服插件：Paper / Spigot | ✅ 实测：Paper 1.21.4、Paper 1.8.8（1.8 上没有跨世界传送命令，按世界设置那一项跳过） |
| 子服插件：Folia | ✅ 实测：Folia 1.21.4（Folia 没有 `/team`，组队用 API / `/bvcs group` 测） |
| 服务端 Mod（和客户端 Mod 同一个 jar） | ✅ 实测：Fabric 1.21.4 / 1.20.1、NeoForge 1.21.4、Forge 1.20.1 / 1.17.1 |
| 数据库 | ✅ H2（默认）；MariaDB 11.4 实测（禁言、举报、录音索引、面板账号和会话都写入成功，数据库重启后自动重连） |
| 客户端：设置界面、选择麦克风 / 扬声器、麦克风测试、单人音量 | ✅ 实测（在真实客户端里自动打开设置界面、逐项切换、跑麦克风测试，测试期间语音不断） |
| 客户端 Mod 1.14.4 Forge；1.15.2、1.16.5、1.17.1、1.18.2、1.19.2、1.19.4、1.20.1、1.20.2 Fabric / Forge；1.20.4–1.21.11、26.1–26.3 Fabric / NeoForge（39 个 jar） | ✅ 37 个实测：真实客户端经 Velocity 进服、连上语音、打开设置界面逐项切换、跑麦克风测试且语音不断。1.16.5 Forge、1.17.1 Forge 见已知问题 |
| 客户端 Mod 1.8.9、1.9.4、1.10.2、1.11.2、1.12.2 Forge（5 个 jar） | ✅ 5 个全部实测（同上） |
| 录音、举报、Web 面板 | ✅ 实测（Velocity 和 BungeeCord 上，机器人说真实 Opus → `/vreport` → 面板查看证据、回放、处理、禁麦、审计） |
| 优先级 / 广播 / 闪避、房间 API、对外接口 | ✅ 实测 |
| BC 和子服之间的共享密钥、面板登录重启后保留 | ✅（单元测试） |

### 已知问题

- 1.13.x 没有客户端 Mod：1.13 没有 Fabric，Forge 1.13.2 只有测试版，构建工具不支持。
- 1.14.4 只有 Forge：1.14 的 Fabric API 没有 HUD 接口。
- 1.16.5 Forge、1.17.1 Forge 的客户端没在真实客户端里跑过：构建工具的开发环境启动不了这两个版本（1.16.5 在原版代码里崩，1.17.1 找不到 NightConfig），和 BVC 的代码无关。它们和旁边已实测的版本（1.15.2 Forge、1.18.2 Forge）走同一套代码；1.17.1 Forge 作为服务端 Mod 已实测。
- NeoForge 1.21.4 服务端放在 Velocity 后面时，装了 NeoForge 的客户端会被断开（Velocity 解析不了服务端发来的命令列表，服务端不装 BVC 也一样）；NeoForge 1.20.4 服务端放在 Velocity 后面时会踢掉原版客户端（直连正常）。
- 服务端 Mod 没有权限插件：优先级和广播只能通过 API 设置，`/bvcs` 需要 2 级 OP。

## 构建

需要 JDK 21（26.x 客户端还需要 JDK 25；旧版 Forge 需要的 JDK 8 / 17 由 Gradle 自动下载）。

```bash
tools/release.sh
```

会构建所有插件和 Mod，并把 jar 收集到 `dist/<版本>/`：`proxy/`（BC 插件）、`server/`（子服插件）、`mods/`（客户端 / 服务端 Mod，每个 Minecraft 版本和加载器一个）、API jar，以及 `SHA256SUMS`。只想编译插件可以用 `./gradlew build`。

产物：

- `proxy/velocity/build/libs/BetterVoiceChat-Velocity-*.jar`（Velocity 3.4+ / 4.x）
- `proxy/bungee/build/libs/BetterVoiceChat-Bungee-*.jar`（BungeeCord，Java 17+）
- `backend/bukkit/build/libs/BetterVoiceChat-Bukkit-*.jar`（每个子服装一个；Spigot / Paper / Folia，1.8+）
- Mod 子服（Fabric / NeoForge / Forge 独立服务端）：直接装客户端 Mod 的 jar（见 `client/modern`），它在独立服务端上会以子服的身份运行，配置在 `config/bettervoicechat/config.yml`，按世界设置时世界名写维度 ID（如 `"minecraft:the_nether"`）。Mod 子服没有权限插件，`bvc.priority.*` / `bvc.broadcast` 只能通过 API 设置；`/bvcs` 需要 2 级 OP。

### 数据库和安全

- BC 默认用内置的 H2。要用 MySQL / MariaDB，把 JDBC 驱动（`mysql-connector-j` 或 `mariadb-java-client` 的 jar）放进 BC 插件目录的 `lib/` 文件夹，然后在 BC 的 `config.yml` 里设置 `storage.type: MYSQL`（或 `MARIADB`）和连接信息。切换数据库不会迁移旧数据。
- 在 BC 的 `security.backend-secret` 和每个子服的 `backend-secret` 里设成同一个值后，BC 只接受知道密钥的子服发来的语音规则，子服也只听知道密钥的 BC。用于防止有人绕过 BC 直连子服冒充 BC。

### 客户端

默认按 K 打开设置：说话方式、声控灵敏度（显示当前音量）、麦克风增益、语音音量、选择麦克风和扬声器、测试麦克风（可以听到自己）、HUD 开关、单独调某个玩家的音量或屏蔽。设置保存在 `config/bettervoicechat.properties`。

子服的语音规则在子服的 `plugins/BetterVoiceChat/config.yml` 里设置（范围麦 / 组队麦 / 自由麦、半径、只允许按键说话、按世界设置、旁观者），BC 的设置在 BC 的 `plugins/bettervoicechat/config.yml`（Velocity）或 `plugins/BetterVoiceChat/config.yml`（BungeeCord）里（传输方式、是否强制装 Mod、码率）。

### 录音、举报和 Web 面板

- BC 把转发过的语音原样录下来（Opus，不转码），存在 `plugins/bettervoicechat/recordings/`，总量默认上限 1GB，满了删最旧的。**请在服务器规则或进服提示里告知玩家语音会被录音。**
- 玩家用 `/vreport <玩家> [原因]` 举报（不需要权限，有冷却）。证据包包含举报前 5 分钟、举报后 2 分钟里被举报人和举报人说的话，以及举报人这段时间听到的所有人的话，单独存放在 `reports/`，不会被滚动删除。拥有 `bvc.report.notify` 的管理员会收到通知。
- Web 面板默认监听 `127.0.0.1:8790`，建议用 Nginx 等反向代理加 HTTPS，并把 `web.public-url` 设成对外地址。
  - 在游戏里执行 `/bvc web` 获取 5 分钟内有效的一次性登录链接；账号权限跟随 `bvc.web.review`（查看举报和录音）、`bvc.web.mute`（禁麦）、`bvc.web.admin`（账号管理、审计日志）。
  - 登录后可以在“我的账号”里设置密码，以后不进游戏也能登录；第一次部署时也可以在 BC 控制台执行 `/bvc web <用户名> <密码>` 创建管理员账号。
  - 功能：举报队列与处理、多轨回放（按说话人分轨，可静音 / 独听，可只播举报人听到的部分）、按玩家 / 子服 / 时间搜索录音、禁麦与解禁、审计日志（谁看了哪个举报、听了哪段录音、做了什么操作）。

### 给其他插件用的 API

依赖 `api` 模块（`top.dext.bvc:api`，只有接口，Java 8）。只提供接口，不内置任何联动（LiteBans、Discord 等由服主或第三方自己对接）。

- **BC 端** `BvcProxy.get()`：
  - `mutes()`：禁麦 / 解禁 / 查询、全服和子服开关，`MuteListener` 监听禁麦变化（例如在 LiteBans 禁言事件里调用 `mute`）。
  - `player(uuid)` / `players()`：在线玩家的语音状态（是否连上、所在子服、是否在说话、优先级、客户端闭麦 / 关闭收听、传输方式）。
  - `reportService()`：提交举报、查询举报、修改处理状态（证据和回放留在 Web 面板）。
  - `addListener(VoiceListener)`：语音连上 / 断开、举报证据生成完成。
- **子服** `BvcServer.get()`（Bukkit 插件和服务端 Mod 相同）：
  - 默认区域、房间（创建 / 删除 / 列出 / 成员）、玩家所在房间、分组、优先级、能否说话 / 收听 / 广播，以及读回这些设置。
  - `hasVoice(uuid)`、`addListener(VoiceStateListener)`：玩家语音连上或断开时通知。
  - `mute` / `unmute`：通过 BC 的禁麦系统禁麦。
- 优先级取 BC 权限 `bvc.priority.<n>`、子服权限 `bvc.priority.<n>` 和子服 API 三者的最大值（0–255）；高优先级的人说话时，客户端把其他人压低 `audio.ducking-db` 分贝；每个听众同时最多收 `audio.max-streams-per-listener` 路，优先级高的优先。有 `bvc.broadcast`（BC 或子服任一处）或 API 允许广播的人按广播键时，整个房间都能听到，不跨子服。

端到端测试见 [tools/e2e](tools/e2e/README.md)。

## 模块

| 目录 | 说明 |
|---|---|
| `protocol` | 线上协议，客户端 / BC / 子服共用（Java 8） |
| `api` | 给其他插件用的 API（Java 8） |
| `proxy/common` | BC 端核心逻辑（Java 17） |
| `proxy/velocity`、`proxy/bungee` | 平台适配 |
| `backend/common` | 子服核心，不依赖服务端软件：语音判定、和 BC 的通信、配置、`/bvcs`、API 实现（Java 8） |
| `backend/bukkit` | 子服插件（Java 8，1.8+） |
| `client/core` | 客户端核心，不依赖 MC（Java 8） |

## 许可证

[MIT](LICENSE)
