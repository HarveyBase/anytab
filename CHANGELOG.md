# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-11-24

### Added
- Inline ghost-text completion powered by the IDE's built-in inline completion: trigger while typing (with debounce) or manually, `Tab` / word / line to accept.
- Provider presets: DeepSeek (native FIM), Zhipu GLM (chat infill), NewAPI gateways, and any OpenAI-compatible endpoint.
- Three request protocols: OpenAI FIM completions, OpenAI chat infill, and FIM special tokens via completions (vLLM / Ollama).
- Streaming suggestions with echo / suffix-overlap / markdown-fence filtering, line and length limits, and a small result cache.
- Settings page with provider list, API key storage in the IDE Password Safe, "Test connection" and "Fetch models".
- Status bar widget and Tools menu to toggle completion, switch provider, and open settings.
