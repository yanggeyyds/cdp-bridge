package com.devtools.cdp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.DomNode

/** 选中节点的编辑动作。 */
private sealed class EditAction {
    data class EditAttr(val nodeId: Int, val name: String, val value: String) : EditAction()
    data class AddAttr(val nodeId: Int) : EditAction()
    data class EditText(val nodeId: Int, val value: String) : EditAction()
    data class EditHTML(val nodeId: Int, val html: String) : EditAction()
    data class DeleteNode(val nodeId: Int) : EditAction()
}

/**
 * Elements 页：对齐 Chrome DevTools Elements。
 *
 * 功能：
 *  - DOM 树展开/折叠 + 标签着色 + 点击高亮
 *  - 选中节点后底部显示属性编辑栏
 *  - 属性：双击编辑值、删除、新增
 *  - 文本节点：双击编辑
 *  - HTML：编辑整个 outerHTML
 *  - 删除节点
 *  - 复制 outerHTML
 *  - 长按节点：弹出编辑菜单
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ElementsScreen(viewModel: CdpViewModel, state: UiState) {
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    var selectedNodeId by remember { mutableStateOf<Int?>(null) }
    var editAction by remember { mutableStateOf<EditAction?>(null) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        if (!state.cdpConnected) {
            Text(
                "请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
            return@Column
        }

        // 工具栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { viewModel.refreshDom() }) { Text("刷新 DOM") }
            Spacer(Modifier.width(6.dp))
            val sel = selectedNodeId
            if (sel != null) {
                Text("已选 #$sel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val node = findNode(state.domRoot, sel)
                    if (node != null) editAction = EditAction.EditHTML(sel, renderNodePlain(node))
                }) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑 HTML",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    val node = findNode(state.domRoot, sel)
                    if (node != null) copyToClipboard(context, renderNodePlain(node))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制 HTML",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editAction = EditAction.DeleteNode(sel) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除节点",
                        tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.refreshDom() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        val root = state.domRoot
        if (root == null) {
            Text("(未获取 DOM 树，点上方「刷新 DOM」)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val flat = remember(root, expanded.toMap(), selectedNodeId) {
                buildFlatList(root, 0, expanded).apply {
                    if (size > 300) subList(0, 300)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(flat, key = { it.node.nodeId }) { item ->
                    NodeRow(
                        item = item,
                        expanded = expanded,
                        isSelected = selectedNodeId == item.node.nodeId,
                        onClick = {
                            selectedNodeId = item.node.nodeId
                            viewModel.highlightNode(item.node.nodeId)
                        },
                        onDoubleClick = {
                            val n = item.node
                            when {
                                n.isElement() -> editAction = EditAction.EditHTML(
                                    n.nodeId, renderNodePlain(n)
                                )
                                n.nodeType == 3 -> editAction = EditAction.EditText(
                                    n.nodeId, n.nodeValue ?: ""
                                )
                                n.nodeType == 8 -> editAction = EditAction.EditText(
                                    n.nodeId, n.nodeValue ?: ""
                                )
                            }
                        },
                        onLongClick = {
                            selectedNodeId = item.node.nodeId
                            val n = item.node
                            if (n.isElement()) {
                                editAction = EditAction.AddAttr(n.nodeId)
                            }
                        }
                    )
                }
            }

            // 选中元素的属性编辑区
            selectedNodeId?.let { sid ->
                val node = findNode(root, sid)
                if (node != null && node.isElement()) {
                    AttributesEditor(
                        node = node,
                        onEditAttr = { name, value ->
                            editAction = EditAction.EditAttr(sid, name, value)
                        },
                        onAddAttr = { editAction = EditAction.AddAttr(sid) },
                        onRemoveAttr = { name -> viewModel.removeAttribute(sid, name) }
                    )
                    // Computed Styles 面板：选中节点自动拉取计算样式
                    LaunchedEffect(sid) { viewModel.getComputedStyles(sid) }
                    StylesCard(state, viewModel, sid)
                }
            }
        }
    }

    // 编辑对话框
    editAction?.let { action ->
        EditDialog(
            action = action,
            onDismiss = { editAction = null },
            onConfirm = { result ->
                when (action) {
                    is EditAction.EditAttr -> viewModel.setAttribute(
                        action.nodeId, result.first, result.second
                    )
                    is EditAction.AddAttr -> viewModel.setAttribute(
                        action.nodeId, result.first, result.second
                    )
                    is EditAction.EditText -> viewModel.setNodeValue(action.nodeId, result.second)
                    is EditAction.EditHTML -> viewModel.setOuterHTML(action.nodeId, result.second)
                    is EditAction.DeleteNode -> {
                        // 删除节点：用 setOuterHTML 替换为空
                        viewModel.setInnerHTML(action.nodeId, "")
                    }
                }
                editAction = null
            }
        )
    }
}

/** 节点行：单击选中+高亮，双击编辑，长按添加属性。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    item: FlatNode,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val node = item.node
    val hasChildren = !node.children.isNullOrEmpty()
    val isOpen = expanded[node.nodeId] ?: false
    var lastClick by remember { mutableStateOf(0L) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent)
            .combinedClickable(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClick < 280) onDoubleClick()
                    else onClick()
                    lastClick = now
                },
                onLongClick = onLongClick
            )
            .padding(start = (item.depth * 12).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            Icon(
                imageVector = if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
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
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 属性编辑区：列出所有属性，每行有编辑/删除按钮。 */
@Composable
private fun AttributesEditor(
    node: DomNode,
    onEditAttr: (name: String, value: String) -> Unit,
    onAddAttr: () -> Unit,
    onRemoveAttr: (name: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Attributes (${node.attributePairs().size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onAddAttr) {
                    Icon(Icons.Filled.Add, contentDescription = "新增属性",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            node.attributePairs().forEach { (name, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$name=\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFF9AB6B))
                    Text("$value\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF81C995),
                        maxLines = 1,
                        modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onEditAttr(name, value) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp))
                    }
                    IconButton(
                        onClick = { onRemoveAttr(name) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** 编辑对话框：属性/文本/HTML。 */
@Composable
private fun EditDialog(
    action: EditAction,
    onDismiss: () -> Unit,
    onConfirm: (Pair<String, String>) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val title: String
    val valueOnly: Boolean
    when (action) {
        is EditAction.EditAttr -> {
            key = action.name; value = action.value
            title = "编辑属性"; valueOnly = false
        }
        is EditAction.AddAttr -> { title = "新增属性"; valueOnly = false }
        is EditAction.EditText -> { value = action.value; title = "编辑文本"; valueOnly = true }
        is EditAction.EditHTML -> { value = action.html; title = "编辑 HTML"; valueOnly = true }
        is EditAction.DeleteNode -> {
            // 删除确认
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("删除节点") },
                text = { Text("确定删除节点 #${action.nodeId}？此操作不可在 App 内撤销，但刷新页面会恢复。") },
                confirmButton = {
                    TextButton(onClick = { onConfirm("" to "") }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
            return
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (!valueOnly) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("属性名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (valueOnly) "内容" else "属性值") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (key.isNotBlank() || valueOnly) onConfirm(key to value)
                    })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (valueOnly) onConfirm("" to value)
                else if (key.isNotBlank()) onConfirm(key to value)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 递归构建可见节点列表。 */
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

/** 在树里按 nodeId 查找节点。 */
private fun findNode(root: DomNode?, nodeId: Int): DomNode? {
    if (root == null) return null
    if (root.nodeId == nodeId) return root
    root.children?.forEach { c ->
        findNode(c, nodeId)?.let { return it }
    }
    return null
}

/** 带着色的节点渲染：标签蓝、属性名橙、属性值绿、文本灰。 */
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
                    withStyle(attrValColor) { append(v.take(60)) }
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

/** 纯文本节点渲染（用于编辑框初值/复制）。 */
private fun renderNodePlain(node: DomNode): String = buildString {
    when {
        node.isElement() -> {
            append("<${node.localName}")
            node.attributePairs().forEach { (k, v) -> append(" $k=\"$v\"") }
            append(">")
            // 包含子节点的简短预览
            if (!node.children.isNullOrEmpty()) {
                append("…")
            }
            append("</${node.localName}>")
        }
        node.nodeType == 3 -> append(node.nodeValue ?: "")
        node.nodeType == 8 -> append("<!-- ${node.nodeValue ?: ""} -->")
        else -> append(node.nodeName)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("elements", text))
}

/**
 * Computed Styles 面板：对齐 Chrome DevTools Elements 的 Computed 标签。
 * 展示选中节点的计算样式（CSS.getComputedStyleForNode），支持搜索过滤。
 */
@Composable
private fun StylesCard(state: UiState, viewModel: CdpViewModel, nodeId: Int) {
    var search by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Computed Styles (${state.computedStyles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.getComputedStyles(nodeId) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新样式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                placeholder = { Text("过滤样式属性") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            val filtered = remember(state.computedStyles, search) {
                if (search.isBlank()) state.computedStyles
                else state.computedStyles.filter { it.name.contains(search, ignoreCase = true) }
            }
            filtered.take(80).forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(s.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFF9AB6B),
                        modifier = Modifier.weight(1f),
                        maxLines = 1)
                    Text(s.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF81C995),
                        maxLines = 1)
                }
            }
        }
    }
}
