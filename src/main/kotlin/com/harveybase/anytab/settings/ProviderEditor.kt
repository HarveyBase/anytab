package com.harveybase.anytab.settings

import com.harveybase.anytab.AnyTabBundle
import com.harveybase.anytab.llm.ModelCatalog
import com.harveybase.anytab.llm.Preset
import com.harveybase.anytab.llm.Protocol
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 协议在设置页里的显示名 */
private val Protocol.displayTitle: String
    get() = when (this) {
        Protocol.OPENAI_FIM -> "OpenAI FIM — /completions (prompt + suffix)"
        Protocol.OPENAI_CHAT -> "OpenAI Chat — /chat/completions (infill prompt)"
        Protocol.FIM_TOKENS -> "FIM tokens — /completions (vLLM / Ollama)"
    }

/** 测试失败时的提示文字颜色（亮/暗主题各一） */
private val ERROR_FOREGROUND = JBColor(0xC7_2A2A, 0xFF_6B6B)

/**
 * 设置页右侧的单个供应商表单。
 *
 * 字段通过 DSL 绑定直接写回 [provider]（它是设置页的编辑副本），API Key 例外：
 * 只写进 [pendingKeys]，点 Apply 时才落盘到 PasswordSafe。
 */
class ProviderEditor(
    private val provider: ProviderConfig,
    private val pendingKeys: MutableMap<String, String>,
    /** 换了预设：地址/协议/模型等一整片字段都变了，需要整体重建表单 */
    private val onPresetChanged: () -> Unit,
    /** 供应商名字/启用状态变了：左侧列表和“当前供应商”下拉要刷新 */
    private val onChanged: () -> Unit,
) {
    private val apiKeyField = JPasswordField(24)
    private val statusLabel = JBLabel()
    private lateinit var modelField: JBTextField

    /** PasswordSafe 里实际存的 Key，用来判断用户有没有改过 */
    private var storedKey: String? = null

    val panel: DialogPanel = panel {
        row(AnyTabBundle["settings.provider.name"]) {
            textField().bindText({ provider.name }) {
                provider.name = it
                onChanged()
            }
        }
        row(AnyTabBundle["settings.provider.preset"]) {
            comboBox(Preset.entries, textRenderer<Preset> { it.title })
                .bindItem({ provider.presetEnum }) { chosen ->
                    val preset = chosen ?: return@bindItem
                    if (preset != provider.presetEnum) {
                        provider.preset = preset.name
                        provider.applyPresetDefaults(preset)
                        // 正在 combo 的事件派发里，等它结束再重建表单
                        SwingUtilities.invokeLater { onPresetChanged() }
                    }
                }
        }
        row(AnyTabBundle["settings.provider.baseUrl"]) {
            textField().bindText({ provider.baseUrl }) {
                provider.baseUrl = it
                onChanged()
            }
        }
        row(AnyTabBundle["settings.provider.protocol"]) {
            comboBox(Protocol.entries, textRenderer<Protocol> { it.displayTitle })
                .bindItem({ provider.protocolEnum }) { chosen ->
                    provider.protocol = (chosen ?: Protocol.OPENAI_CHAT).name
                }
        }
        row(AnyTabBundle["settings.provider.model"]) {
            modelField = textField()
                .bindText({ provider.model }) {
                    provider.model = it
                    onChanged()
                }
                .component
            button(AnyTabBundle["settings.action.fetchModels"]) { fetchModels() }
        }
        row(AnyTabBundle["settings.provider.apiKey"]) {
            cell(apiKeyField).align(AlignX.FILL)
        }
        row {
            checkBox(AnyTabBundle["settings.provider.enabled"]).bindSelected({ provider.enabled }) {
                provider.enabled = it
                onChanged()
            }
        }
        row(AnyTabBundle["settings.provider.extraHeaders"]) {
            textArea()
                .rows(3)
                .columns(46)
                .bindText({ provider.extraHeaders }) { provider.extraHeaders = it }
                .align(AlignX.FILL)
        }
        row(AnyTabBundle["settings.provider.extraBody"]) {
            textArea()
                .rows(2)
                .columns(46)
                .bindText({ provider.extraBody }) { provider.extraBody = it }
                .align(AlignX.FILL)
                .comment(AnyTabBundle["settings.provider.extraBody.comment"])
        }
        row(AnyTabBundle["settings.provider.fimTokens"]) {
            textField()
                .bindText({ provider.fimTokens }) { provider.fimTokens = it }
                .comment(AnyTabBundle["settings.provider.fimTokens.comment"])
        }
        row {
            button(AnyTabBundle["settings.action.test"]) { testConnection() }
            cell(statusLabel).align(Align.FILL)
        }
    }

    init {
        apiKeyField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = onKeyChanged()

            override fun removeUpdate(event: DocumentEvent) = onKeyChanged()

            override fun changedUpdate(event: DocumentEvent) = onKeyChanged()
        })

        // 钥匙串是阻塞调用，而且不能在 EDT 上读：先禁用输入框，读到再放开
        apiKeyField.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val stored = ApiKeyStore.get(provider.id)
            ApplicationManager.getApplication().invokeLater {
                storedKey = stored
                apiKeyField.text = pendingKeys[provider.id] ?: stored.orEmpty()
                apiKeyField.isEnabled = true
            }
        }
    }

    private fun onKeyChanged() {
        val value = apiKeyField.password.concatToString()
        if (value == storedKey) {
            pendingKeys.remove(provider.id)
        } else {
            pendingKeys[provider.id] = value
        }
    }

    private fun testConnection() {
        showStatus(AnyTabBundle["settings.testing"], ok = true)
        val snapshot = provider.copy()
        ApplicationManager.getApplication().executeOnPooledThread {
            val apiKey = pendingKeys[provider.id] ?: ApiKeyStore.get(snapshot.id)
            val result = runCatching { ProviderTester.test(snapshot, apiKey) }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { showStatus(AnyTabBundle["settings.test.ok", it], ok = true) },
                    onFailure = { showStatus(AnyTabBundle["settings.test.failed", ProviderTester.describe(it)], ok = false) },
                )
            }
        }
    }

    private fun fetchModels() {
        showStatus(AnyTabBundle["settings.fetchingModels"], ok = true)
        val snapshot = provider.copy()
        ApplicationManager.getApplication().executeOnPooledThread {
            val apiKey = pendingKeys[provider.id] ?: ApiKeyStore.get(snapshot.id)
            val result = runCatching { runBlocking { ModelCatalog.list(snapshot, apiKey) } }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { models ->
                        statusLabel.text = ""
                        if (models.isEmpty()) {
                            showStatus(AnyTabBundle["settings.models.empty"], ok = false)
                        } else {
                            chooseModel(models)
                        }
                    },
                    onFailure = { showStatus(AnyTabBundle["settings.test.failed", ProviderTester.describe(it)], ok = false) },
                )
            }
        }
    }

    private fun chooseModel(models: List<String>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(models)
            .setTitle(AnyTabBundle["settings.provider.model"])
            .setItemChosenCallback { model ->
                provider.model = model
                modelField.text = model
                onChanged()
            }
            .createPopup()
            .showUnderneathOf(modelField)
    }

    private fun showStatus(text: String, ok: Boolean) {
        statusLabel.text = text
        statusLabel.foreground = if (ok) JBUI.CurrentTheme.ContextHelp.FOREGROUND else ERROR_FOREGROUND
    }
}
