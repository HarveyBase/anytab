package com.harveybase.anytab

import com.harveybase.anytab.llm.Preset
import com.harveybase.anytab.llm.Protocol
import com.harveybase.anytab.llm.SseParser
import com.harveybase.anytab.settings.DEFAULT_EXCLUDED_FILE_PATTERNS
import com.harveybase.anytab.settings.AnyTabSettings
import com.harveybase.anytab.settings.ProviderConfig
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 运行期冒烟测试：在无头 IDE 里走完整条链路——扩展点注册 → 服务 → 平台触发 → 引擎 → 本机模拟的模型服务 → 渲染 → Tab 采纳。
 * 模型服务是本机的一个 HTTP 模拟服务，不需要真实 API Key。
 */
class AnyTabInlineCompletionSmokeTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer

    /** 模拟服务收到的最后一个请求体 */
    private var lastRequestBody: String? = null

    private val settings: AnyTabSettings get() = AnyTabSettings.getInstance()

    /** 行内补全的测试 DSL 要求 JUnit4 测试不要在 dispatch 线程里跑 */
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/completions") { exchange ->
            lastRequestBody = exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("""data: {"choices":[{"text":"return a + b"}]}""".toByteArray())
                out.write("\n\n".toByteArray())
                out.write("data: ${SseParser.DONE}\n\n".toByteArray())
            }
        }
        server.start()

        settings.state.enabled = true
        settings.state.autoTrigger = true
        settings.state.debounceMs = 0
        settings.state.excludedFilePatterns = ""
        settings.state.providers = mutableListOf(
            ProviderConfig.create(Preset.CUSTOM).apply {
                name = "Mock"
                baseUrl = "http://127.0.0.1:${server.address.port}"
                protocol = Protocol.OPENAI_FIM.name
                model = "mock-model"
            },
        )
        settings.state.activeProviderId = settings.state.providers.first().id
    }

    override fun tearDown() {
        settings.state.providers = mutableListOf()
        settings.state.activeProviderId = null
        settings.state.excludedFilePatterns = DEFAULT_EXCLUDED_FILE_PATTERNS
        server.stop(0)
        super.tearDown()
    }

    fun testManualTriggerRendersSuggestionAndTabInsertsIt() {
        myFixture.testInlineCompletion {
            init(PlainTextFileType.INSTANCE, "package main\n\nfunc add(a, b int) int {\n\t<caret>\n}\n")
            callInlineCompletion()
            delay()
            assertInlineRender("return a + b")
            insertWithTab()
            assertFileContent("package main\n\nfunc add(a, b int) int {\n\treturn a + b<caret>\n}\n")
        }

        // 发给模型的请求里带着光标前后各一段代码
        val body = lastRequestBody!!
        assertTrue(body.contains(""""prompt":"package main"""))
        assertTrue(body.contains("func add(a, b int) int {"))
        assertTrue(body.contains(""""suffix":"\n}"""))
    }

    fun testTypingTriggersSuggestion() {
        myFixture.testInlineCompletion {
            init(PlainTextFileType.INSTANCE, "package main\n\nfunc add(a, b int) int {\n\t<caret>\n}\n")
            typeChar('r')
            delay()
            assertInlineRender("return a + b")
            escape()
            assertInlineHidden()
        }
    }

    fun testExcludedFileGetsNoSuggestion() {
        settings.state.excludedFilePatterns = "*.txt"
        myFixture.testInlineCompletion {
            init(PlainTextFileType.INSTANCE, "package main\n<caret>\n")
            callInlineCompletion()
            delay()
            assertInlineHidden()
        }
        assertNull("被排除的文件不应该发请求", lastRequestBody)
    }
}
