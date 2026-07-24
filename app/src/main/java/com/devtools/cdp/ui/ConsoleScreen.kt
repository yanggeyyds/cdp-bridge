package com.devtools.cdp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.ConsoleEntry
import com.devtools.cdp.data.ExceptionEntry
import com.devtools.cdp.data.ObjectPreview
import com.devtools.cdp.data.PreviewProperty
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 级别过滤：对齐 Chrome DevTools Console 的 Levels 下拉。
 * - ALL：全部
 * - ERROR：error + 未捕获异常
 * - WARN：warning
 * - INFO：log / info
 * - VERBOSE：debug / verbose
 */
private enum class ConsoleFilter(val label: String, val matchLevels: Set<String>) {
    ALL("All", emptySet()),
    ERROR("Errors", setOf("error")),
    WARN("Warnings", setOf("warning")),
    INFO("Info", setOf("log", "info", "startGroup", "endGroup")),
    VERBOSE("Verbose", setOf("debug", "verbose", "trace"))
}

/**
 * Console 页：对齐 Chrome DevTools Console 的核心功能。
 *
 * 功能：
 *  - 级别图标（红✗/黄⚠/蓝ℹ/灰·），按 DevTools 配色
 *  - 对象/数组可展开 preview（点击 ▶ 展开，嵌套属性可继续展开）
 *  - 堆栈追踪可展开（点击 ▶ 显示 callFrames）
 *  - 源码位置 url:line:col 展示
 *  - 文本搜索过滤（匹配 args 文本 / text / url）
 *  - 级别过滤 chip（带每级计数：All/Errors/Warnings/Info/Verbose）
 *  - 智能自动滚动：在底部时跟随新消息，上滑时停止；提供「回到底部」按钮
 *  - 长按消息复制全文
 *  - 时间戳
 *  - 清空
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConsoleScreen(viewModel: CdpViewModel, state: UiState) {
    var expr by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ConsoleFilter.ALL) }
    var search by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 展开/折叠状态：用 stable key（时间戳+内容哈希）记录每条消息
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

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

        // ---------- 输入栏 + 命令历史 ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = expr,
                onValueChange = { expr = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Runtime.evaluate 表达式") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (expr.isNotBlank()) {
                        viewModel.evaluateExpression(expr); expr = ""
                    }
                })
            )
            IconButton(
                onClick = {
                    if (expr.isNotBlank()) {
                        viewModel.evaluateExpression(expr); expr = ""
                    }
                },
                modifier = Modifier.padding(start = 4.dp)
            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "执行") }
        }

        // ---------- 搜索框 ----------
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            placeholder = { Text("过滤消息") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
        )

        // ---------- 级别过滤 chip + 计数 + 清空 ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val counts = remember(state.console, state.exceptions) { countByLevel(state) }
            ConsoleFilter.entries.forEach { f ->
                val cnt = when (f) {
                    ConsoleFilter.ALL -> state.console.size + state.exceptions.size
                    ConsoleFilter.ERROR -> counts["error"] ?: 0
                    ConsoleFilter.WARN -> counts["warning"] ?: 0
                    ConsoleFilter.INFO -> counts["info"] ?: 0
                    ConsoleFilter.VERBOSE -> counts["verbose"] ?: 0
                }
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} ($cnt)", style = MaterialTheme.typography.labelSmall) }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.clearConsole() }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空")
            }
        }

        // ---------- 消息列表 ----------
        val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
        val rows = remember(state.console, state.exceptions, filter, search) {
            buildRows(state, filter, search, timeFmt)
        }

        // 智能滚动：用户在底部时跟随，上滑时停止
        val isAtBottom by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= rows.size - 2
            }
        }
        // 监听新条目：仅当已停在底部时自动滚动
        LaunchedEffect(rows.size) {
            if (rows.isNotEmpty() && isAtBottom) {
                listState.animateScrollToItem(rows.size - 1)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(rows, key = { it.id }) { row ->
                    ConsoleMessageRow(
                        row = row,
                        expanded = expanded,
                        onToggle = { expanded[row.id] = !(expanded[row.id] ?: false) },
                        onCopy = { text -> copyToClipboard(context, text) }
                    )
                }
            }
            // 不在底部时显示「回到底部」悬浮按钮
            if (!isAtBottom && rows.isNotEmpty()) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(rows.size - 1) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** 单条 console 消息行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleMessageRow(
    row: ConsoleRow,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit
) {
    val isExpanded = expanded[row.id] ?: false
    val levelColor = row.levelColor()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    // 有可展开内容时点击切换展开
                    if (row.expandable) onToggle()
                },
                onLongClick = { onCopy(row.copyText()) }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 级别图标
            LevelIcon(row.level, levelColor)
            Spacer(Modifier.width(6.dp))
            // 内容
            Column(modifier = Modifier.weight(1f)) {
                // 主体文本（args 拼接 / text）
                Text(
                    text = row.annotatedContent(),
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                // 展开内容：对象 preview / 堆栈
                if (isExpanded) {
                    row.expandedContent()?.let { Text(it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)) }
                }
                // 来源 url:line
                row.sourceLine()?.let { src ->
                    Text(
                        src,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // 展开箭头
            if (row.expandable) {
                Icon(
                    if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** DevTools 风格级别图标。 */
