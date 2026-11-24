package com.harveybase.anytab.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.harveybase.anytab.completion.CompletionContext
import com.harveybase.anytab.settings.ProviderConfig
import com.harveybase.anytab.settings.protocolEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一次补全请求的采样/超时参数 */
data class CompletionOptions(
    val maxTokens: Int,
    val temperature: Double,
    val timeoutMs: Long,
)

/** 一次补全请求所需的全部输入 */
class CompletionRequest(
    val provider: ProviderConfig,
    val apiKey: String?,
    val context: CompletionContext,
    val options: CompletionOptions,
)

/** 补全后端：把上下文变成模型调用，以“增量文本流”返回 */
interface Backend {
    fun stream(request: CompletionRequest): Flow<String>
}

object Backends {
    fun forProtocol(protocol: Protocol): Backend = when (protocol) {
        Protocol.OPENAI_FIM -> OpenAiFimBackend
        Protocol.OPENAI_CHAT -> OpenAiChatBackend
        Protocol.FIM_TOKENS -> FimTokensBackend
    }
}

/**
 * OpenAI 兼容接口的公共部分：组 URL/请求头、发请求、解析 SSE、抽增量文本。
 * 子类只管“请求体长什么样”和“响应里增量在哪”。
 */
abstract class OpenAiBackend : Backend {
    // internal 而不是 protected：单测需要直接组请求体做断言
    abstract fun buildBody(request: CompletionRequest): JsonObject

    /** 流式分片里取增量文本；这一片没有内容返回 null */
    abstract fun extractDelta(chunk: JsonObject): String?

    /** 非流式响应里取完整文本 */
    abstract fun extractFull(response: JsonObject): String?

    final override fun stream(request: CompletionRequest): Flow<String> = flow {
        val provider = request.provider
        val body = buildBody(request)
        mergeExtraBody(body, provider.extraBody)
        val url = OpenAiClient.url(provider.baseUrl, provider.protocolEnum.path)
        val output = this
        val nonSse = StringBuilder()
        var sawSse = false
        OpenAiClient.postLines(url, OpenAiClient.headers(request.apiKey, provider.extraHeaders), body.toString())
            .collect { line ->
                val data = SseParser.dataOf(line)
                if (data != null) {
                    sawSse = true
                    if (data == SseParser.DONE) return@collect
                    val text = Json.parse(data)?.let { extractDelta(it) }
                    if (!text.isNullOrEmpty()) output.emit(text)
                } else if (line.isNotBlank() && !line.startsWith(":") && !line.startsWith("event:")) {
                    nonSse.append(line)
                }
            }
        // 服务端不支持 stream 时会一次性返回整段 JSON
        if (!sawSse && nonSse.isNotEmpty()) {
            Json.parse(nonSse.toString())?.let { extractFull(it) }?.let { output.emit(it) }
        }
    }

    /** 把用户填的额外请求体（JSON 对象）浅合并进来，同名键以用户为准 */
    internal fun mergeExtraBody(body: JsonObject, extraBody: String) {
        val extra = extraBody.trim()
        if (extra.isEmpty()) return
        val extraObject = Json.parse(extra)
            ?: throw LlmException(0, "Extra request body is not a valid JSON object")
        extraObject.entrySet().forEach { (key, value) -> body.add(key, value) }
    }

    protected fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    protected fun jsonArray(vararg items: String): JsonArray = JsonArray().apply {
        items.forEach { add(it) }
    }
}

/** 原生 FIM：POST /completions，prompt + suffix（DeepSeek /beta、vLLM、LM Studio 等） */
object OpenAiFimBackend : OpenAiBackend() {
    override fun buildBody(request: CompletionRequest): JsonObject = JsonObject().apply {
        addProperty("model", request.provider.model)
        addProperty("prompt", request.context.prefix)
        addProperty("suffix", request.context.suffix)
        addProperty("max_tokens", request.options.maxTokens)
        addProperty("temperature", request.options.temperature)
        addProperty("stream", true)
    }

