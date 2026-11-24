package com.harveybase.anytab.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger

/**
 * API Key 存进 IDE 的 PasswordSafe（macOS 上就是钥匙串），不落明文到配置文件。
 * PasswordSafe 是阻塞调用且禁止在 EDT 上使用：读在后台协程里做，写用 [putAsync]。
 */
object ApiKeyStore {
    private val LOG = thisLogger()

    private fun attributes(providerId: String): CredentialAttributes = CredentialAttributes("AnyTab", providerId)

    /** 读取 API Key；没存过或钥匙串拒绝访问时返回 null。只能在后台线程、且不持读锁时调用 */
    fun get(providerId: String): String? = try {
        PasswordSafe.instance.getPassword(attributes(providerId))
    } catch (e: Exception) {
        LOG.warn("AnyTab: failed to read API key from Password Safe", e)
        null
    }

    /** 写入 API Key；传空串或 null 表示删除。只能在后台线程、且不持读锁时调用 */
    fun put(providerId: String, apiKey: String?) {
        val password = apiKey?.trim().orEmpty()
        try {
            PasswordSafe.instance.setPassword(attributes(providerId), password.ifEmpty { null })
        } catch (e: Exception) {
            LOG.warn("AnyTab: failed to save API key to Password Safe", e)
        }
    }

    /** 在后台线程写入，供设置页的 apply 调用 */
    fun putAsync(providerId: String, apiKey: String?) {
        ApplicationManager.getApplication().executeOnPooledThread { put(providerId, apiKey) }
    }
}