@Composable
private fun LevelIcon(level: String, tint: Color) {
    when (level) {
        "error" -> Box(
            Modifier.size(14.dp).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center
        ) { Text("✗", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        "warning" -> Icon(Icons.Filled.Warning, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        "info" -> Icon(Icons.Outlined.Info, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        "verbose", "debug" -> Box(
            Modifier.size(8.dp).clip(CircleShape).background(tint)
        )
        else -> Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
    }
}

/** 统一的消息行模型（log + exception 合并）。 */
private sealed class ConsoleRow {
    abstract val id: String
    abstract val level: String
    abstract val time: String

    /** 是否可展开（有对象 preview / 堆栈）。 */
    abstract val expandable: Boolean
    /** 主体内容（带着色）。 */
    abstract fun annotatedContent(): AnnotatedString
    /** 展开后显示的内容（preview / 堆栈）。 */
    abstract fun expandedContent(): String?
    /** 来源行 url:line:col。 */
    abstract fun sourceLine(): String?
    /** 复制用纯文本。 */
    abstract fun copyText(): String

    /** 级别对应颜色。 */
    fun levelColor(): Color = when (level) {
        "error" -> Color(0xFFEA4335)
        "warning" -> Color(0xFFFBBC04)
        "info" -> Color(0xFF4285F4)
        "verbose", "debug" -> Color(0xFF80868B)
        else -> Color(0xFF202124)
    }

    data class Log(
        override val id: String,
        override val time: String,
        val entry: ConsoleEntry
    ) : ConsoleRow() {
        override val level: String get() = entry.level
        override val expandable: Boolean
            get() = entry.args.any { it.expandable } || !entry.stackTrace.isNullOrBlank()

        override fun annotatedContent(): AnnotatedString = buildAnnotatedString {
            append("${time} ")
            // args 拼接：每个 arg 用 toDisplayText，对象用 description
            if (entry.args.isNotEmpty()) {
                entry.args.forEachIndexed { i, arg ->
                    if (i > 0) append(' ')
                    if (arg.type == "object" && arg.subtype != "null") {
                        // 对象/数组：description 着色为可点击蓝
                        pushStyle(SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Medium))
                        append(arg.toDisplayText())
                        pop()
                    } else {
                        append(arg.toDisplayText())
                    }
                }
            } else {
                append(entry.text)
            }
        }

        override fun expandedContent(): String? {
            val sb = StringBuilder()
            // 对象 preview
            entry.args.filter { it.expandable }.forEach { arg ->
                arg.preview?.let { sb.append(renderPreview(it, indent = 0)) }
            }
            // 堆栈
            if (!entry.stackTrace.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(entry.stackTrace)
            }
            return if (sb.isEmpty()) null else sb.toString()
        }

        override fun sourceLine(): String? {
            val url = entry.url ?: return null
            val short = url.substringAfterLast('/').ifBlank { url }
            val line = entry.lineNumber
            val col = entry.columnNumber
            return when {
                line != null && col != null -> "$short:$line:$col"
                line != null -> "$short:$line"
                else -> short
            }
        }

        override fun copyText(): String = buildString {
            if (entry.args.isNotEmpty()) {
                append(entry.args.joinToString(" ") { it.toDisplayText() })
            } else {
                append(entry.text)
            }
            entry.stackTrace?.let { append("\n").append(it) }
        }
    }

    data class Exception(
        override val id: String,
        override val time: String,
        val entry: ExceptionEntry
    ) : ConsoleRow() {
        override val level: String = "error"
        override val expandable: Boolean = !entry.stackTrace.isNullOrBlank()

        override fun annotatedContent(): AnnotatedString = buildAnnotatedString {
            append("$time ✗ ")
            pushStyle(SpanStyle(color = Color(0xFFEA4335), fontWeight = FontWeight.Medium))
            append(entry.text)
            pop()
        }

        override fun expandedContent(): String? = entry.stackTrace

        override fun sourceLine(): String? {
            val url = entry.url ?: return null
            val short = url.substringAfterLast('/').ifBlank { url }
            val line = entry.lineNumber
            return if (line != null) "$short:$line" else short
        }

        override fun copyText(): String =
            if (entry.stackTrace != null) "${entry.text}\n${entry.stackTrace}" else entry.text
    }
}

/** 渲染 ObjectPreview 成可读多行文本（递归嵌套）。 */
private fun renderPreview(p: ObjectPreview, indent: Int): String {
    val pad = "  ".repeat(indent)
    val sb = StringBuilder()
    val desc = p.description ?: "Object"
    sb.append(pad).append(desc)
    if (p.properties.isNotEmpty()) {
        sb.append('\n')
        p.properties.forEachIndexed { i, prop ->
            sb.append(pad).append("  ").append(prop.name).append(": ")
            // 嵌套对象递归
            if (prop.valuePreview != null) {
                sb.append(renderPreview(prop.valuePreview, indent + 1).trimStart())
            } else {
                sb.append(formatPropValue(prop))
            }
            if (i < p.properties.lastIndex) sb.append('\n')
        }
    }
    if (p.overflow) sb.append("\n").append(pad).append("  …(更多属性已省略)")
    if (p.entries.isNotEmpty()) {
        sb.append('\n')
        p.entries.forEachIndexed { i, e ->
            sb.append(pad).append("  ").append(e.key ?: "").append(" => ").append(e.value)
            if (i < p.entries.lastIndex) sb.append('\n')
        }
    }
    return sb.toString()
}

/** preview 属性值着色（数字蓝、字符串绿、布尔紫）。 */
private fun formatPropValue(prop: PreviewProperty): String = when (prop.type) {
    "number" -> prop.value
    "string" -> "\"${prop.value}\""
    "boolean" -> prop.value
    "function" -> "ƒ ${prop.value}"
    "undefined" -> "undefined"
    "object" -> if (prop.value == "null") "null" else prop.value
    else -> prop.value
}

/** 按级别计数。 */
private fun countByLevel(state: UiState): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    state.console.forEach { e -> map[e.level] = (map[e.level] ?: 0) + 1 }
    // 未捕获异常算到 error
    if (state.exceptions.isNotEmpty()) {
        map["error"] = (map["error"] ?: 0) + state.exceptions.size
    }
    return map
}

/** 组装过滤+搜索后的行列表。 */
private fun buildRows(
    state: UiState,
    filter: ConsoleFilter,
    search: String,
    timeFmt: SimpleDateFormat
): List<ConsoleRow> {
    val q = search.trim()
    val rows = ArrayList<ConsoleRow>()
    if (filter == ConsoleFilter.ALL || filter == ConsoleFilter.ERROR) {
        state.exceptions.forEach { e ->
            val time = timeFmt.format(Date(e.timestamp))
            val row = ConsoleRow.Exception(id = "exc-${e.timestamp}", time = time, entry = e)
            if (q.isBlank() || row.copyText().contains(q, ignoreCase = true)) rows.add(row)
        }
    }
    state.console.forEach { e ->
        val match = filter == ConsoleFilter.ALL || e.level in filter.matchLevels
        if (!match) return@forEach
        val time = timeFmt.format(Date(e.timestamp))
        val row = ConsoleRow.Log(id = "log-${e.timestamp}-${e.hashCode()}", time = time, entry = e)
        if (q.isBlank() || row.copyText().contains(q, ignoreCase = true) ||
            (e.url?.contains(q, ignoreCase = true) == true)) {
            rows.add(row)
        }
    }
    // 按时间排序
    return rows.sortedBy { extractTime(it) }
}

private fun extractTime(row: ConsoleRow): Long = when (row) {
    is ConsoleRow.Log -> row.entry.timestamp
    is ConsoleRow.Exception -> row.entry.timestamp
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("console", text))
}
