package com.harveybase.anytab.settings

import com.harveybase.anytab.AnyTabBundle
import com.harveybase.anytab.llm.Preset
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** 可选的采样温度。补全场景越低越稳，所以只给几个低档位 */
private val TEMPERATURES = listOf(0.0, 0.1, 0.2, 0.3, 0.5)

/**
 * 设置页：左侧供应商列表 + 右侧供应商表单，下面是补全行为。
 *
 * 编辑期间改的是已保存配置的副本 [working]；Apply 时才拷回去，Cancel 直接丢弃。
 */
class AnyTabConfigurable : Configurable {
    private val settings = AnyTabSettings.getInstance()

    /** 打开设置页/上次 Apply 时的快照，用来判断有没有改动 */
    private var saved = settings.state.deepCopy()

    /** 编辑中的副本。对象身份保持不变，这样 DSL 的属性绑定一直有效 */
    private val working = settings.state.deepCopy()

    /** 页面上改过、还没写进 PasswordSafe 的 API Key：providerId -> key */
    private val pendingKeys = LinkedHashMap<String, String>()

    /** 增删过供应商（字段改动不靠它，靠 working != saved） */
    private var structureChanged = false

    private val providerListModel = CollectionListModel<ProviderConfig>()
    private val activeProviderModel = DefaultComboBoxModel<ProviderConfig>()
    private var editor: ProviderEditor? = null
    private var root: DialogPanel? = null

