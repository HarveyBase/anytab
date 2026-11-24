# 任务清单

状态：`[x]` 完成 · `[~]` 进行中 · `[ ]` 待办

## M0 脚手架

- [x] 创建仓库 `HarveyBase/anytab`，以官方 IntelliJ Platform Plugin Template 为脚手架
- [x] 构建配置：Kotlin 2.4.21 / Gradle 9.9.2 / IntelliJ Platform Gradle Plugin 2.20.1，JVM target 21
- [x] 本机构建打通：GoLand 自带 JBR 作 Gradle JVM，`-PlocalIde` 用本机 IDE 当编译平台，`CN_MIRROR` 走阿里云镜像
- [x] `./gradlew buildPlugin` 产出 `anytab-0.1.0.zip`

## M1 核心链路

- [x] `AnyTabInlineCompletionProvider`：isEnabled / 防抖 / getSuggestionDebounced
- [x] 全局与项目级开关、持久化
- [x] `ContextExtractor`：光标前后窗口、排除文件
- [x] `SuggestionFilter`：回显 / 后文重叠 / 停止序列 / 结尾空白 / 行数与长度上限
- [x] `OpenAiClient`：SSE、协程取消断连、错误码 → `LlmException`
- [x] DeepSeek FIM 后端 + 预设
- [x] `CompletionCache`
- [x] API Key 存 PasswordSafe
- [x] 设置页：供应商增删、表单、测试连接、当前供应商、补全行为
- [x] 状态栏部件 + 弹出菜单（开关 / 切换供应商 / 设置）+ Tools 菜单
- [x] 鉴权失败通知（每个供应商一次）
- [ ] 在 GoLand 2026.3 里实装验证：打字出灰字、Tab / 词 / 行采纳、Esc、手动触发、开关

## M2 更多供应商

- [x] GLM 预设（chat 填空 + 关闭思考）
- [x] NewAPI 预设 + 获取模型列表
- [x] 自定义供应商、额外请求头、额外请求体
- [x] FIM tokens 协议
- [ ] 用真实 Key 验证 DeepSeek / GLM / NewAPI 三家的“测试连接”和补全效果
- [ ] 按实测调 prompt、护栏阈值和各家默认参数

## M3 可发布

- [x] 单测：过滤器、上下文窗口、请求体、SSE、本地模拟服务的流式集成测试
- [x] README（中 / 英）、CHANGELOG、LICENSE
- [ ] 提交 GitHub Actions 工作流（当前 gh token 没有 `workflow` scope，推不上去）
- [ ] 放宽 `sinceBuild`：需要为 2026.3 之前的包路径做兼容（反射或双实现），并用 `verifyPlugin` 验证 2024.3 ~ 2026.3
- [ ] 首个 GitHub Release（附 zip）
- [ ] 可选：JetBrains Marketplace 发布（需要签名）
- [ ] 可选：检测到其它行内补全 provider 时给一次提示
- [ ] 可选：发布前用 Plugin Verifier 的结果决定 `untilBuild`

## 不在 v1 范围

聊天面板、next-edit、跨文件上下文、多候选切换、用量统计。