    override fun extractDelta(chunk: JsonObject): String? = chunk.firstChoice()?.stringAt("text")

    override fun extractFull(response: JsonObject): String? = response.firstChoice()?.stringAt("text")
}

/** chat 填空：POST /chat/completions，用提示词让模型只输出光标处要插入的代码（GLM 等） */
object OpenAiChatBackend : OpenAiBackend() {
    override fun buildBody(request: CompletionRequest): JsonObject = JsonObject().apply {
        addProperty("model", request.provider.model)
        add("messages", JsonArray().apply {
            add(message("system", SYSTEM_PROMPT))
            add(message("user", buildUserPrompt(request.context)))
        })
        addProperty("max_tokens", request.options.maxTokens)
        addProperty("temperature", request.options.temperature)
        addProperty("stream", true)
    }

    override fun extractDelta(chunk: JsonObject): String? =
        chunk.firstChoice()?.objectAt("delta")?.stringAt("content")

    override fun extractFull(response: JsonObject): String? =
        response.firstChoice()?.objectAt("message")?.stringAt("content")

    internal fun buildUserPrompt(context: CompletionContext): String = buildString {
        appendLine("Language: ${context.languageId}")
        context.filePath?.let { appendLine("File: $it") }
        appendLine()
        appendLine("<code_before_cursor>")
        append(context.prefix)
        appendLine("</code_before_cursor>")
        appendLine("<code_after_cursor>")
        append(context.suffix)
        appendLine("</code_after_cursor>")
        append("<code_at_cursor>")
    }

    private const val SYSTEM_PROMPT =
        "You are a code completion engine inside an IDE. " +
            "The user sends code before and after the cursor. " +
            "Respond with only the code to insert at the cursor: no explanations, no markdown fences, " +
            "never repeat the code before or after the cursor. Keep it short and stop at a natural boundary."
}

/** FIM 特殊标记协议：把前后文用标记拼成一个 prompt，POST /completions（vLLM / Ollama 直出模型） */
object FimTokensBackend : OpenAiBackend() {
    override fun buildBody(request: CompletionRequest): JsonObject {
        val tokens = FimTokens.parse(request.provider.fimTokens)
        return JsonObject().apply {
            addProperty("model", request.provider.model)
            addProperty(
                "prompt",
                tokens.prefix + request.context.prefix + tokens.suffix + request.context.suffix + tokens.middle,
            )
            addProperty("max_tokens", request.options.maxTokens)
            addProperty("temperature", request.options.temperature)
            addProperty("stream", true)
            add("stop", jsonArray(tokens.end, "<|endoftext|>"))
        }
    }

    override fun extractDelta(chunk: JsonObject): String? = chunk.firstChoice()?.stringAt("text")

    override fun extractFull(response: JsonObject): String? = response.firstChoice()?.stringAt("text")
}

/** FIM_TOKENS 协议用的四个标记 */
data class FimTokens(
    val prefix: String,
    val suffix: String,
    val middle: String,
    val end: String,
) {
    companion object {
        /** Qwen / DeepSeek-Coder 系列通用的默认标记 */
        val DEFAULT = FimTokens("<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>", "<|fim_end|>")

        /** 配置格式：prefix|suffix|middle|end；缺项或格式不对时用默认值 */
        fun parse(text: String?): FimTokens {
            val parts = text.orEmpty().split('|').map { it.trim() }
            return if (parts.size == 4 && parts.all { it.isNotEmpty() }) {
                FimTokens(parts[0], parts[1], parts[2], parts[3])
            } else {
                DEFAULT
            }
        }
    }
}

/** 拉取供应商的模型列表：GET /models，取 data[].id */
object ModelCatalog {
    suspend fun list(provider: ProviderConfig, apiKey: String?): List<String> {
        val raw = OpenAiClient.getJson(
            OpenAiClient.url(provider.baseUrl, "/models"),
            OpenAiClient.headers(apiKey, provider.extraHeaders),
        )
        val data = Json.parse(raw)?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.stringAt("id") }.sorted()
    }
}
