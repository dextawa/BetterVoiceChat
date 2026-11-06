# BetterVoiceChat (BVC)

给 BungeeCord / Velocity 群组服用的 Minecraft 语音插件，用来替代 Simple Voice Chat。

- 只用 TCP，和 Minecraft **共用同一个端口**，不额外开放 UDP 端口，继承现有 CDN / 高防
- 服务器决定语音模式（范围麦 / 组队麦 / 自由麦），支持按键说话
- BC 统一禁麦：单人、单个子服、全服，可定时；只提供 API 接口，LiteBans 等插件可自行对接
- 滚动录音（总量上限 1GB）+ 举报证据包 + Web 面板审核（开发中）
- 指挥优先级、自动压低他人音量、子服内广播
- 声音永远不跨子服

完整方案见 [docs/DESIGN.md](docs/DESIGN.md)。

## 当前进度

| 部分 | 状态 |
|---|---|
| 协议（包格式、端口复用握手、加密） | ✅ 完成，有测试 |
| BC 核心（会话、鉴权、A/B 传输、查表转发、禁麦、指令、API） | ✅ 完成，有测试 |
| Velocity / BungeeCord 适配 | ✅ 完成，已在真实 Velocity 4.3.1、BungeeCord 26.1 上验证端口复用 |
| 子服插件（范围 / 组队 / 自由麦、按世界设置、旁观者、小游戏 API、Folia） | ✅ 完成，有测试，已在 Velocity + Paper 1.21.4 上端到端验证 |
| 服务端 Mod（子服插件的同一套逻辑，装在 Fabric / NeoForge / Forge 独立服务端上；和客户端 Mod 是同一个 jar） | ✅ 32 个版本都能构建；Fabric 1.21.4 / 1.20.1、NeoForge 1.21.4、Forge 1.20.1 在 Velocity 后面端到端通过（基础 13 项、规则 18 项），真实客户端能连上语音，Paper 和 Mod 子服之间换服正常。已知问题：NeoForge 1.21.4 服务端经过 Velocity 时，装了 NeoForge 的客户端会被断开（Velocity 4.3.1 解析不了服务端发来的命令列表包；服务端不装 BVC 也一样）；NeoForge 1.20.4 服务端经过 Velocity 时会踢掉原版客户端（去掉 Velocity 上的 BVC 插件也一样，直连正常；还没确认和服务端 Mod 有没有关系） |
| 客户端核心（连接、Opus、混音、3D 方位、按键说话 / 声控） | ✅ 完成，有测试（和 BC 核心在同一进程里联调） |
| 客户端 Mod 1.16.5、1.18.2、1.19.2、1.19.4、1.20.1 Fabric / Forge；1.20.4–1.21.11、26.1–26.3 Fabric / NeoForge（32 个 jar） | ✅ 31 个在真实客户端里进服并连上语音（Velocity + Paper 1.21.4 + ViaVersion/ViaBackwards）；1.16.5 Forge 的开发环境启动即崩（原版代码里的映射问题），静态检查通过，未真机验证 |
| 客户端 Mod 1.8.9、1.12.2 Forge | ✅ 在真实客户端里进服并连上语音（同一测试环境，另装 ViaRewind） |
| 优先级 / 广播 / 闪避、房间 API、对外接口（M6） | ✅ 完成，有测试；优先级和广播在 Velocity + Paper 1.21.4 上端到端验证 |
| 录音、举报、Web 面板 | ✅ 完成，有测试；在 Velocity + Paper 1.21.4 上端到端验证（机器人说真实 Opus → `/vreport` → 面板里查看证据、回放、处理、禁麦、审计），回放用 ffmpeg 和 Chrome 解码验证 |

## 构建

需要 JDK 21 或更新版本。

```bash
./gradlew build
```

产物：

- `proxy/velocity/build/libs/BetterVoiceChat-Velocity-*.jar`（Velocity 3.4+ / 4.x）
- `proxy/bungee/build/libs/BetterVoiceChat-Bungee-*.jar`（BungeeCord，Java 17+）
- `backend/bukkit/build/libs/BetterVoiceChat-Bukkit-*.jar`（每个子服装一个；Spigot / Paper / Folia，1.8+）
- Mod 子服（Fabric / NeoForge / Forge 独立服务端）：直接装客户端 Mod 的 jar（见 `client/modern`），它在独立服务端上会以子服的身份运行，配置在 `config/bettervoicechat/config.yml`，按世界设置时世界名写维度 ID（如 `"minecraft:the_nether"`）。Mod 子服没有权限插件，`bvc.priority.*` / `bvc.broadcast` 只能通过 API 设置；`/bvcs` 需要 2 级 OP。

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
