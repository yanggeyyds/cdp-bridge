package com.devtools.cdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devtools.cdp.data.TargetInfo

/**
 * Targets 页（chrome://inspect 风格）。
 *
 * 四段式（对齐 JD phoneDebug 的 chrome://inspect + adb forward 流程，
 * 区别是用 Shizuku 在手机本地做桥接，无需电脑/数据线）：
 *  0. 连接向导卡：5 步引导（Shizuku→授权→Chrome→桥接→Inspect），并标注等价 adb forward；
 *  1. Bridge 状态卡：显示 /json/version 摘要 + 当前桥接端点；
 *  2. Remote sockets：/proc/net/unix 枚举的 abstract socket，每个带「启动桥接」；
 *  3. Page targets：/json/list 的 page target，每个带「Inspect」按钮（连接 CDP WS）。
 */
@Composable
fun TargetsScreen(viewModel: CdpViewModel, state: UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---- 0. 连接向导卡（参考 JD phoneDebug 流程） ----
        item { ConnectionGuide(state) }

        // ---- 1. Bridge 状态卡 ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "  Bridge",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val v = state.version
                    if (v == null) {
                        Text(
                            "未获取 /json/version — 请先启动桥接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("Browser  ${v.browser ?: "—"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Protocol ${v.protocolVersion ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "等价：adb forward tcp:9222 localabstract:chrome_devtools_remote",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.refreshVersion() }) { Text("版本") }
                        OutlinedButton(onClick = { viewModel.refreshHttpTargets() }) { Text("Targets") }
                    }
                }
            }
        }

        // ---- 2. Remote sockets ----
        item { SectionHeader("Remote sockets", "从 /proc/net/unix 枚举") }
        if (state.abstractTargets.isEmpty()) {
            item {
                HintText("未发现 *_devtools_remote。请打开 Chrome 或可调试 WebView，再点右上角刷新。")
            }
        }
        items(state.abstractTargets, key = { it }) { name ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("@$name", style = MaterialTheme.typography.bodyMedium)
                        if (name == state.selectedAbstract && state.bridgeState == BridgeState.BRIDGE_RUNNING) {
                            Text("→ 127.0.0.1:9222",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Button(
                        enabled = state.bridgeState == BridgeState.BOUND,
                        onClick = { viewModel.startBridge(name) }
                    ) { Text("启动") }
                }
            }
        }

        // ---- 3. Page targets ----
        item { SectionHeader("Page targets", "来自 /json/list") }
        if (state.httpTargets.isEmpty()) {
            item {
                HintText("无 page target。请启动桥接并在 Chrome 打开网页。")
            }
        }
        items(state.httpTargets, key = { it.id ?: it.title ?: it.url ?: java.util.UUID.randomUUID().toString() }) { tgt ->
            TargetCard(tgt, state.cdpConnected) { viewModel.connectTarget(tgt) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp)
    )
}

@Composable
private fun TargetCard(tgt: TargetInfo, connected: Boolean, onInspect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 类型徽章
            TypeBadge(tgt.type ?: "page")
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    tgt.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    tgt.url ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onInspect) {
                Text(if (connected) "重连" else "Inspect")
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val (bg, fg) = when (type) {
        "page" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "background_page" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(type, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/**
 * 连接向导卡：参考 JD phoneDebug 的 chrome://inspect + adb forward 流程，
 * 用 Shizuku 在手机本地完成等价桥接（无需电脑/数据线）。
 *
 * 5 步：
 *  1. 启动 Shizuku 服务（≈ 开启 USB 调试 + 连数据线）
 *  2. 授权本应用（≈ adb 授权弹窗）
 *  3. 在 Chrome 打开要调试的页面
 *  4. 启动桥接 → 127.0.0.1:9222（≈ adb forward tcp:9222 localabstract:chrome_devtools_remote）
 *  5. 点 Inspect 连接 DevTools（≈ chrome://inspect 的 inspect 按钮）
 *
 * 步骤完成状态由 [BridgeState] 推导：当前步骤高亮，已完成步骤打勾。
 */
@Composable
private fun ConnectionGuide(state: UiState) {
    // 根据 bridgeState 推导当前所在步骤（1..5），已完成步骤 < currentStep
    val currentStep = when (state.bridgeState) {
        BridgeState.IDLE,
        BridgeState.SHIZUKU_NOT_RUNNING,
        BridgeState.SHIZUKU_TOO_OLD,
        BridgeState.BINDER_DEAD -> 1
        BridgeState.WAITING_PERMISSION,
        BridgeState.PERMISSION_DENIED,
        BridgeState.BIND_FAILED -> 2
        BridgeState.BOUND -> if (state.abstractTargets.isEmpty()) 3 else 4
        BridgeState.BRIDGE_RUNNING -> 5
    }
    val steps = listOf(
        GuideStep(1, "启动 Shizuku 服务", "在 Shizuku App 内启动服务（≈ 开启 USB 调试 + 连数据线）"),
        GuideStep(2, "授权本应用", "允许本应用使用 Shizuku（≈ adb 授权弹窗）"),
        GuideStep(3, "打开 Chrome 网页", "在要调试的 Chrome / WebView 打开目标页面"),
        GuideStep(4, "启动桥接", "把 abstract socket 桥到 127.0.0.1:9222（≈ adb forward）"),
        GuideStep(5, "点 Inspect", "连接 CDP WebSocket，开始抓 Console / Network / DOM")
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "连接向导 · 无需电脑（等价 chrome://inspect + adb forward）",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(8.dp))
            steps.forEach { s ->
                StepRow(
                    step = s,
                    done = s.index < currentStep,
                    active = s.index == currentStep
                )
            }
        }
    }
}

private data class GuideStep(val index: Int, val title: String, val hint: String)

@Composable
private fun StepRow(step: GuideStep, done: Boolean, active: Boolean) {
    val numberColor = when {
        done -> MaterialTheme.colorScheme.secondary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
    }
    val titleColor = when {
        done || active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(numberColor),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    step.index.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                step.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}
