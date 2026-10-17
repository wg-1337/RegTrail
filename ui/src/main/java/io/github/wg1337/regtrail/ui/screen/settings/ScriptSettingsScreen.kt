package io.github.wg1337.regtrail.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.ui.script.ScriptActionUi
import io.github.wg1337.regtrail.ui.script.ScriptDraftUi
import io.github.wg1337.regtrail.ui.script.ScriptEntryUi
import io.github.wg1337.regtrail.ui.script.ScriptStrings
import io.github.wg1337.regtrail.ui.script.ScriptUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 → 脚本：the script library and its editor, on one screen.
 *
 * ## Why one screen instead of two destinations
 * The list and the editor are two views of the same object, and the editor is
 * entered from exactly one place. A separate nav destination would add a stack
 * entry that can get out of sync with `state.draft` (back out of the editor, and
 * the draft would still be set). Here the *platform* owns which view is showing —
 * `draft == null` means the list — so "返回" and the system back gesture cannot
 * disagree with what is on screen.
 *
 * ## What this screen is for
 * Before it existed, a script only lived inside the published `config.json`:
 * there was no way to write one, keep one, or get one back after an edit. The
 * rows below make the three things that were impossible possible, in order:
 * **create** (新建), **keep history** (每个脚本的版本列表 + 恢复), **carry it**
 * (导出/导入 one bundle file).
 */
