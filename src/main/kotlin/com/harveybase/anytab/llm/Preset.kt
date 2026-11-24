package com.harveybase.anytab.llm

/**
 * 供应商预设：新建供应商时预填地址、协议、模型。
 * 预设只是“默认值”，用户可以在设置页里随便改。
 */
enum class Preset(
    /** 设置页里显示的名字 */
    val title: String,
    val defaultBaseUrl: String,
    val defaultProtocol: Protocol,
    val defaultModel: String,
    /** 预填的额外请求体（JSON 对象），会浅合并进请求 */
    val defaultExtraBody: String = "",
) {
    /** DeepSeek：官方 FIM（Beta）接口，base_url 必须带 /beta */
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/beta", Protocol.OPENAI_FIM, "deepseek-flash"),

    /**
     * 智谱 GLM：标准按量接口，没有 FIM，只能走 chat 填空。
     * 5.3 系列不支持关闭思考，补全延迟太高，所以默认用支持关闭思考的 4.7-FlashX。
     */
    GLM(
        "GLM (智谱)",
        "https://open.bigmodel.cn/api/paas/v4",
        Protocol.OPENAI_CHAT,
        "glm-4.7-flashx",
        """{"thinking":{"type":"disabled"}}""",
    ),

    /** NewAPI 网关：地址自填，能透传哪种协议取决于上游渠道 */
    NEWAPI("NewAPI", "", Protocol.OPENAI_CHAT, ""),

    /** 任意 OpenAI 兼容端点（Ollama、LM Studio、vLLM、硅基流动等） */
    CUSTOM("Custom", "", Protocol.OPENAI_CHAT, "");

    companion object {
        fun of(name: String?): Preset = entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}
