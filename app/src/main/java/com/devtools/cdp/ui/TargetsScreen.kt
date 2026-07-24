package com.devtools.cdp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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

        // ---- 0b. 诊断卡：桥接已运行但有问题（无 version / WS 连接失败） ----
        // 触发条件：BRIDGE_RUNNING 且（version 为空 或 有 discoveryError 且未连 CDP）
        // 覆盖两种场景：1) /json/version 拿不到；2) version 有但点 Inspect 时 WS 失败
        if (state.bridgeState == BridgeState.BRIDGE_RUNNING &&
            (state.version == null || (state.discoveryError != null && !state.cdpConnected))) {
            item { DiagnosticsCard(state, viewModel) }
        }

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
                            "  桥接状态",
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
                        Text("浏览器  ${v.browser ?: "—"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("协议版本 ${v.protocolVersion ?: "—"}",
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
                        OutlinedButton(onClick = { viewModel.refreshHttpTargets() }) { Text("页面列表") }
                        // 页面控制：重载（Page.reload），对齐 DevTools 的刷新按钮
                        OutlinedButton(
                            onClick = { viewModel.reloadPage() },
                            enabled = state.cdpConnected
                        ) { Text("重载") }
                    }
                    // 便捷操作：页面信息 / 清缓存 / 禁用缓存
                    var cacheDisabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var pageInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = state.cdpConnected,
                            onClick = { viewModel.getPageInfo { pageInfo = it } }
                        ) { Text("页面信息") }
                        OutlinedButton(
                            enabled = state.cdpConnected,
                            onClick = { viewModel.clearBrowserCache() }
                        ) { Text("清缓存") }
                        OutlinedButton(
                            enabled = state.cdpConnected,
                            onClick = {
                                cacheDisabled = !cacheDisabled
                                viewModel.setCacheDisabled(cacheDisabled)
                            }
                        ) { Text(if (cacheDisabled) "启用缓存" else "禁用缓存") }
                    }
                    pageInfo?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(2.dp))
                    }
                }
            }
        }

        // ---- 2. 远程 Socket（显示所属应用）----
        item { SectionHeader("远程调试目标", "从 /proc/net/unix 枚举，已关联所属应用") }
        if (state.abstractTargetsDetailed.isEmpty() && state.abstractTargets.isEmpty()) {
            item {
                HintText("未发现 *_devtools_remote。请打开 Chrome 或可调试 WebView，再点右上角刷新。")
            }
        }
        items(state.abstractTargetsDetailed, key = { it.socketName }) { tgt ->
            RemoteSocketCard(tgt, state, viewModel)
        }

        // ---- 3. 页面目标 ----
        item { SectionHeader("页面目标", "来自 /json/list") }
        if (state.httpTargets.isEmpty()) {
            item {
                HintText("无页面目标。请启动桥接并在浏览器打开网页。")
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

/**
 * 远程 Socket 卡片：显示所属应用名 + 包名 + socket 名，长按复制 socket 名。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteSocketCard(tgt: AbstractTarget, state: UiState, viewModel: CdpViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isActive = tgt.socketName == state.selectedAbstract && state.bridgeState == BridgeState.BRIDGE_RUNNING
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        copyText(context, tgt.socketName)
                    }
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 应用名（大字），没有则显示 socket 名
                Text(tgt.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                // 包名 / 进程名（如果有）
                if (tgt.packageName.isNotBlank() && tgt.packageName != tgt.appLabel) {
                    Text(tgt.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // socket 名（技术细节）
                Text("@${tgt.socketName}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary)
                if (isActive) {
                    Text("→ 127.0.0.1:9222（已桥接）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(
                enabled = state.bridgeState == BridgeState.BOUND,
                onClick = { viewModel.startBridge(tgt.socketName) }
            ) { Text(if (isActive) "重启" else "启动") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetCard(tgt: TargetInfo, connected: Boolean, onInspect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { tgt.url?.let { copyText(context, it) } }
                )
                .padding(12.dp),
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
                Text(if (connected) "重连" else "调试")
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val (bg, fg, label) = when (type) {
        "page" -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "页面")
        "background_page" -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "后台页")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, type)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/**
 * 诊断卡：桥接 TCP 监听已起，但 /json/version 拿不到响应时显示。
 *
 * 综合展示三类信息，帮助用户定位"桥接起来了但没有 targets"：
 *  1. abstract socket 探测结果（probeOk）—— Chrome 是否真的在监听这个 socket；
 *  2. /json/version 的具体失败原因（discoveryError）—— 连接被拒/超时/reset/空响应；
 *  3. 常见根因清单 + 对应操作（打开 Chrome 网页 / 启用 WebView 调试 / 换 socket）。
 *
 * 同时提供「重试探测」「重新枚举」「停止桥接」按钮。
 */
@Composable
private fun DiagnosticsCard(state: UiState, viewModel: CdpViewModel) {
    // 区分两种诊断场景：发现阶段失败（无 version）vs Inspect 阶段 WS 失败（有 version 但连接失败）
    val isWsError = state.version != null && state.discoveryError != null

    // 探测结论（仅在发现阶段有意义）
    val probeLine = when (state.probeOk) {
        null -> "abstract socket 未探测"
        true -> "abstract socket 可连 ✓（Chrome 在监听 @$${state.selectedAbstract ?: "?"}）"
        false -> "abstract socket 连不上 ✗（@${state.selectedAbstract ?: "?"} 可能是残留死条目，或 Chrome 未运行）"
    }
    // 错误详情
    val errLine = state.discoveryError ?: "等待 /json/version 响应…（已启动自动重试）"

    // 根据场景给最可能的建议
    val suggestions = buildList {
        if (isWsError) {
            // WebSocket Inspect 失败的专属建议
            add("保持目标页面前台打开，不要锁屏/切走/刷新 —— target id 失效会导致 401/404")
            add("点下方「重试探测」重新拉最新 target 列表，再点 Inspect")
            add("WebView 的 CDP WS 偶有不稳定，退出 CDP Bridge 重进后重新启动桥接")
            add("若错误含 403/Cleartext：检查 App 是否已更新到 v1.5+（networkSecurityConfig 已放开）")
        } else if (state.probeOk == false) {
            add("Chrome/WebView 进程未运行或已退出 —— 打开官方 Chrome 访问 https://example.com 后回此页")
            add("选错了 socket —— 回到 Remote sockets 区换一个（如 webview_devtools_remote_*）")
        } else {
            add("在官方 Chrome 打开 https://example.com，再点下方「重试探测」")
            add("若是 WebView：目标 App 必须调用 WebView.setWebContentsDebugging(true) 才能被 CDP 发现")
            add("部分定制 ROM 的 Chrome 关闭了远程调试，换用 Chrome Beta / 系统 WebView 测试")
        }
    }

    val title = if (isWsError) "诊断：CDP WebSocket Inspect 失败"
                else "诊断：桥接已起但无 CDP 响应"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(6.dp))
            // 探测行（仅发现阶段失败时显示，WS 阶段已确认 socket 正常）
            if (!isWsError) {
                Text(
                    probeLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            // 错误行
            Text(
                errLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp)
            )
            // 建议
            Spacer(Modifier.height(8.dp))
            Text(
                "可能原因与建议：",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            suggestions.forEachIndexed { i, s ->
                Text(
                    "${i + 1}. $s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
            // 操作按钮
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.refreshVersion() }) { Text("重试探测") }
                OutlinedButton(onClick = { viewModel.refreshAbstractTargetsDetailed() }) { Text("重新枚举") }
                OutlinedButton(onClick = { viewModel.stopBridge() }) { Text("停止桥接") }
            }
        }
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
    // 根据 mode + bridgeState 推导当前所在步骤（1..5），已完成步骤 < currentStep
    val currentStep = if (state.mode == BridgeMode.ROOT) {
        when (state.bridgeState) {
            BridgeState.IDLE,
            BridgeState.ROOT_NO_SHELL,
            BridgeState.BINDER_DEAD,
            BridgeState.BIND_FAILED -> 1
            BridgeState.BOUND -> if (state.abstractTargets.isEmpty()) 2 else 3
            BridgeState.BRIDGE_RUNNING -> 5
            else -> 1
        }
    } else {
        when (state.bridgeState) {
            BridgeState.IDLE,
            BridgeState.SHIZUKU_NOT_RUNNING,
            BridgeState.SHIZUKU_TOO_OLD,
            BridgeState.BINDER_DEAD -> 1
            BridgeState.WAITING_PERMISSION,
            BridgeState.PERMISSION_DENIED,
            BridgeState.BIND_FAILED -> 2
            BridgeState.BOUND -> if (state.abstractTargets.isEmpty()) 3 else 4
            BridgeState.BRIDGE_RUNNING -> 5
            else -> 1
        }
    }
    val steps = if (state.mode == BridgeMode.ROOT) {
        listOf(
            GuideStep(1, "授予 Root 权限", "允许本应用获取 root（首次会弹授权弹窗）"),
            GuideStep(2, "Root 服务就绪", "RootService 已绑定，可启动桥接"),
            GuideStep(3, "打开 Chrome 网页", "在要调试的 Chrome / WebView 打开目标页面"),
            GuideStep(4, "启动桥接", "把 abstract socket 桥到 127.0.0.1:9222（≈ adb forward）"),
            GuideStep(5, "点 Inspect", "连接 CDP WebSocket，开始抓 Console / Network / DOM")
        )
    } else {
        listOf(
            GuideStep(1, "启动 Shizuku 服务", "在 Shizuku App 内启动服务（≈ 开启 USB 调试 + 连数据线）"),
            GuideStep(2, "授权本应用", "允许本应用使用 Shizuku（≈ adb 授权弹窗）"),
            GuideStep(3, "打开 Chrome 网页", "在要调试的 Chrome / WebView 打开目标页面"),
            GuideStep(4, "启动桥接", "把 abstract socket 桥到 127.0.0.1:9222（≈ adb forward）"),
            GuideStep(5, "点 Inspect", "连接 CDP WebSocket，开始抓 Console / Network / DOM")
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (state.mode == BridgeMode.ROOT)
                    "连接向导 · Root 模式（无需电脑，等价 chrome://inspect + adb forward）"
                else
                    "连接向导 · Shizuku 模式（无需电脑/Root，等价 chrome://inspect + adb forward）",
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

/** 复制文本到剪贴板（长按复制用）。 */
private fun copyText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("cdp", text))
}
