package com.devtools.cdp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Console 页：Runtime.consoleAPICalled / exceptionThrown 实时流 + 输入框 Runtime.evaluate。
 */
@Composable
fun ConsoleScreen(viewModel: CdpViewModel, state: UiState) {
    var expr by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (!state.cdpConnected) {
            Text("请先在 Targets 页连接一个 page target。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
            return@Column
        }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.exceptions) { e ->
                Text(
                    text = "✗ ${e.text}" + (e.stackTrace?.let { "\n$it" } ?: ""),
                    color = Color(0xFFB00020),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(state.console) { e ->
                val color = when (e.level) {
                    "error" -> Color(0xFFB00020)
                    "warning" -> Color(0xFFB8860B)
                    else -> Color(0xFF1A1A1A)
                }
                Text(
                    text = "[${e.level}] ${e.text}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
