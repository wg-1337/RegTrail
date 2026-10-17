package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.ui.patch.PatchRecordUi
import io.github.wg1337.regtrail.ui.patch.PatchTargetUi
import io.github.wg1337.regtrail.ui.patch.PatchUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「打包」 — persistent injection: turn an installed app into a re-signed copy that
 * carries the RegTrail overlay and needs **no root and no LSPosed**.
 *
 * The screen has three jobs, in order of importance:
 *
 *  1. **Tell the truth about the trade before anything is generated.** Patching
 *     changes the APK's signing certificate, and that has consequences no code can
 *     undo (Play Integrity, self-update, mandatory reinstall). The warning card is
 *     the first thing on the screen and is not collapsible.
 *  2. **Show what is happening.** Patching a 100 MB APK means copying, rewriting,
 *     signing and verifying it; a silent spinner reads as a hang, and users kill
 *     apps mid-write.
 *  3. **Hand back a path.** The result is a file, so the only useful output is its
 *     exact location, selectable so it can be copied into a file manager.
 */
@Composable
fun PatchScreen(
    state: PatchUiState,
    onRefresh: () -> Unit,
    onGenerate: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(state.targets, query) {
        state.targets.asSequence()
            .filter { target ->
                query.isBlank() ||
                    target.label.contains(query, ignoreCase = true) ||
                    target.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("免 Root 打包", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "把已安装的应用复制、注入悬浮窗载荷并重新签名，" +
                            "得到无需 Root、也无需 LSPosed 即可安装的 APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
                }
            }
        }

        // ---- The four non-negotiable warnings ----------------------------
        item { PatchWarningCard() }

        // ---- Live progress ----------------------------------------------
        if (state.busy) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            state.activeLabel?.let { "正在打包：$it" } ?: "正在打包…",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.stage ?: "准备中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "打包在后台线程进行，期间可以切到别的页面；" +
                                "请不要强制停止应用，否则会留下不完整的 APK。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Result / error message -------------------------------------
        state.message?.let { message ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.messageIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    SelectionContainer {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }

        // ---- Already generated APKs --------------------------------------
        if (state.records.isNotEmpty()) {
            item {
                Text("已生成（${state.records.size}）", style = MaterialTheme.typography.titleSmall)
            }
            items(state.records, key = { it.path }) { record ->
                PatchRecordCard(record = record, onDelete = { onDeleteRecord(record.path) })
            }
        }

        // ---- Signing key -------------------------------------------------
        state.signerFingerprint?.let { fingerprint ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("签名指纹", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "同一台设备上的补丁使用同一把密钥，因此新补丁可以覆盖旧补丁安装；" +
                                "但永远无法覆盖官方原版（签名不同，系统会拒绝安装）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Target list -------------------------------------------------
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.targets.isEmpty()) {
            item {
                Text(
                    if (state.loading) "正在读取应用列表…" else "没有可打包的应用",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(filtered, key = { it.packageName }) { target ->
            PatchTargetCard(
                target = target,
                busyPackage = if (state.busy) state.activePackage else null,
                enabled = !state.busy,
                onGenerate = { onGenerate(target.packageName) },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The mandatory warning card.
 *
 * Exactly four points, no more and no less: these are the consequences that make
 * users regret patching *after* they have already uninstalled the original. Each
 * one is inherent to re-signing an APK, not a bug that a later release could fix.
 */
@Composable
private fun PatchWarningCard() {
    val points = listOf(
        "patch 后必然过不了 Play Integrity：判定在服务器端，客户端无法绕过。",
        "失去应用自更新：官方商店与内置更新都会因签名不同而失败。",
        "必须卸载原版才能安装（会丢数据，先备份）：签名不同，系统拒绝覆盖安装。",
        "加固/加壳的 APK 会失败：dex 被保护或校验的应用无法注入，也无法运行。",
    )

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("打包前必须知道", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            points.forEachIndexed { index, point ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(point, style = MaterialTheme.typography.bodySmall)
                }
                if (index != points.lastIndex) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PatchRecordCard(record: PatchRecordUi, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${record.packageName} · ${formatBytes(record.bytes)} · " +
                            "${formatTime(record.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    record.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "载荷 dex：${record.injectedDexEntry}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record.warnings.forEach { warning ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PatchTargetCard(
    target: PatchTargetUi,
    busyPackage: String?,
    enabled: Boolean,
    onGenerate: () -> Unit,
) {
    val patchingThis = busyPackage == target.packageName

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(target.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (patchingThis) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(onClick = onGenerate, enabled = enabled) {
                        Text("生成免 Root 包")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("minSdk ${target.minSdk} · targetSdk ${target.targetSdk}")
                    append(" · ${formatBytes(target.apkBytes)}")
                    if (target.splitCount > 0) append(" · ${target.splitCount} 个 split")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (target.minSdkWillBeRaised) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "补丁会把 minSdkVersion 提升到 28（注入入口需要），" +
                        "因此打包后的应用无法安装在 Android 9 以下。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (target.isSystem) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "系统应用：重新签名后通常无法覆盖安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
