package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ConsoleFilter(val label: String) {
    ALL("全部"), ERROR("Error"), WARN("Warning"), INFO("Info")
}

/**
 * Console 页：级别过滤 + 清空 + 时间戳 + 自动滚动到底部。
 */
@Composable
fun ConsoleScreen(viewModel: CdpViewModel, state: UiState) {
    var expr by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ConsoleFilter.ALL) }
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text(
                "请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            // 输入栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = expr,
                    onValueChange = { expr = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Runtime.evaluate 表达式") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (expr.isNotBlank()) {
                            viewModel.evaluateExpression(expr)
                            expr = ""
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Eval") }
            }

            // 过滤 chip + 清空
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConsoleFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.clearConsole() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空")
                }
            }

            val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
            val filtered = remember(state.console, state.exceptions, filter) {
                buildList {
                    if (filter == ConsoleFilter.ALL || filter == ConsoleFilter.ERROR) {
                        state.exceptions.forEach { add(ConsoleRow.Exception(it, timeFmt.format(Date(it.timestamp)))) }
                    }
                    state.console.forEach { e ->
                        val match = when (filter) {
                            ConsoleFilter.ALL -> true
                            ConsoleFilter.ERROR -> e.level == "error"
                            ConsoleFilter.WARN -> e.level == "warning"
                            ConsoleFilter.INFO -> e.level in listOf("log", "info", "debug")
                        }
                        if (match) add(ConsoleRow.Log(e, timeFmt.format(Date(e.timestamp))))
                    }
                }
            }

            // 新条目到达时自动滚动到底部
            LaunchedEffect(filtered.size) {
                if (filtered.isNotEmpty()) {
                    listState.animateScrollToItem(filtered.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(filtered) { row ->
                    when (row) {
                        is ConsoleRow.Exception -> {
                            Text(
                                text = "${row.time} ✗ ${row.entry.text}" +
                                    (row.entry.stackTrace?.let { "\n$it" } ?: ""),
                                color = MaterialTheme.colorScheme.error,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is ConsoleRow.Log -> {
                            val color = when (row.entry.level) {
                                "error" -> MaterialTheme.colorScheme.error
                                "warning" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                text = "${row.time} [${row.entry.level}] ${row.entry.text}",
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class ConsoleRow {
    abstract val time: String
    data class Log(val entry: com.devtools.cdp.data.ConsoleEntry, override val time: String) : ConsoleRow()
    data class Exception(val entry: com.devtools.cdp.data.ExceptionEntry, override val time: String) : ConsoleRow()
}
