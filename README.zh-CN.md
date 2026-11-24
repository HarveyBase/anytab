# AnyTab

[English](README.md)

JetBrains IDE 的 AI 行内代码补全插件，模型自己带：DeepSeek 原生 FIM、智谱 GLM、NewAPI 网关，或者任意 OpenAI 兼容服务（Ollama、LM Studio、vLLM……）都能接。

## 功能

- **边打边补灰字。** 内置防抖，停下来才请求；也可以用 IDE 自带的行内补全动作手动触发（在 Keymap 里搜 *Inline Completion*）。
- **原生采纳体验。** `Tab` 采纳、`Esc` 关闭、逐词 / 逐行部分采纳，全部由 IDE 提供。
- **多供应商。** 内置 DeepSeek、GLM（智谱）、NewAPI 预设，也支持完全自定义的 OpenAI 兼容端点，状态栏一键切换。
- **三种请求协议。** OpenAI FIM completions（`prompt` + `suffix`）、OpenAI chat 填空提示词、FIM 特殊标记走 `/completions`（vLLM / Ollama）。
- **流式输出 + 护栏。** 模型输出边到边过滤：回显当前行、与光标后代码重叠、markdown 围栏、结尾空白、行数与长度上限。
- **密钥安全。** API Key 存在 IDE 的 PasswordSafe（macOS 上是钥匙串），不落明文。
- **零第三方运行时依赖。** HTTP 和 JSON 都用 IDE 自带的。

## 环境要求

- JetBrains IDE **2026.3 及以上**（build 263+）。见[兼容性](#兼容性)。
- 一个能访问的模型端点；云端供应商还需要 API Key。

## 安装

### 用发布包

1. 在 [Releases](https://github.com/HarveyBase/anytab/releases) 下载 `anytab-<version>.zip`。
2. IDE 里 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选这个 ZIP，重启。

### 从源码构建

```bash
./gradlew buildPlugin
# 产物在 build/distributions/anytab-<version>.zip
```

## 配置

打开 **Settings → Tools → AnyTab**：

1. 点 **添加**，选一个预设（或 *Custom*）。
2. 填 **API Key**（本地 Ollama / LM Studio 这类不需要鉴权的可以留空）。
3. 点 **测试连接** 确认能通，再点 **Apply**。
4. 有多个供应商时，在 **当前供应商** 里选一个。

### 预设

| 预设 | 默认接口地址 | 协议 | 默认模型 | 说明 |
| --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/beta` | OpenAI FIM | `deepseek-flash` | FIM 是 beta 功能，地址必须带 `/beta` |
| GLM（智谱） | `https://open.bigmodel.cn/api/paas/v4` | OpenAI chat（填空提示词） | `glm-4.7-flashx` | 通过额外请求体关闭思考模式 |
| NewAPI | *（你的网关）* `https://<host>/v1` | OpenAI chat 或 FIM | — | 用 **获取模型列表** 查看网关暴露了哪些模型 |
| Custom | — | 任意 | — | 任意 OpenAI 兼容端点 |

### 怎么选协议

- **OpenAI FIM**：端点实现了 `/completions` 且支持 `prompt` + `suffix`。质量最好、延迟最低（模型就是为填空训练的）。
- **OpenAI Chat**：模型只有 chat 接口时用。AnyTab 把光标前后的代码发过去，让它只输出要插入的部分。任何聊天模型都能用，质量看模型。
- **FIM tokens**：服务端（vLLM、Ollama……）通过 `/completions` 直出 FIM 训练的模型，前后文用 `<|fim_prefix|>` 这类特殊标记包起来。

### 关于 GLM

- **GLM Coding Plan** 的端点在协议里限定只能给白名单工具用，所以 AnyTab 默认走标准按量接口；你非要填 Coding Plan 地址，风险自负。
- GLM-5.3 系列不能关闭思考，做补全太慢；用支持关闭思考的快速模型（如 `glm-4.7-flashx`）。

## 工作原理

```
输入 ──防抖──▶ InlineCompletionProvider ──▶ CompletionEngine
                                           ├─ ContextExtractor   （取光标前后窗口、排除文件）
                                           ├─ CompletionCache    （LRU：同样上下文直接返回）
                                           ├─ Backend            （FIM / chat 填空 / FIM tokens，SSE 流式）
                                           └─ SuggestionFilter   （流式护栏）
                                                  │
                                                  ▼
                                       IDE 渲染灰字，Tab 采纳
```

细节见 [docs/architecture.md](docs/architecture.md)，方案与路线图见 [docs/plan.md](docs/plan.md)。

## 兼容性

- AnyTab 以 IntelliJ Platform **2026.3** 为编译平台，声明 `since-build = 263`。用到了 2026.3 里调整过包路径的 API（如 `com.intellij.ide.passwordSafe.PasswordSafe`、`com.intellij.util.ui.JBUI`、`InlineCompletionSingleSuggestion.build`），所以暂时不能在更老的 IDE 上运行；支持 2024.3+ 在计划中，见 [docs/tasks.md](docs/tasks.md)。
- IDE 同一时刻只显示**一个**行内建议，取第一个启用的 provider。如果同时装了 Qoder、GitHub Copilot 或 JetBrains AI Assistant，请关掉它们的行内补全（或关掉 AnyTab），避免叠加或抢占。

## 开发

```bash
./gradlew test                  # 单测 + 本地模拟服务的流式集成测试
./gradlew buildPlugin           # 以 IntelliJ IDEA 2026.3 为平台编译（从 JetBrains 下载）
./gradlew verifyPlugin          # 跑 IntelliJ Plugin Verifier
```

常用选项：

```bash
# 用本机已安装的 IDE 当编译平台，不用再下载一份
./gradlew buildPlugin -PlocalIde=/Applications/GoLand.app

# 国内网络：Gradle 插件和依赖优先走阿里云镜像
CN_MIRROR=true ./gradlew buildPlugin
```

工程脚手架来自 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)。

## 许可证

[Apache-2.0](LICENSE)
