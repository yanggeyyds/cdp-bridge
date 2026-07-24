package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser

/**
 * Performance 页：对齐 Chrome DevTools Performance 录制。
 * 用 CDP Profiler.start / Profiler.stop 录制 CPU profile，停止后展示热点函数排行。
 */
@Composable
fun PerformanceScreen(viewModel: CdpViewModel, state: UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.perfRecording) {
                Button(onClick = { viewModel.stopPerfRecording() }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text(" 停止录制")
                }
                Text("录制中…请在页面操作", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                Button(onClick = { viewModel.startPerfRecording() }) { Text("开始录制") }
            }
        }
        state.perfProfile?.let { profile ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    profile,
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        } ?: run {
            Text("点击「开始录制」后在页面操作，再停止查看 CPU 热点函数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Memory 页：对齐 Chrome DevTools Memory。
 * 用 Performance.getMetrics 拉取 V8 内存指标（JSHeapUsedSize / Nodes / DOM 等）。
 */
@Composable
fun MemoryScreen(viewModel: CdpViewModel, state: UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.getMemoryMetrics() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(" 刷新指标")
            }
        }
        LaunchedEffect(state.cdpConnected) {
            if (state.cdpConnected && state.memoryMetrics.isEmpty()) {
                viewModel.getMemoryMetrics()
            }
        }
        if (state.memoryMetrics.isEmpty()) {
            Text("暂无指标，点上方刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )) {
                Column(modifier = Modifier.padding(8.dp)) {
                    state.memoryMetrics.entries.forEach { (name, value) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(name, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMetric(name, value),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** 把指标值按语义格式化（字节数→MB，时间→ms）。 */
private fun formatMetric(name: String, value: Double): String = when {
    name.contains("Size", ignoreCase = true) || name.contains("Bytes", ignoreCase = true) ->
        "%.2f MB".format(value / 1024.0 / 1024.0)
    name.contains("Duration", ignoreCase = true) -> "%.2f ms".format(value)
    else -> value.toInt().toString()
}

/**
 * Security 页：对齐 Chrome DevTools Security 面板。
 * 显示页面协议、是否安全连接、混合内容检测。
 */
@Composable
fun SecurityScreen(viewModel: CdpViewModel, state: UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.getSecurityInfo() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(" 刷新")
            }
        }
        LaunchedEffect(state.cdpConnected) {
            if (state.cdpConnected && state.securityInfo == null) {
                viewModel.getSecurityInfo()
            }
        }
        state.securityInfo?.let { json ->
            val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            if (obj == null) {
                Text("解析失败", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                val isSecure = obj.get("isSecure")?.asBoolean ?: false
                val mixed = obj.get("mixedContent")?.asBoolean ?: false
                SecurityCard(isSecure, mixed, obj)
            }
        } ?: run {
            Text("点上方刷新查看页面安全信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SecurityCard(isSecure: Boolean, mixed: Boolean, obj: com.google.gson.JsonObject) {
    Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSecure && !mixed)
                        MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(if (isSecure) "✓ 安全连接" else "⚠ 不安全连接",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSecure) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(6.dp))
                    secRow("URL", obj.get("href")?.asString)
                    secRow("协议", obj.get("protocol")?.asString)
                    secRow("主机", obj.get("host")?.asString)
                    secRow("Origin", obj.get("origin")?.asString)
                    if (mixed) {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠ 检测到混合内容（页面含 http:// 资源）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
    }
}

@Composable
private fun secRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace)
    }
}
