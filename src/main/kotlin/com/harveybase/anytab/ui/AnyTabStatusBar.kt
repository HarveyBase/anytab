package com.harveybase.anytab.ui

import com.harveybase.anytab.AnyTabBundle
import com.harveybase.anytab.completion.CompletionEngine
import com.harveybase.anytab.settings.AnyTabProjectState
import com.harveybase.anytab.settings.AnyTabSettings
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** 状态栏部件工厂：每个窗口一个 [AnyTabStatusBarWidget] */
class AnyTabStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "AnyTab"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AnyTabStatusBarWidget(project)

    companion object {
        /** 与 plugin.xml 里 <statusBarWidgetFactory id="..."> 一致 */
        const val ID = "AnyTab"
    }
}

/**
 * 状态栏部件：显示 AnyTab 当前状态，点击弹出菜单。
 * 文本约定：`AnyTab` 正常、`AnyTab …` 请求中、`AnyTab !` 上次请求失败、`AnyTab ✕` 已关闭。
 */
class AnyTabStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    private val listener: () -> Unit = { refresh() }

    override fun ID(): String = AnyTabStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        AnyTabSettings.getInstance().addListener(listener)
        CompletionEngine.getInstance().addListener(listener)
    }

    override fun dispose() {
        // 窗口关闭时服务可能已经在销毁，这里失败无所谓
        runCatching { AnyTabSettings.getInstance().removeListener(listener) }
        runCatching { CompletionEngine.getInstance().removeListener(listener) }
        statusBar = null
    }

    private fun isEnabled(): Boolean =
        AnyTabSettings.getInstance().state.enabled && AnyTabProjectState.getInstance(project).isEnabled()

    /** 配置或请求状态变了：回到 EDT 刷新部件。IDE 正在退出时 invokeLater 会失败，忽略即可 */
    private fun refresh() {
        runCatching {
            ApplicationManager.getApplication().invokeLater { statusBar?.updateWidget(ID()) }
        }
    }

    override fun getText(): String {
        val engine = CompletionEngine.getInstance()
        return when {
            !isEnabled() -> "AnyTab ✕"
            engine.inFlight > 0 -> "AnyTab …"
            engine.lastError != null -> "AnyTab !"
            else -> "AnyTab"
        }
    }

    override fun getTooltipText(): String {
        if (!isEnabled()) return AnyTabBundle["status.disabled"]
        val provider = AnyTabSettings.getInstance().activeProvider()
        val model = provider?.let { "${it.name} · ${it.model}" } ?: AnyTabBundle["status.unconfigured"]
        val error = CompletionEngine.getInstance().lastError
        val failed = error?.let { " " + AnyTabBundle["status.failed", it] }.orEmpty()
        return AnyTabBundle["status.tooltip", model + failed]
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { event ->
        (event.component as? JComponent)?.let { anchor ->
            AnyTabPopup.show(anchor, DataManager.getInstance().getDataContext(anchor))
        }
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
}
