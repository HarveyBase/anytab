package com.harveybase.anytab.llm

import com.harveybase.anytab.completion.CompletionContext
import com.harveybase.anytab.settings.ProviderConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 用本机的 HTTP 模拟服务验证整条流式链路：请求头/请求体、SSE 解析、非流式响应、错误码。
 * 不依赖任何真实供应商，也不需要 API Key。
 */
class BackendStreamingTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** 服务端收到的请求，供断言 */
    private var receivedPath: String? = null
    private var receivedAuthorization: String? = null
    private var receivedBody: String? = null

    private val context = CompletionContext("func f() {\n\t", "\n}\n", "\t", "Go", "a.go")
    private val options = CompletionOptions(maxTokens = 32, temperature = 0.0, timeoutMs = 5000)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun request(requestProtocol: Protocol): CompletionRequest {
        val provider = ProviderConfig.create(Preset.CUSTOM).apply {
            baseUrl = this@BackendStreamingTest.baseUrl
            protocol = requestProtocol.name
            model = "mock-model"
        }
        return CompletionRequest(provider, "sk-test", context, options)
    }

    private fun respond(status: Int, contentType: String, vararg chunks: String): (HttpExchange) -> Unit = { exchange ->
        try {
            receivedPath = exchange.requestURI.path
            receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, 0)
            val out = exchange.responseBody
            chunks.forEach {
                out.write(it.toByteArray())
                out.flush()
            }
        } finally {
            exchange.close()
        }
    }

    /** 一条 SSE data 事件 */
    private fun sse(json: String): String = "data: $json\n\n"

    private fun stream(protocol: Protocol): String {
        val backend = Backends.forProtocol(protocol)
        return runBlocking { backend.stream(request(protocol)).toList().joinToString("") }
    }

    @Test
    fun `sse chunks are parsed and concatenated`() {
        server.createContext(
            "/completions",
            respond(
                200,
                "text/event-stream",
                "event: message\n",
                ": keep-alive\n",
                sse("""{"choices":[{"text":"a + "}]}"""),
                sse("""{"choices":[{"text":"b"}]}"""),
                sse(SseParser.DONE),
            ),
        )
        assertEquals("a + b", stream(Protocol.OPENAI_FIM))
        assertEquals("/completions", receivedPath)
        assertEquals("Bearer sk-test", receivedAuthorization)
        assertTrue(receivedBody!!.contains("\"stream\":true"))
        assertTrue(receivedBody!!.contains(""""prompt":"func f() {\n\t""""))
    }

    @Test
    fun `chat deltas are read from delta content`() {
        server.createContext(
            "/chat/completions",
            respond(
                200,
                "text/event-stream",
                sse("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}"""),
                sse("""{"choices":[{"delta":{"content":"return "}}]}"""),
                sse("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""),
                sse(SseParser.DONE),
            ),
        )
        assertEquals("return x", stream(Protocol.OPENAI_CHAT))
        assertEquals("/chat/completions", receivedPath)
        assertTrue(receivedBody!!.contains("<code_before_cursor>"))
    }

    @Test
    fun `non stream json response is handled`() {
        server.createContext(
            "/completions",
            respond(200, "application/json", """{"choices":[{"text":"a + b","finish_reason":"stop"}]}"""),
        )
        assertEquals("a + b", stream(Protocol.OPENAI_FIM))
    }

    @Test
    fun `http errors become llm exceptions`() {
        server.createContext(
            "/chat/completions",
            respond(401, "application/json", """{"error":{"message":"Invalid API key","type":"auth"}}"""),
        )
        try {
            stream(Protocol.OPENAI_CHAT)
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertEquals(401, e.statusCode)
            assertEquals("Invalid API key", e.message)
            assertTrue(e.isAuthError)
        }
    }
}
