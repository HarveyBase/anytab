package com.harveybase.anytab.llm

/** 调用模型接口失败。[statusCode] 为 0 表示没拿到 HTTP 状态（网络异常等） */
class LlmException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val isAuthError: Boolean get() = statusCode == 401 || statusCode == 403
}
