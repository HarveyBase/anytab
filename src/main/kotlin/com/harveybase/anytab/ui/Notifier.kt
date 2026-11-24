package com.harveybase.anytab.ui

import com.harveybase.anytab.AnyTabBundle
import com.harveybase.anytab.settings.AnyTabConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** 气泡通知。鉴权失败每个供应商只提醒一次，避免打字时反复弹 */
object Notifier {
    /** 与 plugin.xml 里 <notificationGroup id="..."> 一致 */
    const val GROUP_ID = "com.harveybase.anytab"

    private val reportedAuthErrors: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun authError(project: Project?, providerId: String, providerName: String) {
        if (!reportedAuthErrors.add(providerId)) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                AnyTabBundle["notify.auth.title"],
                AnyTabBundle["notify.auth.text", providerName],
                NotificationType.WARNING,
            )
        notification.addAction(NotificationAction.create(AnyTabBundle["action.openSettings"]) { event, _ ->
            event.project?.let { openSettings(it) }
        })
        notification.notify(project)
    }
}

/** 打开 AnyTab 设置页 */
fun openSettings(project: Project) {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, AnyTabConfigurable::class.java)
}
