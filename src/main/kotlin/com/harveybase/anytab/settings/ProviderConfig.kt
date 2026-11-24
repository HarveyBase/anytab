package com.harveybase.anytab.settings

import com.harveybase.anytab.llm.FimTokens
import com.harveybase.anytab.llm.Preset
import com.harveybase.anytab.llm.Protocol
import java.util.UUID

/**
 * 一个供应商（端点 + 模型 + 鉴权）的配置。
 * 它是 PersistentStateComponent 里的 bean：所有字段都是 var + 默认值，别加只有 getter 的属性（序列化器会认不出来）。
 * API Key 不在这里，放在 PasswordSafe，见 [ApiKeyStore]。
 */
data class ProviderConfig(
    var id: String = "",
    var name: String = "",
    var preset: String = Preset.CUSTOM.name,
    var baseUrl: String = "",
    var protocol: String = Protocol.OPENAI_CHAT.name,
    var model: String = "",
    var enabled: Boolean = true,
    /** 额外请求头，每行一条 `Key: Value` */
    var extraHeaders: String = "",
    /** 额外请求体，JSON 对象，会浅合并进请求体 */
    var extraBody: String = "",
    /** FIM_TOKENS 协议用的四个标记：prefix|suffix|middle|end */
    var fimTokens: String = "",
) {
    companion object {
        /** 按预设新建一个供应商 */
        fun create(preset: Preset): ProviderConfig = ProviderConfig(
            id = UUID.randomUUID().toString(),
            name = preset.title,
            preset = preset.name,
        ).apply { applyPresetDefaults(preset) }
    }
}

val ProviderConfig.presetEnum: Preset get() = Preset.of(preset)

val ProviderConfig.protocolEnum: Protocol get() = Protocol.of(protocol)

/** 是否填完了发请求必需的字段（API Key 可以为空：本地 Ollama / LM Studio 不需要鉴权） */
val ProviderConfig.isConfigured: Boolean
    get() = baseUrl.isNotBlank() && model.isNotBlank()

/** 把预设的地址/协议/模型填进来。预设留空的字段（如自定义地址）保留用户已填的内容 */
fun ProviderConfig.applyPresetDefaults(preset: Preset) {
    if (preset.defaultBaseUrl.isNotBlank()) baseUrl = preset.defaultBaseUrl
    protocol = preset.defaultProtocol.name
    if (preset.defaultModel.isNotBlank()) model = preset.defaultModel
    extraBody = preset.defaultExtraBody
    fimTokens = if (preset.defaultProtocol == Protocol.FIM_TOKENS) FimTokens.DEFAULT.toConfigString() else ""
}

fun FimTokens.toConfigString(): String = "$prefix|$suffix|$middle|$end"
