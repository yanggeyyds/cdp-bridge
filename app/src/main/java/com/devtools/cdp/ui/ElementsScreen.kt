package com.devtools.cdp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.DomNode

/**
 * Elements 页：DOM 树展开/折叠 + 标签着色 + 点击高亮。
 *
 * 用 mutableStateMapOf<Int,Boolean> 记录每个 nodeId 是否展开，
 * 默认只展开根节点，点击 chevron 切换子树可见性。
 */
@Composable
fun ElementsScreen(viewModel: CdpViewModel, state: UiState) {
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text(
                "请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            Button(
                onClick = { viewModel.refreshDom() },
                modifier = Modifier.padding(bottom = 6.dp)
            ) { Text("获取 DOM.getDocument") }

            val root = state.domRoot
            if (root == null) {
                Text(
                    "(未获取 DOM 树，点上方按钮拉取)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 根节点默认展开
                val flat = remember(root, expanded.toMap()) {
                    buildFlatList(root, 0, expanded).apply {
                        if (size > 200) subList(0, 200)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(flat, key = { it.node.nodeId }) { item ->
                        NodeRow(item, expanded, onClick = { viewModel.highlightNode(item.node.nodeId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    item: FlatNode,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    onClick: () -> Unit
) {
    val node = item.node
    val hasChildren = !node.children.isNullOrEmpty()
    val isOpen = expanded[node.nodeId] ?: false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 12).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            Icon(
                imageVector = if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (isOpen) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(16.dp)
                    .clickable { expanded[node.nodeId] = !isOpen }
            )
        } else {
            Spacer(Modifier.size(18.dp))
        }
        Text(
            text = renderNodeAnnotated(node),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

/** 递归构建可见节点列表（只遍历已展开的子树，根默认展开）。 */
private fun buildFlatList(
    node: DomNode,
    depth: Int,
    expanded: Map<Int, Boolean>,
    out: MutableList<FlatNode> = mutableListOf()
): MutableList<FlatNode> {
    out.add(FlatNode(node, depth))
    val isOpen = expanded[node.nodeId] ?: (depth == 0)
    if (isOpen && !node.children.isNullOrEmpty()) {
        node.children!!.forEach { buildFlatList(it, depth + 1, expanded, out) }
    }
    return out
}

private data class FlatNode(val node: DomNode, val depth: Int)

/** 带着色的节点渲染：标签名蓝、属性名橙、文本灰。 */
private fun renderNodeAnnotated(node: DomNode): AnnotatedString {
    val tagColor = SpanStyle(color = Color(0xFF8AB4F8))
    val attrNameColor = SpanStyle(color = Color(0xFFF9AB6B))
    val attrValColor = SpanStyle(color = Color(0xFF81C995))
    val textColor = SpanStyle(color = Color(0xFF9AA0A6))

    return buildAnnotatedString {
        when {
            node.isElement() -> {
                append("<")
                withStyle(tagColor) { append(node.localName) }
                node.attributePairs().forEach { (k, v) ->
                    append(" ")
                    withStyle(attrNameColor) { append(k) }
                    append("=\"")
                    withStyle(attrValColor) { append(v) }
                    append("\"")
                }
                append(">")
            }
            node.nodeType == 3 -> withStyle(textColor) {
                append("\"${(node.nodeValue ?: "").trim().take(80)}\"")
            }
            node.nodeType == 8 -> withStyle(textColor) {
                append("<!-- ${node.nodeValue?.take(80)} -->")
            }
            else -> append(node.nodeName)
        }
    }
}