    private val providerList = JBList(providerListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = AnyTabBundle["settings.providers.empty"]
        cellRenderer = object : ColoredListCellRenderer<ProviderConfig>() {
            override fun customizeCellRenderer(
                list: JList<out ProviderConfig>,
                value: ProviderConfig,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.name)
                append("  " + value.model, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (!value.enabled) {
                    append("  " + AnyTabBundle["settings.provider.disabledMarker"], SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) rebuildDetail()
        }
    }

    private val activeProviderCombo = ComboBox(activeProviderModel).apply {
        renderer = textRenderer<ProviderConfig> { "${it.name} (${it.model})" }
        addActionListener {
            (activeProviderModel.selectedItem as? ProviderConfig)?.let { working.activeProviderId = it.id }
        }
    }

    /** 右侧表单的容器：选中哪个供应商就放谁的表单 */
    private val detailHost = JPanel(BorderLayout())

    override fun getDisplayName(): String = "AnyTab"

    override fun createComponent(): JComponent {
        val addButton = JButton(AnyTabBundle["settings.action.add"])
        val removeButton = JButton(AnyTabBundle["settings.action.remove"])
        addButton.addActionListener { showAddProviderPopup(addButton) }
        removeButton.addActionListener { removeSelectedProvider() }

        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(addButton)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(removeButton)
        }
        val listPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(providerList).apply { minimumSize = Dimension(JBUI.scale(200), JBUI.scale(180)) }, BorderLayout.CENTER)
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        val panel = panel {
            group(AnyTabBundle["settings.group.providers"]) {
                row {
                    cell(listPanel).resizableColumn().align(Align.FILL)
                    cell(detailHost).resizableColumn().align(Align.FILL)
                }.resizableRow()
            }
            group(AnyTabBundle["settings.group.completion"]) {
                row(AnyTabBundle["settings.activeProvider"]) {
                    cell(activeProviderCombo)
                }
                row {
                    checkBox(AnyTabBundle["settings.enabled"]).bindSelected(working::enabled)
                    checkBox(AnyTabBundle["settings.autoTrigger"]).bindSelected(working::autoTrigger)
                }
                row(AnyTabBundle["settings.debounce"]) {
                    intTextField(0..10_000).bindIntText(working::debounceMs).columns(6)
                    label("ms")
                    label(AnyTabBundle["settings.timeout"])
                    intTextField(1_000..120_000).bindIntText(working::requestTimeoutMs).columns(7)
                    label("ms")
                }
                row(AnyTabBundle["settings.maxTokens"]) {
                    intTextField(1..4096).bindIntText(working::maxTokens).columns(6)
                    label(AnyTabBundle["settings.maxLines"])
                    intTextField(1..200).bindIntText(working::maxLines).columns(4)
                    label(AnyTabBundle["settings.temperature"])
                    comboBox(TEMPERATURES).bindItem({ working.temperature.takeIf { it in TEMPERATURES } }) {
                        working.temperature = it ?: TEMPERATURES[1]
                    }
                }
                row(AnyTabBundle["settings.context"]) {
                    label(AnyTabBundle["settings.prefix"])
                    intTextField(1..2000).bindIntText(working::prefixLines).columns(4)
                    label("lines")
                    intTextField(200..40_000).bindIntText(working::prefixMaxChars).columns(6)
                    label("chars")
                }
                row {
                    label(AnyTabBundle["settings.suffix"])
                    intTextField(1..2000).bindIntText(working::suffixLines).columns(4)
                    label("lines")
                    intTextField(100..20_000).bindIntText(working::suffixMaxChars).columns(6)
                    label("chars")
                }
                row(AnyTabBundle["settings.excluded"]) {
                    textArea()
                        .rows(5)
                        .columns(46)
                        .bindText({ working.excludedFilePatterns }) { working.excludedFilePatterns = it }
                        .align(AlignX.FILL)
                        .comment(AnyTabBundle["settings.excluded.comment"])
                }
            }
        }
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean = structureChanged || working != saved || pendingKeys.isNotEmpty()

    override fun apply() {
        working.providers = providerListModel.items.toMutableList()
        settings.state.copyFrom(working)
        pendingKeys.forEach { (providerId, key) -> ApiKeyStore.putAsync(providerId, key) }
        pendingKeys.clear()
        structureChanged = false
        saved = settings.state.deepCopy()
        settings.fireChanged()
    }

    override fun reset() {
        working.copyFrom(settings.state)
        saved = settings.state.deepCopy()
        pendingKeys.clear()
        structureChanged = false
        providerListModel.replaceAll(working.providers)
        refreshActiveProviderModel()
        selectProvider(working.activeProviderId)
        root?.reset()
    }

    override fun disposeUIResources() {
        editor = null
        detailHost.removeAll()
        root = null
    }

    private fun selectProvider(providerId: String?) {
        val target = providerListModel.items.firstOrNull { it.id == providerId }
            ?: providerListModel.items.firstOrNull()
        providerList.setSelectedValue(target, true)
    }

    private fun rebuildDetail() {
        val provider = providerList.selectedValue
        editor = null
        detailHost.removeAll()
        if (provider != null) {
            editor = ProviderEditor(
                provider,
                pendingKeys,
                onPresetChanged = {
                    rebuildDetail()
                    refreshActiveProviderModel()
                },
                onChanged = { refreshActiveProviderModel() },
            ).also { detailHost.add(it.panel, BorderLayout.CENTER) }
        }
        detailHost.revalidate()
        detailHost.repaint()
    }

    /** “当前供应商”下拉只列启用且填完整的供应商 */
    private fun refreshActiveProviderModel() {
        val usable = providerListModel.items.filter { it.enabled && it.isConfigured }
        activeProviderModel.removeAllElements()
        usable.forEach { activeProviderModel.addElement(it) }
        val current = usable.firstOrNull { it.id == working.activeProviderId } ?: usable.firstOrNull()
        activeProviderModel.selectedItem = current
        working.activeProviderId = current?.id
    }

    private fun showAddProviderPopup(anchor: JButton) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(Preset.entries)
            .setTitle(AnyTabBundle["settings.action.add"])
            .setItemChosenCallback { preset -> addProvider(preset) }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun addProvider(preset: Preset) {
        val provider = ProviderConfig.create(preset)
        // 同名供应商自动加序号
        val names = providerListModel.items.map { it.name }.toSet()
        if (provider.name in names) {
            var index = 2
            while ("${preset.title} $index" in names) index++
            provider.name = "${preset.title} $index"
        }
        providerListModel.add(provider)
        structureChanged = true
        refreshActiveProviderModel()
        providerList.setSelectedValue(provider, true)
    }

    private fun removeSelectedProvider() {
        val index = providerList.selectedIndex
        val removed = providerListModel.items.getOrNull(index) ?: return
        providerListModel.remove(index)
        structureChanged = true
        pendingKeys.remove(removed.id)
        // 这个供应商的 Key 也没必要留着了
        ApiKeyStore.putAsync(removed.id, null)
        if (working.activeProviderId == removed.id) working.activeProviderId = null
        refreshActiveProviderModel()
        if (providerListModel.items.isEmpty()) {
            rebuildDetail()
        } else {
            providerList.selectedIndex = index.coerceAtMost(providerListModel.size - 1)
        }
    }
}
