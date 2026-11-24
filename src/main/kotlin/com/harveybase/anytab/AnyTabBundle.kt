package com.harveybase.anytab

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.AnyTabBundle"

/** 界面文案统一走资源包：默认英文，messages/AnyTabBundle_zh.properties 提供中文 */
object AnyTabBundle : DynamicBundle(AnyTabBundle::class.java, BUNDLE) {

    operator fun get(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
