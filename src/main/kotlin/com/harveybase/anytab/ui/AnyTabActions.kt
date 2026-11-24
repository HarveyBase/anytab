package com.harveybase.anytab.ui

import com.harveybase.anytab.AnyTabBundle
import com.harveybase.anytab.settings.AnyTabProjectState
import com.harveybase.anytab.settings.AnyTabSettings
import com.harveybase.anytab.settings.ProviderConfig
import com.harveybase.anytab.settings.isConfigured
import com.harveybase.anytab.settings.presetEnum
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Conditions
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import javax.swing.JComponent

/** 开关本项目的补全（项目级开关，和应用级总开关相互独立） */
class ToggleAnyTabAction : ToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { AnyTabProjectState.getInstance(it).isEnabled() } ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { AnyTabProjectState.getInstance(it).setEnabled(state) }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project != null
    }
}

class OpenSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { openSettings(it) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

/** 切换当前使用的供应商：每个可用的供应商一项，选中即切换 */
class SelectProviderAction(private val provider: ProviderConfig) : ToggleAction() {
    init {
        templatePresentation.text = provider.name
        templatePresentation.description = "${provider.presetEnum.title} · ${provider.model}"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        AnyTabSettings.getInstance().activeProvider()?.id == provider.id

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        val settings = AnyTabSettings.getInstance()
        settings.state.activeProviderId = provider.id
        settings.fireChanged()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = provider.enabled && provider.isConfigured
    }
}

/** 点击状态栏部件弹出的菜单：开关、切换供应商、打开设置 */
object AnyTabPopup {
    fun show(anchor: JComponent, dataContext: DataContext) {
        val providers = AnyTabSettings.getInstance().state.providers.filter { it.enabled && it.isConfigured }
        val group = DefaultActionGroup().apply {
            add(ToggleAnyTabAction())
            addSeparator()
            if (providers.isNotEmpty()) {
                add(
                    DefaultActionGroup(AnyTabBundle["popup.model"], true).apply {
                        providers.forEach { add(SelectProviderAction(it)) }
                    },
                )
            }
            addSeparator()
            add(OpenSettingsAction())
        }

        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            AnyTabBundle["popup.title"],
            group,
            dataContext,
            false,
            false,
            false,
            null,
            15,
            Conditions.alwaysTrue(),
        )
        // 在状态栏部件正上方弹出
        val size = popup.content.preferredSize
        popup.show(RelativePoint(anchor, Point(anchor.width / 2 - size.width / 2, -size.height)))
    }
}
