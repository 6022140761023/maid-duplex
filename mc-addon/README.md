# MC 附属模组

目标：Minecraft 1.20.1、Forge 47.4.21、Touhou Little Maid 1.5.3、Java 17。

## 构建

安装 JDK 17 与 Gradle 8.8。将**原版** TLM JAR 放入 `libs`，并使用依赖名 `touhoulittlemaid-1.5.3-forge+mc1.20.1.jar`；也可通过 `-PtlmDir=绝对目录` 指定依赖目录。依赖 JAR 不提交到仓库。

```sh
cd mc-addon
gradle build
```

首次构建需联网获取 ForgeGradle、Forge、Mixin 和其他依赖，已缓存后可用 `gradle --offline build`。不要禁用 TLS 校验来绕过下载错误。

输出：`build/libs/maid-duplex-0.1.0.jar`。`reobfJar` 对 MC API 调用做重混淆，`verifyModPackMetadata` 校验根目录 `pack.mcmeta` 的 `pack_format=15`。

`check` 运行 PromptPolicy、ProviderPolicy、BridgeClient HTTP 测试及资源包元数据校验。不依赖游戏存档、实际配对 token 或私人角色卡。

不安装 Forge 依赖也可在仓库根目录执行 `npm run test:mc`，这会独立编译三个核心类并测试。测试脚本下载并校验 Gson 2.10.1，或使用 `GSON_JAR` 指定的本地同版本 JAR。

## 安装

将构建后的 JAR 放入目标实例的 `mods`，原版 TLM 仍需安装。完整配对步骤见[根目录 README](../README.md)。

配置是每存档独立的 SERVER 配置。生成配置后，**保存退出并关闭 Minecraft，再填写配对字段**。备份原文件，以 UTF-8 无 BOM 保存，重新启动并核验 `enabled=true` 与所有字段仍存在。

该模组只处理绑定女仆的请求，保留 TLM 工具链。未知提示词格式、人设不可用、配对失败或不兼容的本地 HTTP LLM 接口会拒绝绑定请求，不会静默使用旧人格。

绑定玩家必须是该女仆的主人。其他女仆不受该配对配置影响。多人联机需要服务端与 DSH 桥接同机部署，并保持游戏及模组版本兼容。
