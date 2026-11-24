# 架构

AnyTab 只做一件事：把“光标前后的代码”变成“一条灰字元素流”，其余全部交给 IntelliJ 平台。

## 模块

```
com.harveybase.anytab
├── inline/       AnyTabInlineCompletionProvider   接入平台：isEnabled + 防抖 + getSuggestionDebounced
├── completion/   CompletionEngine                 编排：上下文 → 缓存 → 密钥 → 模型 → 过滤
│                 ContextExtractor                取光标前后窗口（按行数/字符数）、排除文件
│                 SuggestionFilter                流式护栏
│                 CompletionCache                 LRU
├── llm/          Protocol / Preset / Backend      三种协议 + 供应商预设
│                 OpenAiClient                     JDK HttpClient + SSE，协程取消即断连
│                 ModelCatalog                     GET /models
├── settings/     AnyTabSettings / AnyTabProjectState   持久化（全局 / 项目）
│                 ApiKeyStore                      PasswordSafe
│                 AnyTabConfigurable / ProviderEditor   设置页
│                 ProviderTester                   “测试连接”
└── ui/           AnyTabStatusBarWidget / AnyTabPopup / Actions / Notifier
```

## 调用链

1. 用户输入 → 平台触发 `InlineCompletionEvent.DocumentChange`。
2. `AnyTabInlineCompletionProvider.isEnabled(event)`（EDT）：总开关、项目开关、有没有可用供应商、是否允许自动触发。
3. 平台调用 `getSuggestion(request)`；基类 `DebouncedInlineCompletionProvider` 先 `delay(getDebounceDelay)`，打字事件等 300ms（可配），手动触发 0ms。
4. `CompletionEngine.suggest(request)`：
   1. `readAction { ContextExtractor.extract(...) }`：文件是二进制 / 太大 / 命中排除规则 → 返回空。
   2. 查 `CompletionCache`，命中直接返回。
   3. `ApiKeyStore.get(providerId)`（`Dispatchers.IO`，不持读锁）。
   4. `Backend.stream(request)`：发请求，把 SSE 的 `data:` 行解析成文本增量。
   5. 每个增量过 `SuggestionFilter.accept()`，放行的部分包成 `InlineCompletionGrayTextElement` emit。
   6. 流结束 `filter.finish()` 放行收尾；中途失败/超时只记日志和状态栏，不打扰用户。
5. 平台在 EDT 渲染元素；Tab / Esc / 逐词 / 逐行 / 光标移动隐藏 / 过期取消全由平台负责。平台取消协程时，`OpenAiClient` 会关闭响应流并取消 future。

## 协议

| Protocol | Endpoint | 请求体要点 | 增量字段 |
| --- | --- | --- | --- |
| `OPENAI_FIM` | `POST {base}/completions` | `model, prompt, suffix, max_tokens, temperature, stream` | `choices[0].text` |
| `OPENAI_CHAT` | `POST {base}/chat/completions` | `messages[system+user]`，用户消息里用 `<code_before_cursor>` / `<code_after_cursor>` / `<code_at_cursor>` 分隔 | `choices[0].delta.content` |
| `FIM_TOKENS` | `POST {base}/completions` | `prompt = prefix + 前文 + suffix + 后文 + middle`，`stop = [end, <|endoftext|>]` | `choices[0].text` |

- 服务端不支持 `stream`、一次性返回整段 JSON 时，退化为解析 `choices[0].text` / `choices[0].message.content`。
- 供应商的 **Extra request body**（JSON 对象）会浅合并进请求体，同名键以用户为准——用来关思考模式、改采样参数等。
- **Extra headers** 每行一条 `Key: Value`，用于非 Bearer 的鉴权或网关要求的头。

## 上下文窗口

- 前文：从 `cursor - prefixMaxChars` 起，落在行中间就挪到下一行行首；再从光标往前最多 `prefixLines` 行。
- 后文：到 `cursor + suffixMaxChars` 止，落在行中间时若这一行剩余部分不超过 `suffixMaxChars * 2` 就补全整行，否则硬切；再从光标往后最多 `suffixLines` 行。
- 文件路径只发相对项目根目录的路径；拿不到项目就只发文件名。
- 排除规则：文件名 glob，每行一条；默认排除文档/数据/日志和可能带密钥的文件（`.env*`、`*.pem`、`*.key`、`id_rsa*`）。

