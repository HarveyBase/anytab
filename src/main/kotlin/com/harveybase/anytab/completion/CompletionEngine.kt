package com.harveybase.anytab.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.harveybase.anytab.llm.Backends
import com.harveybase.anytab.llm.CompletionOptions
import com.harveybase.anytab.llm.CompletionRequest
import com.harveybase.anytab.llm.LlmException
import com.harveybase.anytab.settings.AnyTabSettings
import com.harveybase.anytab.settings.ApiKeyStore
import com.harveybase.anytab.settings.ProviderConfig
import com.harveybase.anytab.settings.protocolEnum
import com.harveybase.anytab.ui.Notifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 补全引擎（应用级服务）：把编辑器请求变成一条“灰字元素流”。
 *
 * 流程：取上下文 → 查缓存 → 读 API Key → 按协议调模型 → 过滤增量 → 逐片放行。
 * 平台负责防抖、取消、渲染和 Tab 采纳；这里只管内容。
 */
@Service(Service.Level.APP)
class CompletionEngine {
    private val LOG = thisLogger()

    private val cache = CompletionCache()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 正在进行的请求数，状态栏据此显示“请求中” */
    @Volatile
    var inFlight: Int = 0
        private set

    /** 最近一次失败的原因，状态栏提示用 */
    @Volatile
    var lastError: String? = null
        private set

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun fireChanged() {
        listeners.forEach { it() }
    }

    private fun emptySuggestion(): InlineCompletionSingleSuggestion =
        InlineCompletionSingleSuggestion.build(elements = emptyFlow())

    suspend fun suggest(request: InlineCompletionRequest): InlineCompletionSingleSuggestion {
        val settings = AnyTabSettings.getInstance()
        val state = settings.state
        val provider = settings.activeProvider() ?: return emptySuggestion()

        val limits = ContextLimits(state.prefixLines, state.prefixMaxChars, state.suffixLines, state.suffixMaxChars)
        // 取上下文要读 Document/PSI，放在读锁里；PasswordSafe 不能持读锁调用，所以放在后面
        val context = readAction { ContextExtractor.extract(request, limits, state.excludedFilePatterns) }
            ?: return emptySuggestion()

        val cacheKey = CompletionCache.key(provider.id, provider.model, provider.protocol, context)
        cache.get(cacheKey)?.let { cached ->
            return InlineCompletionSingleSuggestion.build(elements = flowOf(InlineCompletionGrayTextElement(cached)))
        }

        val apiKey = withContext(Dispatchers.IO) { ApiKeyStore.get(provider.id) }
        val options = CompletionOptions(state.maxTokens, state.temperature, state.requestTimeoutMs.toLong())
        val completionRequest = CompletionRequest(provider, apiKey, context, options)
        val project = request.editor.project
        val filter = SuggestionFilter(context.currentLinePrefix, context.suffix, state.maxLines)

        val collected = StringBuilder()
        var failed = false
        val elements: Flow<InlineCompletionGrayTextElement> =
            Backends.forProtocol(provider.protocolEnum)
                .stream(completionRequest)
                .withOverallTimeout(state.requestTimeoutMs.toLong())
                // 这个协程库版本的 transformWhile 用 collector 作 lambda 的 receiver
                .transformWhile { delta ->
                    val piece = filter.accept(delta)
                    if (piece.isNotEmpty()) {
                        collected.append(piece)
                        emit(InlineCompletionGrayTextElement(piece))
                    }
                    // 命中停止条件就不再向模型要后续内容（上游 HTTP 请求会被取消）
                    !filter.isStopped
                }
                .catch { e ->
                    when (e) {
                        is TimeoutCancellationException -> {
                            failed = true
                            lastError = "timeout"
                        }
                        // 真正的取消（用户继续输入、光标移动）原样抛出，不算错误
                        is CancellationException -> throw e
                        else -> {
                            failed = true
                            handleError(e, project, provider)
                        }
                    }
                }
                .onCompletion { cause ->
                    if (cause == null && !failed && !filter.isStopped) {
                        val tail = filter.finish()
                        if (tail.isNotEmpty()) {
                            collected.append(tail)
                            emit(InlineCompletionGrayTextElement(tail))
                        }
                    }
                    if (cause == null && !failed && collected.length >= CompletionCache.MIN_CACHED_LENGTH) {
                        cache.put(cacheKey, collected.toString())
                    }
                    inFlight--
                    fireChanged()
                }
                .onStart {
                    inFlight++
                    lastError = null
                    fireChanged()
                }

        return InlineCompletionSingleSuggestion.build(elements = elements)
    }

    private fun handleError(e: Throwable, project: Project?, provider: ProviderConfig) {
        when (e) {
            is LlmException -> {
                lastError = if (e.statusCode > 0) "HTTP ${e.statusCode}" else e.message
                LOG.warn("AnyTab: request to ${provider.name} failed: HTTP ${e.statusCode} ${e.message}")
                if (e.isAuthError) {
                    Notifier.authError(project, provider.id, provider.name)
                }
            }
            // 连不上、DNS 失败之类：用户可能只是暂时没网，记 info 就行
            is IOException -> {
                lastError = e.javaClass.simpleName
                LOG.info("AnyTab: request to ${provider.name} failed: ${e.message}")
            }
            else -> {
                lastError = e.javaClass.simpleName
                LOG.warn("AnyTab: request to ${provider.name} failed", e)
            }
        }
    }

    /** 给上游加一个整体超时：超时抛 TimeoutCancellationException，由 catch 分支记为失败 */
    private fun <T> Flow<T>.withOverallTimeout(timeoutMs: Long): Flow<T> = flow {
        withTimeout(timeoutMs) {
            emitAll(this@withOverallTimeout)
        }
    }

    companion object {
        fun getInstance(): CompletionEngine =
            ApplicationManager.getApplication().getService(CompletionEngine::class.java)
    }
}
