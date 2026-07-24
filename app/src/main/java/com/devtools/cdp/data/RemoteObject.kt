package com.devtools.cdp.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * CDP RemoteObject 的本地表示。
 *
 * Chrome DevTools Protocol 在 Runtime.consoleAPICalled / Runtime.evaluate 的返回里，
 * 每个值都是 RemoteObject：
 *   { type, subtype?, className?, value?, description?, unserializableValue?,
 *     objectId?, preview? }
 * - type: "string"|"number"|"boolean"|"object"|"function"|"undefined"|"symbol"|"bigint"
 * - subtype: "array"|"null"|"node"|"regexp"|"date"|"map"|"set"|"error"|"promise"|...
 * - value: 原始类型直接是值；object/function 无 value（用 description）
 * - description: 对象/函数的描述，如 "Object" / "Array(3)" / "f foo()" / "1 NaN"
 * - preview: 对象/数组的属性预览（console 默认展示这一层，点击才深挖）
 *
 * 来源: https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#type-RemoteObject
 * 来源: https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#type-ObjectPreview
 */
data class RemoteObject(
    val type: String,
    val subtype: String? = null,
    val className: String? = null,
    /** 原始类型的值（已转字符串）；object/function 为 null。 */
    val value: String? = null,
    /** 对象/函数描述，如 "Array(3)" / "f foo()"。 */
    val description: String? = null,
    /** NaN / Infinity / -Infinity / -0 等不可序列化原始值。 */
    val unserializableValue: String? = null,
    /** 对象/数组的属性预览，console 默认展开这一层。 */
    val preview: ObjectPreview? = null
) {
    /**
     * 渲染成 console 单行展示用的文本（不含展开）。
     * - 字符串：原样显示（DevTools 不加引号到 console，方便阅读）
     * - 数字/布尔：value
     * - undefined/null：字面量
     * - 函数：description（如 "ƒ foo()"）
     * - 对象/数组：description（如 "Array(3)"），preview 的属性会在 UI 展开后显示
     */
    fun toDisplayText(): String = when (type) {
        "string" -> value ?: ""
        "number" -> unserializableValue ?: value ?: "0"
        "boolean" -> value ?: "false"
        "undefined" -> "undefined"
        "symbol" -> description ?: "Symbol()"
        "bigint" -> value ?: description ?: "0n"
        "function" -> description ?: "ƒ ()"
        "object" -> when (subtype) {
            "null" -> "null"
            "array" -> description ?: "Array"
            "node" -> description ?: "Node"
            "regexp" -> description ?: "/(?:)/"
            "date" -> description ?: "Date"
            "error" -> description ?: "Error"
            else -> description ?: className ?: "Object"
        }
        else -> description ?: value ?: type
    }

    /** 是否可在 UI 展开（有 preview 且不是 null/node 之外无意义）。 */
    val expandable: Boolean
        get() = type == "object" && subtype != "null" && preview != null && preview.properties.isNotEmpty()

    companion object {
        /** 从 CDP JSON 解析单个 RemoteObject。 */
        fun parse(el: JsonElement?): RemoteObject? {
            val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return RemoteObject(
                type = o.get("type")?.asString ?: "undefined",
                subtype = o.get("subtype")?.takeIf { !it.isJsonNull }?.asString,
                className = o.get("className")?.takeIf { !it.isJsonNull }?.asString,
                // value 可能是字符串/数字/布尔/对象，统一转成字符串
                value = o.get("value")?.let { jsonValueToString(it) },
                description = o.get("description")?.takeIf { !it.isJsonNull }?.asString,
                unserializableValue = o.get("unserializableValue")?.takeIf { !it.isJsonNull }?.asString,
                preview = o.get("preview")?.let { ObjectPreview.parse(it.asJsonObject) }
            )
        }

        /** 把 JsonElement 的 value 字段转成展示字符串。 */
        private fun jsonValueToString(el: JsonElement): String = when {
            el.isJsonPrimitive -> el.asString
            el.isJsonObject -> el.toString()
            el.isJsonArray -> el.toString()
            el.isJsonNull -> "null"
            else -> el.toString()
        }
    }
}

/**
 * CDP ObjectPreview：对象/数组的属性预览。
 * 来源: https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#type-ObjectPreview
 */
data class ObjectPreview(
    val type: String,
    val subtype: String? = null,
    val description: String? = null,
    /** 是否还有更多属性未展示（overflow=true）。 */
    val overflow: Boolean = false,
    val properties: List<PreviewProperty> = emptyList(),
    /** Map/Set 的键值对条目。 */
    val entries: List<PreviewEntry> = emptyList()
) {
    companion object {
        fun parse(o: JsonObject?): ObjectPreview? {
            if (o == null) return null
            val props = o.getAsJsonArray("properties")?.mapNotNull { p ->
                val po = p.asJsonObject ?: return@mapNotNull null
                PreviewProperty(
                    name = po.get("name")?.asString ?: "",
                    type = po.get("type")?.asString ?: "string",
                    value = po.get("value")?.asString ?: "",
                    valuePreview = po.get("valuePreview")?.let { parse(it.asJsonObject) }
                )
            } ?: emptyList()
            val entries = o.getAsJsonArray("entries")?.mapNotNull { e ->
                val eo = e.asJsonObject ?: return@mapNotNull null
                PreviewEntry(
                    key = eo.get("key")?.asString,
                    value = eo.getAsJsonObject("value")?.get("value")?.asString
                        ?: eo.getAsJsonObject("value")?.get("description")?.asString ?: ""
                )
            } ?: emptyList()
            return ObjectPreview(
                type = o.get("type")?.asString ?: "object",
                subtype = o.get("subtype")?.takeIf { !it.isJsonNull }?.asString,
                description = o.get("description")?.takeIf { !it.isJsonNull }?.asString,
                overflow = o.get("overflow")?.asBoolean ?: false,
                properties = props,
                entries = entries
            )
        }
    }
}

/** preview 里的单个属性。 */
data class PreviewProperty(
    val name: String,
    val type: String,
    val value: String,
    /** 该属性值本身又是对象时的嵌套预览。 */
    val valuePreview: ObjectPreview? = null
)

/** Map/Set 条目预览。 */
data class PreviewEntry(
    val key: String?,
    val value: String
)
