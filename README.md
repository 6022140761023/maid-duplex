# Maid Duplex

Minecraft「车万女仆」附属模组 + DeepSeek Harness（DSH）单会话插件。

让同一只女仆与一条 DSH 对话共享文字上下文，并以该 DSH 会话**实际使用的人设卡**作为女仆的人设来源。MC 与 DSH 各自保留自己的工具和执行权限。

## 适配版本（Maid Duplex 0.1.0）

| 部件 | 已验证版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Touhou Little Maid | 1.5.3 |
| DSH 桌面宿主 | Deepseek Harness EAC 5.3.6，原生 Cordis 插件机制 |
| Java | JDK 17（本机构建验证：17.0.20.1） |
| Node.js | 最低要求 22；本机测试版本 24.16.0；CI 配置为 22 |

模组与插件版本均为 **0.1.0**。已实机验证的组合是 **Minecraft 1.20.1 + Forge 47.4.21 + Touhou Little Maid 1.5.3 + Deepseek Harness EAC 5.3.6**。

模组元数据允许 Forge `>=47.4.21、<48`，但这一范围不代表其他 Forge 版本已经实测；Minecraft 限定 `>=1.20.1、<1.20.2`，TLM 严格限定 `1.5.3`。不适用于 Fabric、NeoForge、Minecraft 1.21 或其他 TLM 版本。

其他 DSH 构建不保证接口兼容；插件依赖该宿主的原生 Cordis 及 `@deepseek-ai/dsh-system-prompt`、`@deepseek-ai/dsh-tools` 接口。DSH 插件不是旧版 `maid-memory` 隔离插件，也不能直接粘贴到 `cordis_define` 动态沙箱中。

## 工作方式

- 人设来自绑定会话唯一的 `deployment:persona` 段，使用 DSH 自己的变量渲染，每次 MC 请求重新读取。
- 替换女仆原有身份、性格和固定称呼，保留 MC 世界背景、感知、工具执行规则及气泡／语音两段输出契约。
- 只共享用户文字与最终助手回复。工具定义、调用、返回和思考内容不进入共享记录。
- DSH 的启用操作绑定调用它的单个会话；其他对话和子 agent 不继承该绑定。
- MC 精确绑定世界标识、主人 UUID 和女仆 UUID，主人校验失败时拒绝访问。
- 桥接仅监听同机 `127.0.0.1:8790`，使用独立配对 token。

## 快速安装

### 1. 安装 MC 模组

关闭 Minecraft，将 [maid-duplex-0.1.0.jar](dist/maid-duplex-0.1.0.jar) 放入目标实例的 `mods` 目录。原版 Touhou Little Maid 1.5.3 仍需单独安装。

启动游戏，进入目标存档一次，让 Forge 生成：

```text
<实例>/saves/<存档>/serverconfig/maidduplex-server.toml
```

随后**保存并退出游戏**。不要在该世界仍运行时填写配对配置；实机发现运行期间写入的配置可能被游戏覆盖。

### 2. 安装 DSH 插件

按照 [DSH 安装说明](dsh-plugin/README.md) 将插件通过宿主的原生 Cordis 加载机制安装，然后重启 DSH。

在你要绑定的那条对话里明确要求调用 `maid_duplex`：

```json
{
  "action": "enable",
  "world": "为该存档生成的随机唯一标识",
  "player": "主人玩家的 UUID",
  "maid": "女仆的 UUID"
}
```

插件从真实调用会话确定 sessionId，不接受手填另一个会话 ID。成功后返回 `enabled`、`sessionId`、`pairingFile`、`port`，不在回复中显示 token。

### 3. 配置 MC 配对

确认 Minecraft 已关闭。先备份目标存档的配置，再在本机将 `pairingFile` 中的字段对应填入：

| DSH 配对字段 | MC 配置字段 |
|---|---|
| `binding` | `binding` |
| `token` | `token` |
| `world` | `worldId` |
| `player` | `playerUuid` |
| `maid` | `maidUuid` |

最后设置 `enabled = true`，以 UTF-8 无 BOM 保存。格式见 [示例](examples/maidduplex-server.toml.example)。每个存档独立配对，不能复制另一存档的配置。

女仆的 LLM 站点必须直连 HTTPS 模型接口，例如 DeepSeek。旧的 `:8788` 人设中转会再次注入其他人设，因此被拒绝；TTS 可以继续使用独立的语音中转。

### 4. 验证

重新启动游戏进入存档，确认 `enabled=true` 和配对字段仍保留。

靠近并对准自己拥有的女仆，按聊天键（默认 `T`）打开**女仆专用对话框**。普通世界聊天不触发女仆 AI。也可使用已经配置好的语音识别入口；STT 服务需单独运行。

向女仆说一条容易辨认的话，再在绑定 DSH 对话中询问；反向测试也一样，另一端在下一次模型回合读取共享上下文。游戏端的自然语言工具指令仍由原 TLM 执行。

健康检查：

```sh
curl http://127.0.0.1:8790/health
```

会话内管理：`{"action":"status"}` / `{"action":"disable"}`。DSH 重启后，需要在原会话重新 `enable`，相同绑定复用已有配对及历史。

## 源码与测试

```text
mc-addon/       Forge 模组源码与 Java 测试
dsh-plugin/     DSH 原生插件源码与 Node.js 测试
examples/       仅含占位值的安装示例
dist/           可安装的附属模组 JAR 与 SHA256
scripts/        独立 Java 策略测试
.github/        持续集成配置
```

安装 Node.js 22+ 和 JDK 17 后，在仓库根目录运行：

```sh
npm test
```

这会运行 DSH 插件单元测试、MC 提示词与模型接口策略测试，以及回环 HTTP 桥接测试，不需要真实角色卡、存档或 DSH 凭据。首次运行会从 Maven Central 下载并校验 Gson 2.10.1；离线环境可通过 `GSON_JAR` 指向相同版本的本地 JAR。

完整 Forge 构建和桥接 HTTP 测试见 [MC 构建说明](mc-addon/README.md)。对本机已安装 DSH 的原生兼容测试见 [DSH 安装说明](dsh-plugin/README.md)。

## 验证状态与限制

本项目已在上述环境中实际加载，作者测试用户已确认配对及链路跑通；源码测试覆盖会话隔离、提示词处理及桥接协议。详细测试范围见 [验证说明](docs/VERIFICATION.md)。

- 当前一个插件实例只支持一个活动绑定，不会自动跟随当前标签页切换。
- 共享历史保留最近 200 条；每侧注入至多 12000 字符的另一侧对话。启用时恢复本会话最近至多 100 个已完成回合。
- 共享不会主动让另一端发言，也不会伪造成另一端原生输入。
- 最终回复中的自然语言信息会共享，不能把它当作内容级隐私过滤器。
- MC 最终回复只有内存重试，没有跨重启的持久发送队列。
- 不删除原有游戏聊天历史，绑定前的人格台词可能仍存在。
- 仅支持桥接与 MC 服务端部署在同一台机器，不暴露公网接口。
- 独立 STT/TTS 服务、API key、模型费用由使用者自行配置。

## 配对数据

配对 token、API key、角色卡及私人对话历史不要提交到 Git。本仓库只包含代码和占位示例，不包含其他模组的 JAR、游戏存档或 DSH 安装文件。

MIT License。Touhou Little Maid、Forge、Minecraft 和 DSH 的名称及原始软件属于各自项目；本项目是独立附属项目。
