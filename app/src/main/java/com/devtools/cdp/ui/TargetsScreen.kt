package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Targets 页：
 *  - 上半：/proc/net/unix 枚举的 abstract socket 名 + 启动桥接按钮；
 *  - 下半：/json/list 的 page target + 连接 CDP 按钮；
 *  - 顶部：/json/version 信息。
 */
@Composable
fun TargetsScreen(viewModel: CdpViewModel, state: UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Version", fontWeight = FontWeight.Bold)
                    val v = state.version
                    if (v == null) {
                        Text("(未获取 /json/version)", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Browser: ${v.browser}", style = MaterialTheme.typography.bodySmall)
                        Text("Protocol: ${v.protocolVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { viewModel.refreshVersion() }) { Text("刷新版本") }
                        OutlinedButton(
                            onClick = { viewModel.refreshHttpTargets() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("刷新 /json/list") }
                    }
                }
            }
        }

        item {
            Text("Abstract sockets (/proc/net/unix)",
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.abstractTargets.isEmpty()) {
            item { Text("(未枚举到 *_devtools_remote，请打开 Chrome/可调试 WebView)",
                style = MaterialTheme.typography.bodySmall) }
        }
        items(state.abstractTargets) { name ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("@$name", style = MaterialTheme.typography.bodyMedium)
                        if (name == state.selectedAbstract && state.bridgeState == BridgeState.BRIDGE_RUNNING) {
                            Text("桥接中 → 127.0.0.1:9222",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        enabled = state.bridgeState == BridgeState.BOUND,
                        onClick = { viewModel.startBridge(name) }
                    ) { Text("启动桥接") }
                }
            }
        }

        item {
            Text("Page targets (/json/list)",
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.httpTargets.isEmpty()) {
            item { Text("(无 page target，请先启动桥接并打开 Chrome 页面)",
                style = MaterialTheme.typography.bodySmall) }
        }
        items(state.httpTargets) { tgt ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tgt.displayTitle(), style = MaterialTheme.typography.bodyMedium)
                        Text("type=${tgt.type} id=${tgt.id}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { viewModel.connectTarget(tgt) }) {
                        Text(if (state.cdpConnected) "重连" else "连接 CDP")
                    }
                }
            }
        }
    }
}
