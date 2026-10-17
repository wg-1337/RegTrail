package io.github.wg1337.regtrail.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.CheckStatus
import io.github.wg1337.regtrail.shared.model.SelfCheckReport
import io.github.wg1337.regtrail.shared.model.ZygiskState

@Composable
fun SelfCheckScreen(
    report: SelfCheckReport?,
    running: Boolean,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("环境自检", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "确认 RegTrail 运行所需的 Root 与 LSPosed 条件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        if (report == null) {
            Text(
                if (running) "正在检测…" else "尚未检测",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("设备", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("厂商 / 型号", "${report.device.manufacturer} ${report.device.model}")
                    KeyValue(
                        "Android",
                        "${report.device.androidRelease} (API ${report.device.sdkInt})",
                    )
                    KeyValue("主 ABI", report.device.primaryAbi)
                    KeyValue("支持 ABI", report.device.supportedAbis.joinToString(", "))
                    KeyValue("应用包名", report.device.appPackageName)
                    KeyValue("应用版本", report.device.appVersionName)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Root", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    KeyValue(
                        "状态",
                        if (report.root.granted) "已授权" else "未授权",
                        if (report.root.granted) CheckStatus.Ok else CheckStatus.Failed,
                    )
                    KeyValue("管理器", report.root.manager.name)
                    report.root.versionString?.let { KeyValue("版本", it) }
                    report.root.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("LSPosed", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    KeyValue(
                        "已安装",
                        if (report.lsposed.installed) "是" else "否",
                        if (report.lsposed.installed) CheckStatus.Ok else CheckStatus.Failed,
                    )
                    report.lsposed.versionName?.let { KeyValue("版本", it) }
                    report.lsposed.versionCode?.let { KeyValue("versionCode", it) }
                    KeyValue(
                        "Zygisk",
                        when (report.lsposed.zygiskState) {
                            ZygiskState.Enabled -> "已启用"
                            ZygiskState.Disabled -> "已禁用"
                            ZygiskState.Unknown -> "无法判定"
                        },
                        when (report.lsposed.zygiskState) {
                            ZygiskState.Enabled -> CheckStatus.Ok
                            ZygiskState.Disabled -> CheckStatus.Failed
                            ZygiskState.Unknown -> CheckStatus.Info
                        },
                    )
                    KeyValue(
                        "ABI 支持",
                        if (report.lsposed.abiSupported) "匹配" else "不匹配",
                        if (report.lsposed.abiSupported) CheckStatus.Ok else CheckStatus.Warning,
                    )
                    report.lsposed.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (report.lsposed.notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        report.lsposed.notes.forEach { note ->
                            Text(
                                "· $note",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onRerun,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "检测中…" else "重新检测")
        }

        OutlinedButton(
            onClick = onRerun,
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("导出检测报告（P0 未实现）")
        }
    }
}