## 流式护栏（SuggestionFilter）

已经放行的文本收不回来，所以“看不准”的部分先攥在手里：

| 规则 | 处理 |
| --- | --- |
| 模型回显当前行 | 光标在空行：去掉开头的换行和与已有缩进相同的空白；行尾非字母数字且长度 ≥ 2：去掉与当前行相同的前缀，开头空白去掉 |
| 复述后文 | 后文第一行非空代码（含前面那个换行）出现在输出里 → 截掉并停止；输出结尾是它的一小截 → 先攥着，流结束就丢弃 |
| 停止序列 | ```` ``` ````、`<|fim_end|>`、`<|endoftext|>`：开头就出现则整条丢弃，否则截断；结尾是它们的前半截 → 先攥着 |
| 结尾空白 | 换行 / 空格 / Tab 一律等后面的非空白到了再放行，流结束就丢弃 |
| 上限 | `maxLines`（最多 n 行，第 n 个换行处停止并取消请求）、`maxChars` |

## 配置

- `anytab.xml`（应用级，`AnyTabSettings`）：开关、防抖、上下文窗口、上限、排除规则、供应商列表、当前供应商。
- 工作区文件（项目级，`AnyTabProjectState`）：本项目的开关。
- PasswordSafe：`AnyTab` / `<providerId>` 为键存 API Key。
- 设置页编辑的是已保存配置的副本，Apply 才拷回去；API Key 单独收集，Apply 时异步写入 PasswordSafe。

## 状态与交互

- 状态栏部件：`AnyTab`（正常）/ `AnyTab …`（请求中）/ `AnyTab !`（上次失败）/ `AnyTab ✕`（已关闭）；点击弹出：开关、切换供应商、打开设置。
- 手动触发用平台自带的 `CallInlineCompletionAction`（默认 Shift+Alt+\\），插件不额外占快捷键。
- 鉴权失败（401/403）每个供应商只弹一次通知，带“打开设置”动作；429/5xx/超时只记日志和状态栏。

## 2026.3 平台 API 备忘

编译平台是 IntelliJ Platform 2026.3，下面这些类和旧版文档/博客里的写法不一样，写代码时以 2026.3 的 jar 为准（可用反射或 IDE 查看真实签名）：

| 旧写法 | 2026.3 |
| --- | --- |
| `com.intellij.credentialStore.PasswordSafe` | `com.intellij.ide.passwordSafe.PasswordSafe`（`PasswordSafe.instance.getPassword/setPassword`，后台线程 + 不持读锁） |
| `com.intellij.ui.JBLabel` | `com.intellij.ui.components.JBLabel` |
| `com.intellij.ui.JBUI` | `com.intellij.util.ui.JBUI` |
| `com.intellij.openapi.ui.SimpleTextAttributes` | `com.intellij.ui.SimpleTextAttributes` |
| `com.intellij.ui.components.ComboBox` | `com.intellij.openapi.ui.ComboBox` |
| `com.intellij.ui.components.PasswordComponent` | 已移除，用 `JPasswordField` |
| `com.intellij.ide.BrowseNotificationAction(text, action)` | `com.intellij.notification.NotificationAction.create(text) { event, notification -> }` |
| `com.intellij.util.containers.ConcurrentHashMap` | 已移除，用 `java.util.concurrent.ConcurrentHashMap` |
| `SimpleListCellRenderer.create(...)` | 已废弃，用 `ColoredListCellRenderer` |
| `StatusBarWidget` 的点击回调 | `WidgetPresentation.getClickConsumer(): com.intellij.util.Consumer<MouseEvent>?` |
| `InlineCompletionSuggestion`（根包抽象类） | 已废弃，用 `suggestion.InlineCompletionSingleSuggestion.build(elements = flow)` |
| `Flow.transformWhile { value, collector -> }` | 这个协程库版本里 collector 是 lambda 的 receiver：`transformWhile { value -> emit(...); true }` |
| `Row.textField()` / `textArea()` 的顶层扩展 import | 改成了 `Row` 的成员，不需要 import |

Kotlin 标准库被并进了 `util-8.jar`，`lib/` 下没有单独的 `kotlin-stdlib` jar；`kotlin.stdlib.default.dependency = false` 保持默认即可。
