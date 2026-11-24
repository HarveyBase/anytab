# AnyTab

AI inline code completion for JetBrains IDEs — with your own model.

AnyTab brings Copilot-style ghost-text completion to any IntelliJ-platform IDE (IntelliJ IDEA, GoLand, PyCharm, WebStorm, …) and lets you plug in the model you want: DeepSeek's native FIM endpoint, Zhipu GLM, a NewAPI gateway, or any OpenAI-compatible server (Ollama, LM Studio, vLLM, …).

[中文说明](README.zh-CN.md)

## Features

- **Ghost text while you type.** Requests are debounced so the model is only called once you pause. You can also trigger a suggestion manually with the IDE's built-in inline-completion action (search for *Inline Completion* in Keymap).
- **Native accept behavior.** `Tab` accepts, `Esc` dismisses, and next-word / next-line partial accept are all provided by the IDE.
- **Multiple providers.** Presets for DeepSeek, GLM (Zhipu), NewAPI, plus a fully custom OpenAI-compatible provider. Switch between them from the status bar.
- **Three request protocols.** OpenAI FIM completions (`prompt` + `suffix`), OpenAI chat with an infill prompt, and FIM special tokens via `/completions` (vLLM / Ollama).
- **Streaming suggestions with guardrails.** Model output is filtered as it arrives: repeated current line, overlap with the code after the cursor, markdown fences, trailing whitespace, line and length limits.
- **Keys stay safe.** API keys are stored in the IDE Password Safe (Keychain on macOS), never in plain config files.
- **No third-party runtime dependencies.** HTTP and JSON use what the IDE already ships with.

## Requirements

- A JetBrains IDE **2026.3 or newer** (build 263+). See [Compatibility](#compatibility).
- A reachable model endpoint and, for cloud providers, an API key.

## Installation

### From a release ZIP

1. Download `anytab-<version>.zip` from [Releases](https://github.com/HarveyBase/anytab/releases).
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the ZIP, restart.

### From source

```bash
./gradlew buildPlugin
# the plugin is written to build/distributions/anytab-<version>.zip
```

## Configuration

Open **Settings → Tools → AnyTab**.

1. Click **Add** and pick a preset (or *Custom*).
2. Fill in the **API Key** (not needed for local servers such as Ollama / LM Studio).
3. Click **Test connection** to verify the endpoint, then **Apply**.
4. Pick the provider under **Active provider** if you have more than one.

### Presets

| Preset | Default Base URL | Protocol | Default model | Notes |
| --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/beta` | OpenAI FIM | `deepseek-flash` | FIM is a beta feature; `/beta` is required |
| GLM (Zhipu) | `https://open.bigmodel.cn/api/paas/v4` | OpenAI chat (infill prompt) | `glm-4.7-flashx` | Thinking is disabled via the extra request body |
| NewAPI | *(your gateway)* `https://<host>/v1` | OpenAI chat or FIM | — | Use **Fetch models** to list what the gateway exposes |
| Custom | — | any | — | Any OpenAI-compatible endpoint |

### Choosing a protocol

- **OpenAI FIM** — the endpoint implements `/completions` with `prompt` and `suffix`. Best quality and latency (the model is trained for fill-in-the-middle).
- **OpenAI Chat** — the model only has a chat API. AnyTab sends the code before and after the cursor and asks for the code to insert. Works with any chat model; quality varies.
- **FIM tokens** — the server (vLLM, Ollama, …) serves a FIM-trained model through `/completions`; the prefix/suffix are wrapped in special tokens such as `<|fim_prefix|>`.

### Notes on GLM

- The **GLM Coding Plan** endpoint is licensed for a whitelist of tools only. AnyTab therefore defaults to the standard pay-as-you-go API; if you point it at the Coding Plan endpoint, that is at your own risk.
- GLM-5.3 models cannot disable thinking mode, which makes them too slow for inline completion. Use a fast model that can (`glm-4.7-flashx`, …).

## How it works

```
typing ──debounce──▶ InlineCompletionProvider ──▶ CompletionEngine
                                                   ├─ ContextExtractor   (prefix / suffix window, file exclusions)
                                                   ├─ CompletionCache    (LRU, same context → same answer)
                                                   ├─ Backend            (FIM / chat infill / FIM tokens, SSE)
                                                   └─ SuggestionFilter   (streaming guardrails)
                                                          │
                                                          ▼
                                              gray text elements rendered by the IDE
```

Details: [docs/architecture.md](docs/architecture.md). Design notes and roadmap: [docs/plan.md](docs/plan.md).

## Compatibility

- AnyTab is built against IntelliJ Platform **2026.3** and declares `since-build = 263`. It uses APIs that were reorganized in 2026.3 (for example `com.intellij.ide.passwordSafe.PasswordSafe`, `com.intellij.util.ui.JBUI`, `InlineCompletionSingleSuggestion.build`), so it does not run on older IDEs yet. Supporting 2024.3+ is planned — see [docs/tasks.md](docs/tasks.md).
- The IDE shows **one** inline suggestion at a time and picks the first enabled provider. If you also use Qoder, GitHub Copilot or JetBrains AI Assistant, disable their inline completion (or AnyTab's) to avoid overlapping or competing suggestions.

## Development

```bash
./gradlew test                  # unit tests + a local mock-server streaming test
./gradlew buildPlugin           # compiles against IntelliJ IDEA 2026.3 (downloaded from JetBrains)
./gradlew verifyPlugin          # IntelliJ Plugin Verifier
```

Useful options:

```bash
# Build against an IDE that is already installed instead of downloading one
./gradlew buildPlugin -PlocalIde=/Applications/GoLand.app

# Mainland China: prefer the Aliyun Maven mirror for Gradle plugins and dependencies
CN_MIRROR=true ./gradlew buildPlugin
```

Project scaffold: [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

## License

[Apache-2.0](LICENSE)
