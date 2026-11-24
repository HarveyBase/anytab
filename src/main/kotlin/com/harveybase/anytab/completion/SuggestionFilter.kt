package com.harveybase.anytab.completion

/**
 * 流式补全过滤器：模型吐出来的增量先经过这里，再放行给编辑器渲染。
 *
 * 关键约束：已经放行的文本收不回来，所以凡是“看不准”的部分一律先攥在手里，等后续增量来佐证：
 *  - 模型对当前行的“回显”（重复输出光标前已有的内容）
 *  - 结尾的换行/空白（后面没内容了就不该有）
 *  - 与后文开头重叠的部分（模型开始复述光标后已有的代码）
 *  - 停止序列的前半截（markdown 围栏、FIM 结束标记）
 *  - 超出行数/长度上限的部分
 */
class SuggestionFilter(
    /** 当前行里光标之前的文本 */
    private val currentLinePrefix: String,
    /** 光标之后的代码，只用开头一小段做重叠判断 */
    suffix: String,
    /** 最多放行多少行 */
    private val maxLines: Int,
    /** 最多放行多少字符 */
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    /** 命中就截断的停止序列 */
    private val stopSequences: List<String> = DEFAULT_STOP_SEQUENCES,
) {
    /** 收到但还没放行的文本 */
    private val pending = StringBuilder()

    /** 后文里第一行有内容的代码，连同它前面的那个换行（不含行尾换行），用来识别“模型开始复述后文” */
    private val suffixHead: String = suffixHeadOf(suffix)

    private var emittedChars = 0
    private var emittedLineBreaks = 0
    private var leadingResolved = false

    /** 出现必须终止生成的情况：命中停止序列，或超出上限 */
    var isStopped = false
        private set

    val emittedLength: Int get() = emittedChars

    /** 喂入一段增量，返回现在可以安全放行的文本（可能为空串） */
    fun accept(delta: String): String {
        if (isStopped || delta.isEmpty()) return ""
        pending.append(delta)
        return release(final = false)
    }

    /** 流结束时调用：放行剩余内容，丢掉结尾空白和“疑似与后文重叠”的残留 */
    fun finish(): String {
        if (isStopped) return ""
        return release(final = true)
    }

    private fun release(final: Boolean): String {
        val output = StringBuilder()
        while (pending.isNotEmpty()) {
            if (!leadingResolved && !resolveLeading(final)) break
            if (!isStopped) applyStopSequences()
            if (pending.isEmpty()) break
            val suffixHold = suffixPrefixHold()
            val stopHold = if (isStopped || final) 0 else stopPrefixHold()
            val hold = maxOf(suffixHold, stopHold, trailingWhitespaceLength())
            val releasable = pending.length - hold
            if (releasable <= 0) break
            var chunk = pending.substring(0, releasable)
            pending.delete(0, releasable)
            chunk = applyLimits(chunk)
            if (chunk.isNotEmpty()) {
                emittedChars += chunk.length
                emittedLineBreaks += chunk.count { it == '\n' }
                output.append(chunk)
            }
            if (isStopped) break
        }
        return output.toString()
    }

    /**
     * 处理开头：模型经常把光标前已有的内容再输出一遍。
     * 返回 false 表示还看不清、需要更多输入。
     */
    private fun resolveLeading(final: Boolean): Boolean {
        val line = currentLinePrefix
        if (line.isBlank()) {
            // 光标在空行上：补全不应该以换行开头；已有缩进也不该重复
            stripWhile { it == '\n' }
            if (pending.isNotEmpty() && line.isNotEmpty()) {
                val received = pending.toString()
                if (received.length < line.length && line.startsWith(received)) {
                    return false
                }
                if (received.startsWith(line)) pending.delete(0, line.length)
            }
            stripWhile { it == '\n' }
        } else {
            if (shouldStripEcho(line)) {
                when {
                    pending.startsWith(line) -> pending.delete(0, line.length)
                    line.startsWith(pending.toString()) -> {
                        // 手上只有当前行的前缀：要么模型在回显，要么只是恰好同开头，等更多输入
                        if (!final) return false
                        pending.setLength(0)
                    }
                }
            }
            // 行尾是空白时，模型开头的空白是多余的
            if (line.last().isWhitespace()) stripWhile { it == ' ' || it == '\t' }
        }
        leadingResolved = true
        return true
    }

    /**
     * 是否要防“回显当前行”。
     * 行尾是字母/数字时不处理：比如光标在 `x` 后面，`x` 本身可能就是要补的内容（`xx`）。
     */
    private fun shouldStripEcho(line: String): Boolean =
        line.trim().length >= 2 && !line.last().isLetterOrDigit()

    /** 命中停止序列：开头就是的话整条丢弃（那是模型的“说明文字”），否则截断 */
    private fun applyStopSequences() {
        for (sequence in stopSequences) {
            val index = pending.indexOf(sequence)
            if (index >= 0) {
                pending.setLength(if (index == 0 && emittedChars == 0) 0 else index)
                isStopped = true
                return
            }
        }
    }

    /**
     * 后文重叠处理：
     *  - 手上正好以后文第一行结尾 → 模型在复述后文，截掉并停止
     *  - 手上结尾是后文开头的一小截 → 先不放，等后续增量判断
     * 返回需要攥住的字符数。
     */
    private fun suffixPrefixHold(): Int {
        val head = suffixHead
        if (head.length < 2) return 0
        // 手上出现了和后文第一行完全相同的内容：模型在复述后文，截掉并停止。
        // 单字符的参照不做这一步——后文以 `)` 开头时补全里到处都是括号，只走下面“先攥着”的逻辑
        val core = head.removeSuffix("\n")
        if (core.length >= 2) {
            val duplicated = pending.indexOf(core)
            if (duplicated >= 0) {
                pending.setLength(duplicated)
                isStopped = true
                return 0
            }
        }
        val maxLength = minOf(pending.length, head.length - 1)
        for (length in maxLength downTo 1) {
            if (pending.regionMatches(pending.length - length, head, 0, length)) return length
        }
        return 0
    }

    /** 手上结尾如果正好是某个停止序列的前半截，先攥着 */
    private fun stopPrefixHold(): Int {
        var best = 0
        for (sequence in stopSequences) {
            val maxLength = minOf(pending.length, sequence.length - 1)
            for (length in maxLength downTo best + 1) {
                if (pending.regionMatches(pending.length - length, sequence, 0, length)) {
                    best = length
                    break
                }
            }
        }
        return best
    }

    private fun trailingWhitespaceLength(): Int {
        var length = 0
        var index = pending.length - 1
        while (index >= 0 && pending[index].let { it == ' ' || it == '\t' || it == '\n' }) {
            length++
            index--
        }
        return length
    }

    private fun stripWhile(predicate: (Char) -> Boolean) {
        var index = 0
        while (index < pending.length && predicate(pending[index])) index++
        if (index > 0) pending.delete(0, index)
    }

    /** 按行数/长度上限截断，超出就停止生成 */
    private fun applyLimits(chunk: String): String {
        val allowedLineBreaks = (maxLines - 1) - emittedLineBreaks
        var result = chunk
        if (allowedLineBreaks < 0) {
            isStopped = true
            return ""
        }
        var lineBreaks = 0
        var cut = -1
        for (i in result.indices) {
            if (result[i] == '\n') {
                lineBreaks++
                if (lineBreaks > allowedLineBreaks) {
                    cut = i
                    break
                }
            }
        }
        if (cut >= 0) {
            result = result.substring(0, cut)
            isStopped = true
        }
        if (emittedChars + result.length > maxChars) {
            result = result.substring(0, (maxChars - emittedChars).coerceAtLeast(0))
            isStopped = true
        }
        return result
    }

    /**
     * 取后文里第一行非空代码，作为“可能被模型复述”的参照：
     *  - 光标在行尾时后文以换行开头，所以要把紧挨着这一行的那个换行也算进去（`"\n}"`）
     *  - 保留行尾换行：模型复述到行尾时，最后那个换行可能还没到，要靠“先攥着”等它
     *  - 太长就截断
     */
    private fun suffixHeadOf(suffix: String): String {
        var index = 0
        var afterLineBreak = false
        while (index < suffix.length) {
            val lineBreak = suffix.indexOf('\n', index)
            val lineEnd = if (lineBreak < 0) suffix.length else lineBreak
            val line = suffix.substring(index, lineEnd)
            if (line.isNotBlank()) {
                val head = (if (afterLineBreak) "\n" else "") + suffix.substring(index, minOf(suffix.length, lineEnd + 1))
                return if (head.length > MAX_SUFFIX_HEAD_CHARS) head.substring(0, MAX_SUFFIX_HEAD_CHARS) else head
            }
            afterLineBreak = true
            index = lineEnd + 1
        }
        return ""
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 2000
        const val MAX_SUFFIX_HEAD_CHARS = 80

        /** markdown 围栏 + 常见 FIM 结束标记 */
        val DEFAULT_STOP_SEQUENCES: List<String> = listOf("```", "<|fim_end|>", "<|endoftext|>")
    }
}
