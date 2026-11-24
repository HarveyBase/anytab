package com.harveybase.anytab.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.stream.Collectors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 JDK 自带 HttpClient 的 OpenAI 兼容客户端，不引入任何第三方 HTTP 依赖。
 * 只提供两个能力：POST 后逐行读响应（流式/非流式通用），以及 GET 一段 JSON。
 */
object OpenAiClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** 请求头：Bearer 鉴权 + 用户自定义的额外请求头 */
    fun headers(apiKey: String?, extraHeaders: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!apiKey.isNullOrBlank()) {
            result["Authorization"] = "Bearer $apiKey"
        }
        parseHeaders(extraHeaders).forEach { (name, value) -> result[name] = value }
        return result
    }

    /** 每行一条 `Key: Value`，`#` 开头的行忽略 */
    fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }
            .toMap(LinkedHashMap())

    fun url(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + path

    /**
     * POST 一个 JSON 请求体，把响应体按行吐出来。
     * 协程被取消（用户继续输入、光标移动）时会主动断开底层连接，不让请求挂着。
     */
    fun postLines(url: String, headers: Map<String, String>, bodyJson: String): Flow<String> = flow {
        val builder = HttpRequest.newBuilder(URI.create(url))
            // 只约束“等到响应头”的时间；整体超时由调用方的协程超时负责
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        headers.forEach { (name, value) -> builder.header(name, value) }

        val future = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines())
        val response = future.awaitCancellable()
        val lines = response.body()
        // 协程一结束（含取消）就关流：阻塞在读下一行上的迭代会立刻解除
        val cancelHook = coroutineContext.job.invokeOnCompletion {
            runCatching { lines.close() }
            future.cancel(true)
        }
        try {
            if (response.statusCode() !in 200..299) {
                val errorBody = lines.use { stream -> stream.limit(40).collect(Collectors.joining("\n")) }
                throw LlmException(response.statusCode(), extractErrorMessage(errorBody))
            }
            lines.use { stream ->
                for (line in stream) {
                    currentCoroutineContext().ensureActive()
                    emit(line)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 协程已被取消时关流会抛 IO 异常，这种情况不算错误，静默结束
            if (currentCoroutineContext().isActive) throw e
        } finally {
            cancelHook.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** GET 一段 JSON（如 /models）并整段读回；非 2xx 抛 LlmException */
    suspend fun getJson(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).awaitCancellable()
        val body = response.body() ?: ""
        if (response.statusCode() !in 200..299) {
            throw LlmException(response.statusCode(), extractErrorMessage(body))
        }
        body
    }

    /** 尽量从响应体里挖出人能看懂的报错：先按 `error.message`，再按 `message`，最后截一段原文 */
    internal fun extractErrorMessage(body: String): String {
        val json = Json.parse(body)
        json?.objectAt("error")?.stringAt("message")?.let { return it }
        json?.stringAt("message")?.let { return it }
        return body.trim().take(300).ifEmpty { "empty response" }
    }

    /** CompletableFuture -> 挂起，协程取消时把 future 一并取消 */
    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            when {
                error == null -> cont.resume(value)
                error is CompletionException && error.cause != null -> cont.resumeWithException(error.cause!!)
                else -> cont.resumeWithException(error)
            }
        }
        cont.invokeOnCancellation { cancel(true) }
    }
}
