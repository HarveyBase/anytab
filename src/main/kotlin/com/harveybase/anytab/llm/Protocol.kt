package com.harveybase.anytab.llm

/**
 * 与模型通信的协议：决定请求打到哪个 endpoint、怎么组请求体、怎么从响应里取增量。
 * [path] 是拼在供应商 Base URL 后面的路径。
 */
enum class Protocol(val path: String) {
    /** 原生 FIM：POST /completions，带 prompt + suffix（DeepSeek /beta、vLLM、LM Studio 等） */
    OPENAI_FIM("/completions"),

    /** 模型没有 FIM 接口时，用 chat 接口 + 填空提示词（GLM、NewAPI 后面的聊天模型等） */
    OPENAI_CHAT("/chat/completions"),

    /** 用 FIM 特殊标记把前后文拼成一个 prompt，POST /completions（vLLM / Ollama 直出的 Qwen、DeepSeek-Coder 类模型） */
    FIM_TOKENS("/completions");

    companion object {
        fun of(name: String?): Protocol = entries.firstOrNull { it.name == name } ?: OPENAI_CHAT
    }
}
