package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Network 页：聚合 requestWillBeSent / responseReceived / loadingFinished / loadingFailed。
 */
@Composable
fun NetworkScreen(state: UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
            return@Column
        }
        Text("请求数：${state.network.size}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.network) { req ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when {
                                req.failed -> Color(0xFFB00020)
                                req.status in 200..299 -> Color(0xFF1B6B1B)
                                req.status in 300..399 -> Color(0xFFB8860B)
                                req.status in 400..599 -> Color(0xFFB00020)
                                else -> Color(0xFF555555)
                            }
                            Text("${req.method}  ", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                            Text("${req.status}", color = statusColor,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                            Text("  ${req.type}", style = MaterialTheme.typography.labelSmall)
                            if (req.finished) Text(" ✓", color = Color(0xFF1B6B1B),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Text(req.url, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        if (req.mimeType.isNotBlank()) {
                            Text(req.mimeType, style = MaterialTheme.typography.labelSmall)
                        }
                        if (req.failed) {
                            Text("✗ ${req.errorText ?: "failed"}",
                                color = Color(0xFFB00020),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
