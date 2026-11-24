package com.harveybase.anytab.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 把几段增量依次喂给过滤器，返回最终放行的文本 */
private fun collect(
    line: String,
    suffix: String = "",
    vararg deltas: String,
    maxLines: Int = 6,
    maxChars: Int = SuggestionFilter.DEFAULT_MAX_CHARS,
): String {
    val filter = SuggestionFilter(line, suffix, maxLines, maxChars)
    val output = StringBuilder()
    deltas.forEach { output.append(filter.accept(it)) }
    output.append(filter.finish())
    return output.toString()
}

class SuggestionFilterTest {

    // ---- 正常放行 ----

    @Test
    fun `plain completion passes through`() {
        assertEquals("a + b", collect("return ", "\n}\n", "a + b"))
    }

    @Test
    fun `completion split into many chunks is unchanged`() {
        assertEquals("return x + y", collect("x := ", "\n", "ret", "urn ", "x ", "+ ", "y"))
    }

    @Test
    fun `leading newline is kept when cursor is at end of a non-empty line`() {
        assertEquals("\nreturn x", collect("import \"fmt\"", "\n", "\nreturn x"))
    }

    // ---- 模型回显当前行 ----

    @Test
    fun `echo of current line is stripped`() {
        assertEquals("x", collect("\treturn ", "\n}\n", "\treturn x"))
    }

    @Test
    fun `echo of current line split across chunks is stripped`() {
        assertEquals("x", collect("return ", "\n", "ret", "urn x"))
    }

    @Test
    fun `partial echo at end of stream is dropped`() {
        assertEquals("", collect("return ", "\n", "ret"))
    }

    @Test
    fun `indentation on blank line is not duplicated`() {
        assertEquals("foo()", collect("    ", "\n}\n", "    foo()"))
    }

    @Test
    fun `extra indentation on blank line is kept`() {
        assertEquals("  foo()", collect("    ", "\n}\n", "      foo()"))
    }

    @Test
    fun `leading newline on blank line is dropped`() {
        assertEquals("foo", collect("", "\n}", "\nfoo"))
    }

    @Test
    fun `short line ending with a letter is not treated as echo`() {
        // 光标在 x 后面，模型想补的就是另一个 x
        assertEquals("x", collect("x", "", "x"))
    }

    @Test
    fun `whitespace after line ending with space is not duplicated`() {
        assertEquals("x", collect("return ", "\n", " x"))
    }

    // ---- 与后文重叠 ----

    @Test
    fun `duplicated suffix line is cut`() {
        assertEquals("\n\ta()", collect("func f() {", "\n}\n", "\n\ta()\n}"))
    }

    @Test
    fun `duplicated suffix line across chunks is cut`() {
        assertEquals("\n\ta()", collect("func f() {", "\n}\n", "\n\ta()", "\n", "}"))
    }

    @Test
    fun `partial suffix overlap at end of stream is dropped`() {
        assertEquals("baz", collect("foo(bar", ")\n", "baz", ")"))
    }

    @Test
    fun `closing brace with different indentation is not treated as suffix duplication`() {
        // 后文是 "\n}"，补全里的 "\t}" 不是它，不该把后面的内容截掉
        assertEquals("if a {\n\tb()\n\t}\n\tc()", collect("x", "\n}\n", "if a {\n\tb()\n\t}\n\tc()"))
    }

    @Test
    fun `blank lines before the duplicated suffix line are skipped`() {
        // 后文以空行开头：参照的是第一行有内容的代码
        assertEquals("", collect("package main", "\n\nimport (", "\n\nimport (\"fmt\")"))
        assertEquals("\n\nconst x = 1", collect("package main", "\n\nimport (", "\n\nconst x = 1"))
    }

    // ---- 停止序列 ----

    @Test
    fun `markdown fence at start rejects the whole suggestion`() {
        assertEquals("", collect("", "", "```go\nfunc main() {}\n```"))
    }

    @Test
    fun `markdown fence in the middle cuts the suggestion`() {
        assertEquals("foo(", collect("", "", "foo(", "```\nbar"))
    }

    @Test
    fun `fim end token cuts the suggestion`() {
        assertEquals("return x", collect("", "", "return x<|fim_end|>garbage"))
    }

    // ---- 上限 ----

    @Test
    fun `line limit stops generation at the nth newline`() {
        val filter = SuggestionFilter("", "", maxLines = 2)
        assertEquals("a\nb", filter.accept("a\nb\nc\nd"))
        assertTrue(filter.isStopped)
    }

    @Test
    fun `single line mode stops at first newline`() {
        val filter = SuggestionFilter("", "\n", maxLines = 1)
        assertEquals("return x", filter.accept("return x\nfoo"))
        assertTrue(filter.isStopped)
    }

    @Test
    fun `char limit truncates`() {
        assertEquals("abcde", collect("", "", "abcdefgh", maxChars = 5))
    }

    // ---- 结尾空白 ----

    @Test
    fun `trailing newline and spaces are dropped`() {
        assertEquals("x := 1", collect("", "", "x := 1\n\n  "))
    }

    @Test
    fun `whitespace only suggestion is dropped`() {
        assertEquals("", collect("", "", "   \n\t"))
    }

    @Test
    fun `newline in the middle is kept`() {
        assertEquals("a\nb", collect("", "", "a\nb"))
    }

    @Test
    fun `stopped filter ignores later chunks`() {
        val filter = SuggestionFilter("", "\n}", maxLines = 6)
        assertEquals("x", filter.accept("x\n}"))
        assertTrue(filter.isStopped)
        assertEquals("", filter.accept("more"))
        assertEquals("", filter.finish())
        assertFalse(filter.emittedLength == 0)
    }
}
