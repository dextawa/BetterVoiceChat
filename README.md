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
| 客户端核心（连接、Opus、混音、3D 方位、按键说话 / 声控） | ✅ 完成，有测试（和 BC 核心在同一进程里联调） |
| 客户端 Mod 26.3 Fabric / NeoForge | 🚧 能构建出 jar，还没在真实游戏里跑过 |
| 客户端 Mod 1.16.5、1.18.2、1.19.2、1.19.4、1.20.1 Fabric / Forge；1.20.4–1.21.11、26.1–26.2 Fabric / NeoForge | 🚧 能构建出 jar，还没在真实游戏里跑过 |
| 客户端 Mod 1.8.9 Forge 及其他版本 | ⏳ 未开始 |
| 录音、举报、Web 面板 | ⏳ 未开始 |

## 构建

需要 JDK 21 或更新版本。

```bash
./gradlew build
```

产物：

- `proxy/velocity/build/libs/BetterVoiceChat-Velocity-*.jar`（Velocity 3.4+ / 4.x）
- `proxy/bungee/build/libs/BetterVoiceChat-Bungee-*.jar`（BungeeCord，Java 17+）
- `backend/bukkit/build/libs/BetterVoiceChat-Bukkit-*.jar`（每个子服装一个；Spigot / Paper / Folia，1.8+）

子服的语音规则在子服的 `plugins/BetterVoiceChat/config.yml` 里设置（范围麦 / 组队麦 / 自由麦、半径、只允许按键说话、按世界设置、旁观者），BC 的设置在 BC 的 `plugins/bettervoicechat/config.yml`（Velocity）或 `plugins/BetterVoiceChat/config.yml`（BungeeCord）里（传输方式、是否强制装 Mod、码率）。

端到端测试见 [tools/e2e](tools/e2e/README.md)。

## 模块

| 目录 | 说明 |
|---|---|
| `protocol` | 线上协议，客户端 / BC / 子服共用（Java 8） |
| `api` | 给其他插件用的 API（Java 8） |
| `proxy/common` | BC 端核心逻辑（Java 17） |
| `proxy/velocity`、`proxy/bungee` | 平台适配 |
| `backend/bukkit` | 子服插件（Java 8，1.8+） |
| `client/core` | 客户端核心，不依赖 MC（Java 8） |

## 许可证

[MIT](LICENSE)
