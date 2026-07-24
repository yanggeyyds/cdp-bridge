package com.devtools.cdp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class SourcesTab(val label: String) {
    SCRIPTS("Scripts"), SNIPPET("Snippet")
}

/**
 * Sources 页：对齐 Chrome DevTools Sources。
 *
 * 功能：
 *  - Scripts：列出 Debugger.scriptParsed 收到的所有脚本，点击查看源码
 *  - 源码查看：Debugger.getScriptSource 拉取，支持复制
 *  - Snippet：多行 JS 编辑器，Runtime.evaluate 执行（复用 Console 的 evaluate）
 *  - 脚本搜索过滤（按 url）
 *  - 复制源码
 *
 * 注：DevTools 的断点/调试器需要 Debugger.setBreakpoint 等，
 * 本 App 不实现断点调试，只做源码查看 + Snippet 运行。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SourcesScreen(viewModel: CdpViewModel, state: UiState) {
    var tab by remember { mutableStateOf(SourcesTab.SCRIPTS) }
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

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            SourcesTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        val cnt = if (t == SourcesTab.SCRIPTS) " (${state.scripts.size})" else ""
                        Text("${t.label}$cnt", style = MaterialTheme.typography.labelSmall)
                    }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        when (tab) {
            SourcesTab.SCRIPTS -> ScriptsTab(state, viewModel, context)
            SourcesTab.SNIPPET -> SnippetTab(state, viewModel, context)
        }
    }
}

/** Scripts Tab：脚本列表 + 源码查看。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptsTab(state: UiState, viewModel: CdpViewModel, context: Context) {
    var search by remember { mutableStateOf("") }
    var selectedScriptId by remember { mutableStateOf<String?>(null) }

    // 首次进入若未启用 Debugger，自动启用
    androidx.compose.runtime.LaunchedEffect(state.cdpConnected, state.sourcesEnabled) {
        if (state.cdpConnected && !state.sourcesEnabled) {
            viewModel.enableSources()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            placeholder = { Text("过滤脚本 URL") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
        )
        Text(
            "已收集 ${state.scripts.size} 个脚本（Debugger.scriptParsed）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        val filtered = remember(state.scripts, search) {
            if (search.isBlank()) state.scripts
            else state.scripts.filter { it.url.contains(search, ignoreCase = true) }
        }

        if (selectedScriptId != null) {
            // 源码视图
            val src = state.scriptSources[selectedScriptId]
            val script = state.scripts.firstOrNull { it.scriptId == selectedScriptId }
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedScriptId = null }) {
                            Icon(Icons.Filled.Delete, contentDescription = "返回列表",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            script?.url?.substringAfterLast('/') ?: script?.scriptId ?: "未知",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (src != null && src.isNotEmpty()) {
                            IconButton(onClick = { copyToClipboard(context, src) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制源码",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (script?.url?.isNotBlank() == true) {
                        Text(script.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    when {
                        src == null -> {
                            Text("正在拉取源码…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.runtime.LaunchedEffect(selectedScriptId) {
                                viewModel.fetchScriptSource(selectedScriptId!!)
                            }
                        }
                        src.isEmpty() -> Text("(空源码)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(src.take(20000),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.fillMaxWidth().padding(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 脚本列表
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.scriptId }) { s ->
                    ScriptRow(s, onClick = {
                        selectedScriptId = s.scriptId
                        if (state.scriptSources[s.scriptId] == null) {
                            viewModel.fetchScriptSource(s.scriptId)
                        }
                    }, onCopy = { copyToClipboard(context, s.url) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptRow(s: ScriptInfo, onClick: () -> Unit, onCopy: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .combinedClickable(onClick = onClick, onLongClick = onCopy),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.url.substringAfterLast('/').ifBlank { "(inline)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (s.isContentScript) {
                    Text(" content",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (s.url.isNotBlank()) {
                Text(s.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Text("scriptId=${s.scriptId}  ${s.endLine - s.startLine} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Snippet Tab：多行 JS 编辑器 + 执行。 */
@Composable
private fun SnippetTab(state: UiState, viewModel: CdpViewModel, context: Context) {
    var code by remember { mutableStateOf("") }
    val result = remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Snippet",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (code.isNotBlank()) {
                    copyToClipboard(context, code)
                }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制代码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                if (code.isNotBlank()) {
                    viewModel.evaluateExpression(code)
                }
            }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "执行",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            placeholder = { Text("// 在此输入 JS 代码\n// 例如：document.title\n// 例如：\nArray.from(document.querySelectorAll('*')).length") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
        )
        Spacer(Modifier.height(8.dp))
        Text("说明：执行结果会输出到 Console 页（带 → 前缀）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("sources", text))
}
