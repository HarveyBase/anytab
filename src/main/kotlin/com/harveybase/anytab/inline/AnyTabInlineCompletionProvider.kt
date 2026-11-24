package com.harveybase.anytab.inline

import com.harveybase.anytab.completion.CompletionEngine
import com.harveybase.anytab.settings.AnyTabProjectState
import com.harveybase.anytab.settings.AnyTabSettings
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 接入 IntelliJ 平台的行内补全入口。
 *
 * 平台负责：什么时候发请求、防抖基类、取消过期请求、画灰字、Tab / Esc / 逐词逐行采纳。
 * 这里只回答两个问题：这个事件要不要接（[isEnabled]），接了给什么内容（[getSuggestionDebounced]）。
 */
class AnyTabInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    /** 必须与 plugin.xml 里 <inline.completion.provider id="..."> 一致 */
    override val id: InlineCompletionProviderID =
        InlineCompletionProviderID("com.harveybase.anytab.inline.AnyTabInlineCompletionProvider")

    private val settings: AnyTabSettings get() = AnyTabSettings.getInstance()

    @RequiresEdt
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!settings.state.enabled) return false
        // 项目级开关：事件里拿不到编辑器的情况很少见，拿不到就按“不拦”处理
        val project = editorOf(event)?.project
        if (project != null && !AnyTabProjectState.getInstance(project).isEnabled()) return false
        if (!settings.hasUsableProvider()) return false
        // 自动触发可以单独关掉，关掉后只剩手动触发（平台自带动作，默认 Alt+Shift+\）
        return event !is InlineCompletionEvent.DocumentChange || settings.state.autoTrigger
    }

    /** 打字触发的请求等用户停下来再发；手动触发立即执行 */
    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        if (request.event is InlineCompletionEvent.DocumentChange) {
            settings.state.debounceMs.coerceAtLeast(0).toLong().milliseconds
        } else {
            Duration.ZERO
        }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSingleSuggestion =
        CompletionEngine.getInstance().suggest(request)

    private fun editorOf(event: InlineCompletionEvent) = when (event) {
        is InlineCompletionEvent.DocumentChange -> event.editor
        is InlineCompletionEvent.DirectCall -> event.editor
        else -> event.toRequest()?.editor
    }
}
