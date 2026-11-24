package com.harveybase.anytab.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Gson 的轻量封装：解析失败一律返回 null，调用方按“这一片没有内容”处理 */
internal object Json {
    fun parse(text: String): JsonObject? = try {
        JsonParser.parseString(text).asJsonObject
    } catch (e: Exception) {
        null
    }
}

/** 从 OpenAI 风格响应里安全取值的工具函数：类型不对就返回 null，不抛异常 */

internal fun JsonObject.firstChoice(): JsonObject? =
    get("choices")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.firstOrNull()
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

internal fun JsonObject.objectAt(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.stringAt(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
