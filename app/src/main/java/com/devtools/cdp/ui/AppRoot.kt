package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class CdpTab(val title: String) {
    TARGETS("Targets"), CONSOLE("Console"), ELEMENTS("Elements"), NETWORK("Network")
}

/**
 * AppRoot：Scaffold + TopBar(Shizuku 状态 + 启动/停止桥接按钮) + TabRow 四页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: CdpViewModel) {
    val state by viewModel.ui.collectAsState()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = CdpTab.values()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CDP Bridge") },
                actions = {
                    // 状态文字
                    Text(
                        text = state.bridgeState.name,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = { viewModel.refreshAbstractTargets() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新枚举")
                    }
                    when (state.bridgeState) {
                        BridgeState.BOUND -> {
                            // 默认启动第一个枚举到的 abstract target
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
            // 状态条
            Text(
                text = state.statusMessage.ifBlank { "等待 Shizuku…" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )

            TabRow(selectedTabIndex = tabIndex) {
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
                CdpTab.NETWORK -> NetworkScreen(state)
            }
        }
    }
}
