package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wg1337.regtrail.ui.terminal.TerminalEntry
import io.github.wg1337.regtrail.ui.terminal.TerminalMode
import io.github.wg1337.regtrail.ui.terminal.TerminalUiState

/**
 * The in-app terminal.
 *
 * Renders a scrollback plus a single-line input. The visual language is a real
 * terminal's (monospace, dark surface, coloured streams) because that is what
 * makes long output readable, but the widget set is Compose so it behaves like
 * the rest of the app.
 *
 * The mode selector is prominent rather than buried: running a command as root
 * versus as the target app is the single most consequential choice here.
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onCommand: (String) -> Unit,
    onModeChange: (TerminalMode) -> Unit,
    onClear: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the tail as output arrives.
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.lastIndex)
        }
    }

    fun submit() {
        val command = input
        if (command.isBlank()) return
        input = ""
        onCommand(command)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ---- Header ------------------------------------------------------
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("终端", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.statusLine(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- Mode selector ------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TerminalMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label) },
                        enabled = !state.busy,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                state.mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Scrollback --------------------------------------------------
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = Color(0xFF101014),
        ) {
            if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.connected) "输入命令开始" else "未连接",
                            color = Color(0xFF8A8F98),
                            fontSize = 13.sp,
                        )
                        if (!state.connected) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onReconnect) { Text("连接 Root shell") }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.entries) { entry -> TerminalLine(entry) }
                }
            }
        }

        // ---- Input -------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                placeholder = { Text("输入命令…", fontFamily = FontFamily.Monospace) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                singleLine = true,
                enabled = state.connected && !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { submit() },
                enabled = state.connected && !state.busy && input.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "执行")
            }
        }
    }
}

@Composable
private fun TerminalLine(entry: TerminalEntry) {
    val (color, prefix) = when (entry.kind) {
        TerminalEntry.Kind.Command -> Color(0xFF7DD3FC) to "$ "
        TerminalEntry.Kind.Output -> Color(0xFFD6DCE4) to "  "
        TerminalEntry.Kind.Error -> Color(0xFFFF8A80) to "! "
        TerminalEntry.Kind.System -> Color(0xFFB9A0FF) to "# "
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            prefix,
            color = color.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            entry.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (entry.kind == TerminalEntry.Kind.Command) {
                FontWeight.Medium
            } else {
                FontWeight.Normal
            },
        )
    }
}

/** Header subtitle: identity, cwd, connection state. */
private fun TerminalUiState.statusLine(): String = buildString {
    if (!connected) {
        append("未连接")
        return@buildString
    }
    append(mode.label)
    if (user.isNotEmpty() || uid.isNotEmpty()) {
        append(" · ")
        append(user.ifEmpty { "uid=$uid" })
        if (user.isNotEmpty() && uid.isNotEmpty()) append("($uid)")
    }
    if (!isRoot && mode != TerminalMode.App) append(" · 非 root")
    append("\n")
    append(cwd)
}
