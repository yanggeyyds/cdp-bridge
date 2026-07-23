package com.devtools.cdp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.DomNode

/**
 * Elements 页：DOM.getDocument 递归成树，缩进显示，点击 DOM.highlightNode。
 */
@Composable
fun ElementsScreen(viewModel: CdpViewModel, state: UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
            return@Column
        }
        Button(onClick = { viewModel.refreshDom() }, modifier = Modifier.padding(bottom = 6.dp)) {
            Text("获取 DOM.getDocument")
        }
        val root = state.domRoot
        if (root == null) {
            Text("(未获取 DOM 树，点上方按钮拉取)",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val interactionSource = remember { MutableInteractionSource() }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val flat = flatten(root, depth = 0)
            items(flat) { (depth, node) ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp)) {
                    Text(
                        text = renderNode(node),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { viewModel.highlightNode(node.nodeId) }
                    )
                }
            }
        }
    }
}

/** 递归扁平化为 (depth, node) 列表（限制 500 个避免爆栈）。 */
private fun flatten(node: DomNode, depth: Int, out: MutableList<Pair<Int, DomNode>> = mutableListOf()): MutableList<Pair<Int, DomNode>> {
    if (out.size >= 500) return out
    out.add(depth to node)
    node.children?.forEach { flatten(it, depth + 1, out) }
    return out
}

private fun renderNode(node: DomNode): String {
    return if (node.isElement()) {
        val attrs = node.attributePairs().joinToString(" ") { (k, v) -> "$k=\"$v\"" }
        val prefix = if (attrs.isEmpty()) "" else " $attrs"
        "<${node.localName}$prefix>"
    } else if (node.nodeType == 3) { // text
        "\"${(node.nodeValue ?: "").trim().take(80)}\""
    } else if (node.nodeType == 8) { // comment
        "<!-- ${node.nodeValue?.take(80)} -->"
    } else {
        node.nodeName
    }
}
