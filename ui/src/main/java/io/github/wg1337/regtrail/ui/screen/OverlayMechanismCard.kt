package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.OverlayMechanism

/**
 * Lets the user choose how the floating window is realised.
 *
 * This is presented as a safety decision, not a preference, because that is what
 * it is: one option cannot break the device, the other can leave it unbootable
 * with no in-Android way back. The wording below is deliberately plain about the
 * recovery cost.
 */
@Composable
fun OverlayMechanismCard(
    selected: OverlayMechanism,
    acknowledged: Boolean,
    onSelect: (OverlayMechanism, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingSystemOptIn by remember { mutableStateOf(false) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("悬浮窗实现方式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "两种方式的窗口层级不同，风险等级也不同。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // ---- DecorView (default) -------------------------------------
            MechanismOption(
                selected = selected == OverlayMechanism.DecorView,
                onSelect = { onSelect(OverlayMechanism.DecorView, false) },
                title = "内嵌式（推荐）",
                riskLabel = "不会导致无法开机",
                riskColor = MaterialTheme.colorScheme.primary,
                points = listOf(
                    "不需要任何权限，也不需要 system 作用域",
                    "代码只运行在目标应用内，出问题最多是该应用异常，可随时取消作用域",
                    "窗口位于系统栏之内，会被状态栏、导航栏和弹窗遮挡",
                ),
            )

            Spacer(Modifier.height(8.dp))

            // ---- ApplicationOverlay (opt-in, gated) ----------------------
            MechanismOption(
                selected = selected == OverlayMechanism.ApplicationOverlay,
                onSelect = {
                    if (acknowledged) {
                        onSelect(OverlayMechanism.ApplicationOverlay, true)
                    } else {
                        pendingSystemOptIn = true
                    }
                },
                title = "免权限悬浮窗（有风险）",
                riskLabel = "可能导致无法开机",
                riskColor = MaterialTheme.colorScheme.error,
                points = listOf(
                    "窗口浮在系统栏之上，不被弹窗遮挡",
                    "必须把 RegTrail 加入 system 作用域，模块会在每次开机时被加载进 system_server",
                    "需要在 system_server 内 Hook 系统的窗口权限检查",
                    "如果该 Hook 在开机阶段出错，设备会卡在开机画面 —— 此时无法在系统内关闭模块",
                    "恢复手段：Magisk 安全模式、LSPosed 管理器的救援入口，或清除数据",
                ),
            )

            if (selected == OverlayMechanism.ApplicationOverlay && acknowledged) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已启用。若设备无法开机：长按音量键进入 Magisk 安全模式，" +
                        "或删除 /data/adb/modules/zygisk_lsposed 后重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (pendingSystemOptIn) {
        AlertDialog(
            onDismissRequest = { pendingSystemOptIn = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("确认启用 system_server Hook？") },
            text = {
                Column {
                    Text(
                        "这个选项会在 system_server 进程内 Hook 系统的窗口权限检查，" +
                            "用于让悬浮窗不再需要「显示在其他应用上层」权限。",
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "风险：system_server 属于开机必经路径。如果该 Hook 在开机阶段抛异常，" +
                            "设备会卡在开机画面，而那时你无法打开 RegTrail 关掉它。",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("出问题时的恢复方式：")
                    Text("· 长按音量下键开机进入 Magisk 安全模式，模块会被禁用")
                    Text("· 或在 recovery/ADB 下删除 /data/adb/modules/zygisk_lsposed 目录后重启")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "如果你不确定，选「内嵌式」就好 —— 它同样不需要权限，只是窗口层级低一些。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSystemOptIn = false
                        onSelect(OverlayMechanism.ApplicationOverlay, true)
                    },
                ) { Text("我已了解风险，启用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSystemOptIn = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MechanismOption(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    riskLabel: String,
    riskColor: Color,
    points: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = riskColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        riskLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                    )
                }
                Spacer(Modifier.height(4.dp))
                points.forEach { point ->
                    Text(
                        "· $point",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
