package com.devtools.cdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class AppTab(val label: String) {
    COOKIES("Cookies"), LOCAL("Local Storage"), SESSION("Session Storage")
}

/**
 * Application 页：对齐 Chrome DevTools Application 面板。
 *
 * 功能：
 *  - Cookies：列出 Network.getCookies 返回的 Cookie，支持新增/编辑/删除/清空
 *  - Local Storage：读写 localStorage（Runtime.evaluate）
 *  - Session Storage：读写 sessionStorage
 *  - 修改 token：在 Cookie 或 Storage 里直接编辑 value 即可改 token
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationScreen(viewModel: CdpViewModel, state: UiState) {
    var tab by remember { mutableStateOf(AppTab.COOKIES) }
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
            AppTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = {
                        tab = t
                        when (t) {
                            AppTab.COOKIES -> viewModel.getCookies()
                            AppTab.LOCAL -> viewModel.getLocalStorage()
                            AppTab.SESSION -> viewModel.getSessionStorage()
                        }
                    },
                    text = { Text(t.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (tab) {
            AppTab.COOKIES -> CookiesTab(state, viewModel)
            AppTab.LOCAL -> StorageTab("localStorage", state.localStorage, viewModel)
            AppTab.SESSION -> StorageTab("sessionStorage", state.sessionStorage, viewModel)
        }
    }
}

@Composable
private fun CookiesTab(state: UiState, viewModel: CdpViewModel) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<CookieInfo?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Cookies (${state.cookies.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.getCookies() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增 Cookie")
            }
            IconButton(onClick = { viewModel.clearCookies() }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空全部",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.cookies, key = { "${it.name}@${it.domain}${it.path}" }) { c ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { editing = c }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.deleteCookie(c.name, c.domain, c.path) },
                                modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "删除",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = {
                                copyText(context, "${c.name}=${c.value}")
                            }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制",
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                        Text("值: ${c.value.take(120)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("domain=${c.domain}  path=${c.path}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (c.httpOnly) Badge("HttpOnly")
                            if (c.secure) Badge("Secure")
                            if (c.sameSite.isNotBlank()) Badge(c.sameSite)
                            Badge("${c.size}B")
                        }
                    }
                }
            }
        }
    }

    editing?.let { c ->
        CookieEditDialog(
            title = "编辑 Cookie",
            initialName = c.name,
            initialValue = c.value,
            initialDomain = c.domain,
            initialPath = c.path,
            onDismiss = { editing = null },
            onConfirm = { n, v, d, p ->
                viewModel.setCookie(n, v, d, p)
                editing = null
            }
        )
    }
    if (adding) {
        CookieEditDialog(
            title = "新增 Cookie",
            initialName = "",
            initialValue = "",
            initialDomain = "",
            initialPath = "/",
            onDismiss = { adding = false },
            onConfirm = { n, v, d, p ->
                viewModel.setCookie(n, v, d, p)
                adding = false
            }
        )
    }
}

@Composable
private fun StorageTab(which: String, items: List<StorageItem>, viewModel: CdpViewModel) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<StorageItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$which (${items.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (which == "localStorage") viewModel.getLocalStorage() else viewModel.getSessionStorage()
            }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增")
            }
            IconButton(onClick = { viewModel.clearStorage(which) }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.key }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.key,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.value.take(150),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { editing = item }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑",
                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { viewModel.removeStorageItem(which, item.key) },
                            modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "删除",
                                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { copyText(context, item.value) },
                            modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制",
                                modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        StorageEditDialog(
            title = "编辑 $which",
            initialKey = item.key,
            initialValue = item.value,
            keyEditable = false,
            onDismiss = { editing = null },
            onConfirm = { k, v ->
                viewModel.setStorageItem(which, k, v)
                editing = null
            }
        )
    }
    if (adding) {
        StorageEditDialog(
            title = "新增 $which",
            initialKey = "",
            initialValue = "",
            keyEditable = true,
            onDismiss = { adding = false },
            onConfirm = { k, v ->
                viewModel.setStorageItem(which, k, v)
                adding = false
            }
        )
    }
}

@Composable
private fun CookieEditDialog(
    title: String,
    initialName: String, initialValue: String,
    initialDomain: String, initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, value: String, domain: String, path: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var value by remember { mutableStateOf(initialValue) }
    var domain by remember { mutableStateOf(initialDomain) }
    var path by remember { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutLabeled("Name", name) { name = it }
                Spacer(Modifier.height(6.dp))
                OutLabeled("Value", value) { value = it }
                Spacer(Modifier.height(6.dp))
                OutLabeled("Domain", domain) { domain = it }
                Spacer(Modifier.height(6.dp))
                OutLabeled("Path", path) { path = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(),
                onClick = { onConfirm(name, value, domain, path) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun StorageEditDialog(
    title: String,
    initialKey: String, initialValue: String,
    keyEditable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (key: String, value: String) -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutLabeled("Key", key) { if (keyEditable) key = it }
                Spacer(Modifier.height(6.dp))
                OutLabeled("Value", value) { value = it }
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank(),
                onClick = { onConfirm(key, value) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun OutLabeled(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun copyText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("app", text))
}
