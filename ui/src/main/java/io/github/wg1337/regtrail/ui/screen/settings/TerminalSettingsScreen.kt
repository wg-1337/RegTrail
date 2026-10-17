package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.ui.state.AppSettings
import io.github.wg1337.regtrail.ui.terminal.TerminalMode

/**
 * Terminal configuration.
 *
 * These were never configurable: the mode was hard-coded to Root at startup, the
 * workspace to one constant, and the font size to one `sp` value. Making them
 * settings is most of the answer to "the settings for it must exist" — the
 * terminal screen itself is a live surface, not a settings page, which is why it
 * is no longer a bottom-bar tab.
 */
@Composable
fun TerminalSettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenTerminal: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var workspaceDraft by remember(settings.terminalWorkspacePath) {
        mutableStateOf(settings.terminalWorkspacePath)
    }
    val workspaceValid = AppSettings.isValidWorkspacePath(workspaceDraft)
    val workspaceDirty = workspaceDraft.trim() != settings.terminalWorkspacePath

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            title = "终端",
            subtitle = "默认模式、工作区与字号",
            onBack = onBack,
        )

        SettingsCard(
            title = "默认模式",
            description = "打开终端时使用的身份。只影响下一次连接；当前会话可在终端页顶部随时切换。",
        ) {
            TerminalMode.entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { mode ->
                        FilterChip(
                            selected = settings.terminalDefaultMode == mode,
                            onClick = { onChange { it.copy(terminalDefaultMode = mode) } },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
            Text(
                settings.terminalDefaultMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(
            title = "工作区路径",
            description = "终端会创建该目录，并在「工作区」「宿主上下文」模式下把它作为初始目录。",
        ) {
            OutlinedTextField(
                value = workspaceDraft,
                onValueChange = { workspaceDraft = it },
                label = { Text("绝对路径") },
                singleLine = true,
                isError = !workspaceValid,
                supportingText = {
                    Text(
                        if (workspaceValid) {
                            "必须是绝对路径，且不能包含空白字符"
                        } else {
                            "路径无效：需要以 / 开头，且不能包含空格"
                        },
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onChange { it.copy(terminalWorkspacePath = workspaceDraft.trim()) }
                    },
                    enabled = workspaceValid && workspaceDirty,
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                TextButton(
                    onClick = { workspaceDraft = SharedPaths.BASE_DIR },
                    enabled = workspaceDraft != SharedPaths.BASE_DIR,
                ) { Text("默认路径") }
            }
            Text(
                // Stated because it is a real trap: the module reads its config
                // from a fixed location, so "moving the workspace" must not be
                // read as "moving everything".
                "配置下发路径与模块日志固定为 ${SharedPaths.BASE_DIR}，不随此处改动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(
            title = "字号",
            description = "终端回显与输入框的字号",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.TERMINAL_FONT_SP_CHOICES.forEach { size ->
                    FilterChip(
                        selected = settings.terminalFontSizeSp == size,
                        onClick = { onChange { it.copy(terminalFontSizeSp = size) } },
                        label = { Text("${size}sp") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$ ls -al /data/local/tmp",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = settings.terminalFontSizeSp.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Button(
            onClick = onOpenTerminal,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开终端") }
    }
}
