package com.devtools.cdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devtools.cdp.BuildConfig

private enum class CdpTab(val title: String) {
    TARGETS("Targets"), CONSOLE("Console"), ELEMENTS("Elements"), NETWORK("Network")
}

/**
 * AppRoot：Scaffold + TopBar(状态 dot + 端点 + 版本 + 启动/停止) + 状态条 + TabRow 四页。
 *
 * 连接方法对齐 JD phoneDebug 文档的 chrome://inspect + adb forward 流程，
 * 区别是不依赖电脑：Shizuku UserService 在手机本地做 127.0.0.1:9222 ↔ abstract socket 桥接，
 * 等价于 `adb forward tcp:9222 localabstract:chrome_devtools_remote`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: CdpViewModel) {
    val state by viewModel.ui.collectAsState()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = CdpTab.entries

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                ),
                title = {
                    Column {
                        Text("CDP Bridge", fontWeight = FontWeight.SemiBold)
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 状态指示 dot（红/黄/绿）
                    StatusDot(state.bridgeState, modifier = Modifier.padding(end = 4.dp))
                    IconButton(onClick = { viewModel.refreshAbstractTargets() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新枚举")
                    }
                    when (state.bridgeState) {
                        BridgeState.BOUND -> {
                            val first = state.abstractTargets.firstOrNull()
                            IconButton(
                                enabled = first != null,
                                onClick = { first?.let { viewModel.startBridge(it) } }
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "启动桥接")
                            }
                        }
                        BridgeState.BRIDGE_RUNNING -> {
                            IconButton(onClick = { viewModel.stopBridge() }) {
                                Icon(Icons.Filled.Stop, contentDescription = "停止桥接")
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 状态条：端点 + 消息
            StatusBar(state)

            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(tab.title) }
                    )
                }
            }

            when (tabs[tabIndex]) {
                CdpTab.TARGETS -> TargetsScreen(viewModel, state)
                CdpTab.CONSOLE -> ConsoleScreen(viewModel, state)
                CdpTab.ELEMENTS -> ElementsScreen(viewModel, state)
                CdpTab.NETWORK -> NetworkScreen(viewModel, state)
            }
        }
    }
}

/** 桥接状态 → 颜色/文案。 */
private fun BridgeState.colors(): Pair<Color, String> = when (this) {
    BridgeState.IDLE -> Color(0xFF9AA0A6) to "待机"
    BridgeState.SHIZUKU_NOT_RUNNING -> Color(0xFFEA4335) to "Shizuku 未运行"
    BridgeState.SHIZUKU_TOO_OLD -> Color(0xFFEA4335) to "Shizuku 过旧"
    BridgeState.PERMISSION_DENIED -> Color(0xFFEA4335) to "未授权"
    BridgeState.WAITING_PERMISSION -> Color(0xFFFBBC04) to "等待授权"
    BridgeState.BIND_FAILED -> Color(0xFFEA4335) to "绑定失败"
    BridgeState.BINDER_DEAD -> Color(0xFFEA4335) to "Binder 死亡"
    BridgeState.BOUND -> Color(0xFFFBBC04) to "已绑定"
    BridgeState.BRIDGE_RUNNING -> Color(0xFF34A853) to "桥接中"
}

@Composable
private fun StatusDot(state: BridgeState, modifier: Modifier = Modifier) {
    val (color, label) = state.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 顶部状态条：显示当前连接步骤进度 + 状态消息 + 桥接端点。 */
@Composable
private fun StatusBar(state: UiState) {
    // 推导当前步骤（1..5），对齐连接向导，让顶部状态条也能一眼看出进度
    val (step, total) = when (state.bridgeState) {
        BridgeState.IDLE,
        BridgeState.SHIZUKU_NOT_RUNNING,
        BridgeState.SHIZUKU_TOO_OLD,
        BridgeState.BINDER_DEAD -> 1 to 5
        BridgeState.WAITING_PERMISSION,
        BridgeState.PERMISSION_DENIED,
        BridgeState.BIND_FAILED -> 2 to 5
        BridgeState.BOUND -> if (state.abstractTargets.isEmpty()) 3 to 5 else 4 to 5
        BridgeState.BRIDGE_RUNNING -> 5 to 5
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = state.statusMessage.ifBlank { "等待 Shizuku…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // 步骤进度指示：步骤 1/5 ~ 5/5
            Text(
                "步骤 $step/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 10.dp)
            )
            if (state.bridgeState == BridgeState.BRIDGE_RUNNING) {
                Text(
                    "127.0.0.1:9222",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
