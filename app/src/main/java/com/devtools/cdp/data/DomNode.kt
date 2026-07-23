package com.devtools.cdp.data

import com.google.gson.annotations.SerializedName

/**
 * DOM 树节点（DOM.getDocument 返回的 node 递归结构）。
 *
 * 研究：CDP DOM 域 node 字段含 nodeId/nodeName/nodeValue/attributes/children 等。
 * 来源: https://chromedevtools.github.io/devtools-protocol/tot/DOM/
 */
data class DomNode(
    @SerializedName("nodeId") val nodeId: Int = 0,
    @SerializedName("parentId") val parentId: Int? = null,
    @SerializedName("backendNodeId") val backendNodeId: Int? = null,
    @SerializedName("nodeType") val nodeType: Int = 0,
    @SerializedName("nodeName") val nodeName: String = "",
    @SerializedName("localName") val localName: String = "",
    @SerializedName("nodeValue") val nodeValue: String? = null,
    /** 原始 attributes 是扁平数组 [name1, value1, name2, value2, ...]。 */
    @SerializedName("attributes") val attributes: List<String>? = null,
    @SerializedName("children") val children: List<DomNode>? = null,
    @SerializedName("childNodeCount") val childNodeCount: Int? = null
) {
    /** attributes 扁平数组转成有序 name->value 对，便于 UI 显示。 */
    fun attributePairs(): List<Pair<String, String>> {
        val raw = attributes ?: return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i + 1 < raw.size) {
            pairs.add(raw[i] to raw[i + 1])
            i += 2
        }
        return pairs
    }

    /** 是否元素节点（nodeType==1）。 */
    fun isElement(): Boolean = nodeType == 1
}
