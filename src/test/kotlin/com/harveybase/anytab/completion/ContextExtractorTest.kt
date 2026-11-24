package com.harveybase.anytab.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextExtractorTest {

    private val lines = "l0\nl1\nl2\nl3\nl4"
    private val cursorAfterL2 = "l0\nl1\nl2".length

    @Test
    fun `window is limited by line count`() {
        val window = ContextExtractor.window(
            lines,
            cursorAfterL2,
            ContextLimits(prefixLines = 2, prefixMaxChars = 100, suffixLines = 2, suffixMaxChars = 100),
        )
        assertEquals("l1\nl2", window.prefix)
        assertEquals("\nl3\n", window.suffix)
        assertEquals("l2", window.currentLinePrefix)
    }

    @Test
    fun `prefix starting mid-line is moved to the next full line`() {
        val text = "aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\ndddddddddd"
        val cursor = "aaaaaaaaaa\nbbbbbbbbbb\nccc".length
        // 只允许 12 个字符的前文：起点落在第二行中间，于是丢掉半行、从第三行开始
        val window = ContextExtractor.window(
            text,
            cursor,
            ContextLimits(prefixLines = 10, prefixMaxChars = 12, suffixLines = 10, suffixMaxChars = 100),
        )
        assertEquals("ccc", window.prefix)
        assertEquals("ccccccc\ndddddddddd", window.suffix)
        assertEquals("ccc", window.currentLinePrefix)
    }

    @Test
    fun `very long line is cut by chars instead of skipped`() {
        val text = "x".repeat(1000) + "\n" + "y".repeat(1000)
        val cursor = 1500
        val window = ContextExtractor.window(
            text,
            cursor,
            ContextLimits(prefixLines = 10, prefixMaxChars = 400, suffixLines = 10, suffixMaxChars = 100),
        )
        assertEquals("y".repeat(400), window.prefix)
        assertEquals("y".repeat(100), window.suffix)
        // 当前行从第二行行首开始，但窗口只截取了 400 个字符
        assertEquals("y".repeat(400), window.currentLinePrefix)
    }

    @Test
    fun `cursor at file start and end`() {
        val limits = ContextLimits(10, 100, 10, 100)
        val head = ContextExtractor.window(lines, 0, limits)
        assertEquals("", head.prefix)
        assertEquals(lines, head.suffix)
        assertEquals("", head.currentLinePrefix)
        val tail = ContextExtractor.window(lines, lines.length, limits)
        assertEquals(lines, tail.prefix)
        assertEquals("", tail.suffix)
        assertEquals("l4", tail.currentLinePrefix)
    }

    @Test
    fun `cursor in the middle of a line`() {
        // 光标落在 "l3" 的 l 和 3 之间
        val window = ContextExtractor.window(lines, "l0\nl1\nl2\nl".length, ContextLimits(1, 100, 1, 100))
        assertEquals("l", window.prefix)
        assertEquals("3\n", window.suffix)
        assertEquals("l", window.currentLinePrefix)
    }

    @Test
    fun `suffix ends at a full line when the rest of the line is short`() {
        // 上限 10 个字符落在 12 个 b 中间：剩下的 2 个字符不长，把这一行补全
        val text = "a\n" + "b".repeat(12)
        val window = ContextExtractor.window(text, 2, ContextLimits(10, 100, 10, 10))
        assertEquals("a\n", window.prefix)
        assertEquals("b".repeat(12), window.suffix)
    }

    @Test
    fun `suffix is cut by chars when the rest of the line is long`() {
        val text = "a\n" + "b".repeat(60)
        val window = ContextExtractor.window(text, 2, ContextLimits(10, 100, 10, 10))
        assertEquals("b".repeat(10), window.suffix)
    }

    // ---- 排除规则 ----

    @Test
    fun `glob patterns`() {
        assertTrue(ContextExtractor.globMatches("*.md", "README.md"))
        assertTrue(ContextExtractor.globMatches("*.MD", "readme.md"))
        assertTrue(ContextExtractor.globMatches(".env*", ".env.local"))
        assertTrue(ContextExtractor.globMatches("id_rsa*", "id_rsa_ed25519"))
        assertTrue(ContextExtractor.globMatches("go.mod", "go.mod"))
        assertTrue(ContextExtractor.globMatches("a?c", "abc"))
        assertTrue(ContextExtractor.globMatches("*_test.go", "log_test.go"))
        assertTrue(ContextExtractor.globMatches("*.*", "a.b"))
        assertFalse(ContextExtractor.globMatches("*.md", "main.go"))
        assertFalse(ContextExtractor.globMatches("go.mod", "go.sum"))
        assertFalse(ContextExtractor.globMatches("a?c", "abbc"))
        assertFalse(ContextExtractor.globMatches("*.*", "ab"))
    }

    @Test
    fun `excluded patterns are one per line and may contain comments`() {
        val patterns = "# docs\n*.md\n\n  *.txt  "
        assertTrue(ContextExtractor.isExcluded("a.md", patterns))
        assertTrue(ContextExtractor.isExcluded("a.txt", patterns))
        assertFalse(ContextExtractor.isExcluded("a.go", patterns))
        assertFalse(ContextExtractor.isExcluded("a.md", ""))
    }
}
