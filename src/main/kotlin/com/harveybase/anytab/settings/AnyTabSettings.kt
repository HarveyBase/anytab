package com.harveybase.anytab.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.concurrent.CopyOnWriteArrayList

/** 默认不补全的文件：文档/数据/日志，以及可能带密钥的文件（避免把密钥发给模型） */
const val DEFAULT_EXCLUDED_FILE_PATTERNS = "*.md\n*.markdown\n*.txt\n*.log\n*.lock\n*.csv\n*.svg\n.env*\n*.pem\n*.key\nid_rsa*"

/** 全局设置（应用级）。真正的持久化载体是 [State]，页面编辑时操作的是它的副本 */
@Service(Service.Level.APP)
@State(name = "AnyTabSettings", storages = [Storage("anytab.xml")])
class AnyTabSettings : PersistentStateComponent<AnyTabSettings.State> {

    data class State(
        /** 总开关（应用级）；项目级开关见 [AnyTabProjectState] */
        var enabled: Boolean = true,
        /** 输入时自动触发；关掉后只能手动触发（Alt+Shift+\\） */
        var autoTrigger: Boolean = true,
        /** 停止输入多少毫秒后才发请求 */
        var debounceMs: Int = 300,
        /** 补全最多生成多少 token */
        var maxTokens: Int = 128,
        /** 补全最多多少行 */
        var maxLines: Int = 6,
        /** 光标前最多带多少行 */
        var prefixLines: Int = 40,
        /** 光标前最多带多少字符 */
        var prefixMaxChars: Int = 4000,
        /** 光标后最多带多少行 */
        var suffixLines: Int = 20,
        /** 光标后最多带多少字符 */
        var suffixMaxChars: Int = 1000,
        /** 单次请求的整体超时 */
        var requestTimeoutMs: Int = 15_000,
        /** 采样温度，补全场景越低越稳 */
        var temperature: Double = 0.1,
        /** 不补全的文件名 glob，每行一条 */
        var excludedFilePatterns: String = DEFAULT_EXCLUDED_FILE_PATTERNS,
        var providers: MutableList<ProviderConfig> = mutableListOf(),
        /** 当前使用的供应商；为空或不可用时取第一个启用的 */
        var activeProviderId: String? = null,
    ) {
        /** 深拷贝：providers 里的 bean 也复制一份，页面编辑不会污染已保存的配置 */
        fun deepCopy(): State = copy(providers = providers.map { it.copy() }.toMutableList())

        /** 把 [other] 的内容拷进当前实例。保持对象身份不变，这样设置页上按属性引用做的绑定不会失效 */
        fun copyFrom(other: State) {
            enabled = other.enabled
            autoTrigger = other.autoTrigger
            debounceMs = other.debounceMs
            maxTokens = other.maxTokens
            maxLines = other.maxLines
            prefixLines = other.prefixLines
            prefixMaxChars = other.prefixMaxChars
            suffixLines = other.suffixLines
            suffixMaxChars = other.suffixMaxChars
            requestTimeoutMs = other.requestTimeoutMs
            temperature = other.temperature
            excludedFilePatterns = other.excludedFilePatterns
            providers = other.providers.map { it.copy() }.toMutableList()
            activeProviderId = other.activeProviderId
        }
    }

    private var state = State()

    /** 配置或运行状态变化时通知（主要是状态栏刷新） */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** 当前该用哪个供应商：优先用户选中的，其次第一个启用且填完整的 */
    fun activeProvider(): ProviderConfig? =
        state.providers.firstOrNull { it.id == state.activeProviderId && it.enabled && it.isConfigured }
            ?: state.providers.firstOrNull { it.enabled && it.isConfigured }

    fun hasUsableProvider(): Boolean = activeProvider() != null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun fireChanged() {
        listeners.forEach { it() }
    }

    companion object {
        fun getInstance(): AnyTabSettings = service()
    }
}