@Composable
fun ScriptSettingsScreen(
    state: ScriptUiState,
    onRefresh: () -> Unit,
    onEdit: (id: String?) -> Unit,
    onSave: (ScriptDraftUi) -> Unit,
    onCloseEditor: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestore: (id: String, version: Int) -> Unit,
    onExport: () -> Unit,
    onImport: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    if (draft == null) {
        ScriptLibrary(
            state = state,
            onRefresh = onRefresh,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onRestore = onRestore,
            onExport = onExport,
            onImport = onImport,
            onBack = onBack,
            modifier = modifier,
        )
    } else {
        ScriptEditor(
            state = state,
            initial = draft,
            onSave = onSave,
            onClose = onCloseEditor,
            onRestore = onRestore,
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------

@Composable
private fun ScriptLibrary(
    state: ScriptUiState,
    onRefresh: () -> Unit,
    onEdit: (String?) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestore: (id: String, version: Int) -> Unit,
    onExport: () -> Unit,
    onImport: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ScriptEntryUi?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Import goes through the system file picker rather than a path field: the
    // bundle is produced on another device, so it arrives as a document the user
    // picked (Downloads, a chat attachment, a USB stick). Reading it here keeps
    // the platform bridge free of a URI it would have to ask for permission to
    // read anyway.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            importError = "读取所选文件失败（或文件为空）"
            onImport("")
        } else {
            importError = null
            onImport(text)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            title = "脚本",
            subtitle = "每个脚本一个目录、每次保存一个新版本；当前版本会被下发到目标进程",
            onBack = onBack,
            trailing = {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新读取脚本库")
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onEdit(null) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" 新建脚本")
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(" 导出全部")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null)
                Text(" 导入脚本包")
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("刷新列表")
            }
        }

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        importError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        state.exportPath?.let { path ->
            Text(
                "导出路径：$path",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (state.entries.isEmpty()) {
            SettingsCard(
                title = "还没有脚本",
                description = "脚本 = 一串可以重放的动作 + 一个执行时机。" +
                    "最快的做法是在 Agent 里说「把刚才这套改法记成脚本」，它会替你建好；" +
                    "也可以在这里手写。",
            ) {
                Text(
                    "存放位置：应用私有目录 files/scripts/<id>/v0001.json（每个版本一个文件，" +
                        "current 指向当前版本）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.entries.forEach { entry ->
            SettingsCard(
                title = entry.name + if (entry.enabled) "" else "（已停用）",
                description = entry.description.ifBlank { null },
            ) {
                Text(
                    entry.summary + " · " + formatTime(entry.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onEdit(entry.id) }) { Text("编辑") }
                    IconButton(onClick = { onDuplicate(entry.id) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = { onEdit(entry.id) }) {
                        Icon(Icons.Filled.History, contentDescription = "版本历史")
                    }
                    IconButton(onClick = { pendingDelete = entry }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Text(
            "脚本只有「启用」的才会被执行；停用的脚本仍然留在库里，历史也保留。" +
                "版本历史在「编辑」页里：恢复旧版本 = 把它的内容存成一个新版本，中间版本不会丢。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除脚本「${entry.name}」？") },
            text = {
                Text(
                    "会连同 ${entry.versionCount} 个历史版本一起删除，无法恢复。" +
                        "如果只是想让它别运行，关掉「启用」就行。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(entry.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

@Composable
private fun ScriptEditor(
    state: ScriptUiState,
    initial: ScriptDraftUi,
    onSave: (ScriptDraftUi) -> Unit,
    onClose: () -> Unit,
    onRestore: (id: String, version: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The draft is a *local* copy on purpose: pushing every keystroke through the
    // platform would mean a revision bump (and a state re-read) per character.
    // `remember(initial)` re-seeds it when the platform hands back a different
    // draft — after a save or a version restore.
    var draft by remember(initial) { mutableStateOf(initial) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            title = if (draft.isNew) "新建脚本" else "编辑脚本",
            subtitle = if (draft.isNew) {
                "保存时会写入 v0001"
            } else {
                "id ${draft.id} · 保存会追加一个新版本"
            },
            onBack = onClose,
        )

        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        SettingsCard(title = "基本信息") {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("脚本名") },
                placeholder = { Text("登录永远成功") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                label = { Text("说明（这个脚本解决什么问题）") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsSwitchRow(
                title = "启用",
                subtitle = if (draft.enabled) {
                    "会被下发到目标进程，并按下面的时机执行"
                } else {
                    "停用：留在库里但不会执行，历史仍然保留"
                },
                checked = draft.enabled,
                onCheckedChange = { draft = draft.copy(enabled = it) },
            )
        }

        SettingsCard(
            title = "执行时机",
            description = ScriptStrings.triggerHint(draft.trigger),
        ) {
            TriggerChips(
                selected = draft.trigger,
                onSelect = { draft = draft.copy(trigger = it) },
            )

            if (ScriptStrings.usesTarget(draft.trigger)) {
                OutlinedTextField(
                    value = draft.triggerTarget,
                    onValueChange = { draft = draft.copy(triggerTarget = it) },
                    label = { Text("目标类名（子串匹配）") },
                    placeholder = { Text("com.example.app.MainActivity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ScriptStrings.usesMethod(draft.trigger)) {
                OutlinedTextField(
                    value = draft.triggerMethod,
                    onValueChange = { draft = draft.copy(triggerMethod = it) },
                    label = { Text("目标方法（类#方法）") },
                    placeholder = { Text("com.example.app.LoginManager#checkLogin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SettingsCard(
            title = "动作（${draft.actions.size}）",
            description = "按顺序执行。Trace / SetReturn / SetArg 会挂钩子，其余是立即执行。",
        ) {
            draft.actions.forEachIndexed { index, action ->
                ActionEditor(
                    index = index,
                    action = action,
                    onChange = { updated ->
                        draft = draft.copy(
                            actions = draft.actions.toMutableList().also { it[index] = updated },
                        )
                    },
                    onRemove = {
                        draft = draft.copy(
                            actions = draft.actions.filterIndexed { i, _ -> i != index },
                        )
                    },
                )
            }

            OutlinedButton(
                onClick = { draft = draft.copy(actions = draft.actions + ScriptActionUi()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" 添加动作")
            }
        }

        Button(
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (draft.isNew) "创建脚本" else "保存（追加新版本）") }

        if (!draft.isNew && state.versions.isNotEmpty()) {
            SettingsCard(
                title = "版本历史（${state.versions.size}）",
                description = "恢复旧版本 = 把它的内容存成一个新版本，中间版本不会丢。",
            ) {
                state.versions.forEach { version ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "v${version.version} · ${formatTime(version.savedAt)} · " +
                                "${version.actionCount} 个动作",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRestore(draft.id, version.version) }) {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                            Text(" 恢复")
                        }
                    }
                }
            }
        }

        Text(
            "保存后会自动重新下发配置，所以改完不用重启目标应用就能生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TriggerChips(selected: String, onSelect: (String) -> Unit) {
    // Chips wrap onto several lines: seven triggers do not fit one row on a
    // phone, and a horizontally scrolling chip strip hides the one the user
    // needs.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScriptStrings.TRIGGERS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { trigger ->
                    FilterChip(
                        selected = selected == trigger,
                        onClick = { onSelect(trigger) },
                        label = { Text(ScriptStrings.triggerLabel(trigger)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionEditor(
    index: Int,
    action: ScriptActionUi,
    onChange: (ScriptActionUi) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${index + 1} ${action.summary}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除这个动作",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScriptStrings.ACTION_KINDS.forEach { kind ->
                FilterChip(
                    selected = action.kind == kind,
                    onClick = { onChange(action.copy(kind = kind)) },
                    label = { Text(ScriptStrings.kindLabel(kind)) },
                )
            }
        }

        OutlinedTextField(
            value = action.className,
            onValueChange = { onChange(action.copy(className = it)) },
            label = { Text("类名") },
            placeholder = { Text("com.example.app.LoginManager") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = action.memberName,
            onValueChange = { onChange(action.copy(memberName = it)) },
            label = { Text("方法名 / 字段名") },
            placeholder = { Text("checkLogin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = action.signature,
            onValueChange = { onChange(action.copy(signature = it)) },
            label = { Text("参数类型（可空）") },
            placeholder = { Text("(java.lang.String,int)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (action.kind != "Trace" && action.kind != "CallMethod") {
            OutlinedTextField(
                value = action.value,
                onValueChange = { onChange(action.copy(value = it)) },
                label = { Text("值（字面量）") },
                placeholder = { Text("true") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptStrings.VALUE_TYPES.forEach { type ->
                    FilterChip(
                        selected = action.valueType == type,
                        onClick = { onChange(action.copy(valueType = type)) },
                        label = { Text(type) },
                    )
                }
            }
        }
    }
}

/** `10-17 18:04` — short enough for a row, precise enough to tell saves apart. */
private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "时间未知"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
