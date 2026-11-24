package com.harveybase.anytab.llm

/** 极简 SSE 解析：LLM 接口每个事件只有一行 data，逐行取负载即可 */
object SseParser {
    const val DONE = "[DONE]"

    /** 返回 data 字段的负载；不是 data 行返回 null */
    fun dataOf(line: String): String? = when {
        line.startsWith("data:") -> line.substring(5).removePrefix(" ")
        line == "data" -> ""
        else -> null
    }
}
