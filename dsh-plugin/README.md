# DSH 插件

`bootstrap.mjs` 注册会话内工具 `maid_duplex`；`index.mjs` 负责绑定后的人设读取、上下文注入及桥接。

## 已验证的本地源安装方式

适用于使用 `web-desktop` profile 的 Deepseek Harness EAC 5.3.6。其他宿主应使用其原生 Cordis 插件加载机制。

1. 关闭 DSH。
2. 将本目录中的 `package.json` 与所有 `.mjs` 文件复制到 profile 下的 `node_modules/dsh-maid-duplex/`。Windows 本地 profile 通常位于 `%USERPROFILE%/.dsh/profiles/web-desktop/`。
3. 备份 profile 的 `cordis.patch.yml`，合并 [安装示例](../examples/cordis.patch.yml.example)，保留其他插件条目。
4. `dataDir` 指向单独的绝对路径，用于私人配对和聊天记录。`runtimeRoot` 指向当前 DSH 安装的 `node_modules`；正常包管理安装且 peerDependencies 已满足时可省略。
5. 重启 DSH，在插件列表确认 `maid-duplex` 已启用。此时仅注册工具，并不自动绑定或采集对话。
6. 在目标对话内按照根目录 README 的参数调用 `maid_duplex enable`。

该插件需要 `tools`、`systemPrompt`、`sessions`、`agents` 原生服务。角色卡取自真实 agent 作用域；变量组装使用 `{ agent, scope: agent }`，与 DSH 正常回合一致。

## 测试

```sh
cd dsh-plugin
npm test
```

额外使用本机 DSH 的真实运行库做隔离兼容测试：

```sh
node test/native-smoke.mjs "C:/path/to/DSH/node_modules/@deepseek-ai"
```

原生测试仅使用临时目录、虚拟人设和配对参数，不读取实际用户对话、角色卡或凭据。

一个插件实例只支持一个活动绑定。另一个对话只能查看自己的状态，不能停用原会话的绑定。相同配对字段可在 DSH 重启后重新启用；切换世界或女仆应使用独立数据目录并先停用原绑定，避免复用不匹配的私人历史。
