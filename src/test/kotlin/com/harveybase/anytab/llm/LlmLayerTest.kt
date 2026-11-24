package com.harveybase.anytab.llm

import com.harveybase.anytab.completion.CompletionContext
import com.harveybase.anytab.settings.ProviderConfig
import com.harveybase.anytab.settings.protocolEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmLayerTest {
    private val context = CompletionContext(
        prefix = "func f() {\n\t",
        suffix = "\n}\n",
        currentLinePrefix = "\t",
        languageId = "Go",
        filePath = "a.go",
    )
    private val options = CompletionOptions(maxTokens = 64, temperature = 0.1, timeoutMs = 1000)

    private fun request(preset: Preset, configure: ProviderConfig.() -> Unit = {}): CompletionRequest {
        val provider = ProviderConfig.create(preset).apply(configure)
        return CompletionRequest(provider, "secret", context, options)
    }

    // ---- 预设 ----

    @Test
    fun `deepseek preset uses native fim`() {
        val provider = request(Preset.DEEPSEEK).provider
        assertEquals("https://api.deepseek.com/beta", provider.baseUrl)
        assertEquals(Protocol.OPENAI_FIM, provider.protocolEnum)
        assertEquals("deepseek-flash", provider.model)
        assertEquals("/completions", provider.protocolEnum.path)
    }

    @Test
    fun `glm preset uses chat infill and disables thinking`() {
        val provider = request(Preset.GLM).provider
        assertEquals("https://open.bigmodel.cn/api/paas/v4", provider.baseUrl)
        assertEquals(Protocol.OPENAI_CHAT, provider.protocolEnum)
        assertTrue(provider.extraBody.contains("\"disabled\""))
    }

    @Test
    fun `presets without defaults keep user values`() {
        val provider = request(Preset.NEWAPI) {
            baseUrl = "https://gw.example.com/v1"
            model = "gpt-oss-120b"
        }.provider
        assertEquals("https://gw.example.com/v1", provider.baseUrl)
        assertEquals("gpt-oss-120b", provider.model)
        assertEquals("", provider.extraBody)
    }

    // ---- 请求体 ----

    @Test
    fun `fim body carries prompt and suffix`() {
        val request = request(Preset.DEEPSEEK)
        val body = OpenAiFimBackend.buildBody(request)
        assertEquals("deepseek-flash", body.stringAt("model"))
        assertEquals(context.prefix, body.stringAt("prompt"))
        assertEquals(context.suffix, body.stringAt("suffix"))
        assertEquals(64, body.get("max_tokens").asInt)
        assertTrue(body.get("stream").asBoolean)
    }

    @Test
    fun `chat body carries system and user messages plus merged extra body`() {
        val request = request(Preset.GLM)
        val body = OpenAiChatBackend.buildBody(request)
        OpenAiChatBackend.mergeExtraBody(body, request.provider.extraBody)

        val messages = body.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.stringAt("role"))
        val user = messages[1].asJsonObject.stringAt("content")!!
        assertTrue(user.contains("<code_before_cursor>"))
        assertTrue(user.contains(context.prefix))
        assertTrue(user.contains(context.suffix))
        assertTrue(user.contains("Language: Go"))
        assertTrue(user.contains("File: a.go"))
        assertEquals("disabled", body.getAsJsonObject("thinking").stringAt("type"))
    }

    @Test
    fun `extra body overrides built-in fields`() {
        val request = request(Preset.GLM)
        val body = OpenAiChatBackend.buildBody(request)
        OpenAiChatBackend.mergeExtraBody(body, """{"max_tokens":32,"stream":false}""")
        assertEquals(32, body.get("max_tokens").asInt)
        assertEquals(false, body.get("stream").asBoolean)
    }

    @Test
    fun `fim tokens are stitched into a single prompt`() {
        val request = request(Preset.CUSTOM) {
            protocol = Protocol.FIM_TOKENS.name
            fimTokens = "<p>|<s>|<m>|<e>"
        }
        val body = FimTokensBackend.buildBody(request)
        assertEquals("<p>${context.prefix}<s>${context.suffix}<m>", body.stringAt("prompt"))
        assertEquals("<e>", body.getAsJsonArray("stop")[0].asString)
    }

    // ---- 响应解析 ----

    @Test
    fun `deltas are extracted from stream chunks`() {
        assertEquals("a + b", OpenAiFimBackend.extractDelta(Json.parse("""{"choices":[{"text":"a + b"}]}""")!!))
        assertEquals("return x", OpenAiChatBackend.extractDelta(Json.parse("""{"choices":[{"delta":{"content":"return x"}}]}""")!!))
        // 思考型模型的 reasoning_content 不是补全内容
        assertNull(OpenAiChatBackend.extractDelta(Json.parse("""{"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""")!!))
        // 只有 usage 的那一片
        assertNull(OpenAiChatBackend.extractDelta(Json.parse("""{"choices":[],"usage":{"total_tokens":3}}""")!!))
    }

    @Test
    fun `full text is extracted from non-stream responses`() {
        assertEquals("a + b", OpenAiFimBackend.extractFull(Json.parse("""{"choices":[{"text":"a + b"}]}""")!!))
        assertEquals(
            "return x",
            OpenAiChatBackend.extractFull(Json.parse("""{"choices":[{"message":{"role":"assistant","content":"return x"}}]}""")!!),
        )
    }

    @Test
    fun `invalid json is not a suggestion`() {
        assertNull(Json.parse("not json at all"))
        assertNull(Json.parse("[1,2,3]"))
    }

    // ---- 小工具 ----

    @Test
    fun `sse data lines`() {
        assertEquals("""{"a":1}""", SseParser.dataOf("""data: {"a":1}"""))
        assertEquals("[DONE]", SseParser.dataOf("data: [DONE]"))
        assertEquals("", SseParser.dataOf("data"))
        assertNull(SseParser.dataOf(""))
        assertNull(SseParser.dataOf("event: message"))
        assertNull(SseParser.dataOf(": keep-alive"))
    }

    @Test
    fun `fim tokens fall back to defaults when malformed`() {
        assertEquals(FimTokens.DEFAULT, FimTokens.parse(null))
        assertEquals(FimTokens.DEFAULT, FimTokens.parse("a|b|c"))
        assertEquals(FimTokens("a", "b", "c", "d"), FimTokens.parse(" a | b |c| d"))
    }

    @Test
    fun `url and headers`() {
        assertEquals(
            "https://api.deepseek.com/beta/completions",
            OpenAiClient.url("https://api.deepseek.com/beta/", "/completions"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            OpenAiClient.url(" http://localhost:11434/v1 ", "/chat/completions"),
        )

        val headers = OpenAiClient.headers("sk-abc", "# comment\nX-Title: anytab\napi-key: k2\nbroken line")
        assertEquals("Bearer sk-abc", headers["Authorization"])
        assertEquals("anytab", headers["X-Title"])
        assertEquals("k2", headers["api-key"])
        assertEquals(3, headers.size)
        assertTrue(OpenAiClient.headers("", "").isEmpty())
    }

    @Test
    fun `error message is extracted from provider responses`() {
        assertEquals(
            "Invalid API key",
            OpenAiClient.extractErrorMessage("""{"error":{"message":"Invalid API key","type":"auth"}}"""),
        )
        assertEquals("nope", OpenAiClient.extractErrorMessage("""{"message":"nope"}"""))
        assertEquals("Service Unavailable", OpenAiClient.extractErrorMessage("Service Unavailable"))
        assertEquals("empty response", OpenAiClient.extractErrorMessage("  "))
    }
}
