package com.harveybase.anytab.settings

import com.harveybase.anytab.completion.CompletionContext
import com.harveybase.anytab.llm.Backends
import com.harveybase.anytab.llm.CompletionOptions
import com.harveybase.anytab.llm.CompletionRequest
import com.harveybase.anytab.llm.LlmException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** 设置页“测试连接”用：发一个很小的补全请求，验证地址/密钥/协议都能通 */
object ProviderTester {
    private val TEST_CONTEXT = CompletionContext(
        prefix = "package main\n\nfunc add(a, b int) int {\n\treturn ",
        suffix = "\n}\n",
        currentLinePrefix = "\treturn ",
        languageId = "Go",
        filePath = "main.go",
    )

    private const val TIMEOUT_MS = 20_000L

    /** 返回“耗时 · 补全出来的首段文本”；配置不全或请求失败时抛异常 */
    fun test(provider: ProviderConfig, apiKey: String?): String {
        require(provider.isConfigured) { "Base URL and model are required" }
        val startedAt = System.nanoTime()
        val request = CompletionRequest(
            provider,
            apiKey,
            TEST_CONTEXT,
            CompletionOptions(maxTokens = 16, temperature = 0.0, timeoutMs = TIMEOUT_MS),
        )
        val text = runBlocking {
            withTimeout(TIMEOUT_MS) {
                val collected = StringBuilder()
                Backends.forProtocol(provider.protocolEnum).stream(request).collect { delta ->
                    if (collected.length < 80) collected.append(delta)
                }
                collected.toString()
            }
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val preview = text.trim().take(60)
        return "$elapsedMs ms · " + preview.ifEmpty { "(empty completion)" }
    }

    /** 把异常转成一行给用户看的说明 */
    fun describe(e: Throwable): String =
        (e as? LlmException)?.let { "HTTP ${it.statusCode}: ${it.message}" }
            ?: (e.message?.take(200) ?: e.javaClass.simpleName)
}
