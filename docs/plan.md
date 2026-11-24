# 方案

## 背景

想要一个 JetBrains IDE 的行内代码补全（灰字 + Tab 采纳），模型自己带：DeepSeek、智谱 GLM、NewAPI 网关，或者任意 OpenAI 兼容端点。现成的插件（ProxyAI、Continue、DevoxxGenie……）要么太重，要么没有国内供应商预设，要么支持的 IDE 版本范围不合适，所以自研一个轻量的。

## 调研结论

这些结论决定了方案走向，细节里的链接核对于 2026-11。

1. **参考仓库 `MirminaMirror/qoder-cn-patch` 不是补全实现。** 它是 Qoder CN 官方 JetBrains 插件的“功能恢复补丁”（`<depends>` + 反射调官方私有类），仓库里没有任何模型/供应商/HTTP 代码。可借鉴的只有工程脚手架。
2. **JetBrains 平台已经把难做的部分做好了。** 公开 API `InlineCompletionProvider`：插件只回答“这个事件接不接”和“接了给什么”，Tab / Esc / 逐词逐行 / 灰字渲染 / 过期取消全由平台负责；`DebouncedInlineCompletionProvider` 提供防抖；手动触发是平台自带动作。
3. **三家供应商协议不一样：**
   - DeepSeek 有原生 FIM：`https://api.deepseek.com/beta/completions`，`prompt` + `suffix`，beta 功能。
   - GLM 没有 FIM 接口，只能走 chat 填空。GLM Coding Plan 明文限定只能在官方白名单工具里用；GLM-5.3 系列不能关闭思考。
   - NewAPI 的 `/v1/completions`、`/v1/chat/completions`、`/v1/models` 路由都在，透传哪种取决于上游渠道。
4. **2026.3 的平台 API 相比网上资料有大量包路径调整**（见 [architecture.md](architecture.md#20263-平台-api-备忘)），必须对着目标 IDE 的真实 jar 写。

## 技术路线

| 项 | 选择 | 理由 |
| --- | --- | --- |
| 渲染 | 平台 `InlineCompletionProvider` + `DebouncedInlineCompletionProvider` + `InlineCompletionSingleSuggestion.build` | 自绘 inlay 要自己处理 Tab / Lookup / 撤销 / 光标，不划算 |
| 编译平台 | IntelliJ Platform 2026.3，`sinceBuild = 263`，不封顶 | 先保证在作者本机 GoLand 2026.3 上跑通，再谈放宽 |
| 工程 | Kotlin 2.4.21 + Gradle 9.9.2 + IntelliJ Platform Gradle Plugin 2.20.1，脚手架取自官方 `intellij-platform-plugin-template` | 模板自带 CI / Release / Changelog |
| 网络 | JDK 自带 `java.net.http.HttpClient` + SSE | 零第三方运行时依赖 |
| 存储 | `PersistentStateComponent` + PasswordSafe | 官方推荐做法 |

## 里程碑

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 脚手架 | 仓库 + 模板 + Provider 骨架，`buildPlugin` 出包 | 完成 |
| M1 DeepSeek 端到端 | FIM 流式补全、设置页、状态栏、防抖 + 取消 | 代码完成，待实机验证 |
| M2 GLM + NewAPI + 自定义 | chat 填空、FIM tokens、拉模型列表、流式护栏 | 代码完成，单测通过，待实机验证 |
| M3 可发布 | 放宽 `sinceBuild`、verifyPlugin、CI、Release | 进行中 |

详细的任务清单见 [tasks.md](tasks.md)。

## 风险

| 风险 | 对策 |
| --- | --- |
| GLM Coding Plan 只能用于官方白名单工具 | 预设默认走标准按量端点，README 明示 |
| 与 Qoder / Copilot / AI Assistant 抢灰字 | 平台只取第一个启用的 provider；README 提示用户关掉其它插件的行内补全 |
| chat 填空质量不如原生 FIM | 默认推荐 DeepSeek FIM；chat 模式低温度 + 严格护栏 |
| 平台 API 变动频繁（2026.3 大改） | 只用非废弃 API；API 备忘写进 architecture.md |
| 代码会发给第三方端点 | 默认排除密钥类文件；文件路径只发相对路径 |
