package com.harveybase.anytab.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

/** 发给模型的上下文：光标前后各一段代码 + 文件信息 */
class CompletionContext(
    val prefix: String,
    val suffix: String,
    /** 当前行里光标之前的文本，用于识别模型“回显当前行” */
    val currentLinePrefix: String,
    val languageId: String,
    /** 相对项目根目录的路径；拿不到项目时只给文件名，避免暴露本机目录结构 */
    val filePath: String?,
)

/** 上下文窗口参数 */
data class ContextLimits(
    val prefixLines: Int,
    val prefixMaxChars: Int,
    val suffixLines: Int,
    val suffixMaxChars: Int,
)

/** 切好的窗口 */
data class ContextWindow(
    val prefix: String,
    val suffix: String,
    val currentLinePrefix: String,
)

/**
 * 负责从编辑器请求里取上下文：这个文件该不该补、光标前后各带多少。
 * 切窗口的纯文本逻辑放在 [window]，不依赖 IDE，方便单测。
 */
object ContextExtractor {
    /** 超过这个大小就不补了：既没必要，也避免把大文件内容发给模型 */
    const val MAX_FILE_CHARS = 200_000

    /** 把请求变成上下文；不该补（二进制、文件太大、文件名命中排除规则）返回 null */
    fun extract(request: InlineCompletionRequest, limits: ContextLimits, excludedPatterns: String): CompletionContext? {
        val file = request.file
        val document = request.document
        val virtualFile = file.virtualFile

        if (file.fileType.isBinary) return null
        if (document.textLength > MAX_FILE_CHARS) return null
        val fileName = virtualFile?.name ?: file.name
        if (isExcluded(fileName, excludedPatterns)) return null

        val offset = request.endOffset.coerceIn(0, document.textLength)
        // 只读光标附近的内容：多取一倍宽度，给“按整行截断”留余量
        val from = (offset - limits.prefixMaxChars * 2L).coerceAtLeast(0L).toInt()
        val to = (offset + limits.suffixMaxChars * 2L).coerceAtMost(document.textLength.toLong()).toInt()
        val text = document.getText(TextRange(from, to))

        val window = window(text, offset - from, limits)
        return CompletionContext(
            prefix = window.prefix,
            suffix = window.suffix,
            currentLinePrefix = window.currentLinePrefix,
            languageId = file.language.id,
            filePath = relativePath(request.editor.project?.basePath, virtualFile, fileName),
        )
    }

    /** 以 [cursor] 为界把 [text] 切成前文/后文：先满足字符数上限，切点尽量落在整行边界，再按行数收紧 */
    fun window(text: String, cursor: Int, limits: ContextLimits): ContextWindow {
        val cursor = cursor.coerceIn(0, text.length)
        val prefixMaxChars = limits.prefixMaxChars.coerceAtLeast(1)
        val suffixMaxChars = limits.suffixMaxChars.coerceAtLeast(1)

        // ---- 前文 ----
        var start = (cursor - prefixMaxChars).coerceAtLeast(0)
        if (start > 0) {
            // 起点落在行中间：如果这一行的行尾在光标之前，就把起点挪到下一行行首
            val lineBreak = text.indexOf('\n', start)
            if (lineBreak in start until cursor) start = lineBreak + 1
        }
        // 从光标往前最多带 prefixLines 行，也就是最多 prefixLines - 1 个换行
        val maxLineBreaks = (limits.prefixLines - 1).coerceAtLeast(0)
        var lineBreaks = 0
        var index = cursor - 1
        while (index >= start) {
            if (text[index] == '\n') {
                lineBreaks++
                if (lineBreaks > maxLineBreaks) {
                    start = index + 1
                    break
                }
            }
            index--
        }
        val prefix = text.substring(start, cursor)

        // ---- 后文 ----
        var end = (cursor + suffixMaxChars).coerceAtMost(text.length)
        if (end < text.length) {
            // 终点落在行中间：这一行剩下的部分不长就把它补全，太长就按字符数硬切
            val lineBreak = text.indexOf('\n', end)
            val lineEnd = if (lineBreak >= 0) lineBreak + 1 else text.length
            if (lineEnd <= cursor + suffixMaxChars * 2) end = lineEnd
        }
        // 从光标往后最多带 suffixLines 行
        val maxSuffixLines = limits.suffixLines.coerceAtLeast(1)
        var suffixLines = 0
        index = cursor
        while (index < end) {
            index++
            if (text[index - 1] == '\n') {
                suffixLines++
                if (suffixLines >= maxSuffixLines) {
                    end = index
                    break
                }
            }
        }
        val suffix = text.substring(cursor, end)

        // ---- 当前行光标之前 ----
        val lineStart = (text.lastIndexOf('\n', cursor - 1) + 1).coerceAtLeast(start)
        return ContextWindow(prefix, suffix, text.substring(lineStart, cursor))
    }

    /** 文件名是否命中排除规则（每行一条 glob） */
    fun isExcluded(fileName: String, patterns: String): Boolean =
        patterns.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .any { globMatches(it, fileName) }

    /** 只支持 `*` 和 `?` 的极简 glob，大小写不敏感；没有通配符时就是精确匹配 */
    fun globMatches(pattern: String, fileName: String): Boolean =
        matchGlob(pattern.lowercase(), 0, fileName.lowercase(), 0)

    private fun matchGlob(pattern: String, patternStart: Int, text: String, textStart: Int): Boolean {
        var p = patternStart
        var t = textStart
        while (p < pattern.length) {
            when (pattern[p]) {
                '*' -> {
                    while (p < pattern.length && pattern[p] == '*') p++
                    if (p == pattern.length) return true
                    // `*` 可以匹配任意长度，逐个起点试
                    for (next in t..text.length) {
                        if (matchGlob(pattern, p, text, next)) return true
                    }
                    return false
                }
                '?' -> {
                    if (t >= text.length) return false
                    p++
                    t++
                }
                else -> {
                    if (t >= text.length || pattern[p] != text[t]) return false
                    p++
                    t++
                }
            }
        }
        return t == text.length
    }

    private fun relativePath(basePath: String?, virtualFile: VirtualFile?, fileName: String): String {
        val file = virtualFile ?: return fileName
        val base = basePath ?: return fileName
        val path = file.path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else fileName
    }
}
