package io.github.wg1337.regtrail.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wg1337.regtrail.shared.model.TargetApp

/**
 * One row in the injection-target list.
 *
 * Two independent states are shown because they mean different things:
 *  - **in scope** — the LSPosed framework loads the module into this app at all.
 *  - **overlay** — RegTrail additionally shows its floating window there.
 */
@Composable
fun TargetAppRow(
    app: TargetApp,
    inScope: Boolean,
    /** Ticked for a scope request but not yet submitted. */
    scopePending: Boolean,
    overlayEnabled: Boolean,
    onToggleScope: () -> Unit,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (app.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "系统",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        inScope -> "已在框架作用域内"
                        scopePending -> "已勾选，待申请作用域"
                        else -> "不在框架作用域内"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (inScope) {
                        MaterialTheme.colorScheme.primary
                    } else if (scopePending) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Scope: whether the framework loads the module here at all.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("作用域", style = MaterialTheme.typography.labelSmall)
                Checkbox(
                    checked = inScope || scopePending,
                    onCheckedChange = { if (!inScope) onToggleScope() },
                    enabled = !inScope,
                )
            }

            // Overlay: whether RegTrail additionally shows its window here.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("悬浮窗", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = overlayEnabled, onCheckedChange = { onToggleOverlay() })
            }
        }
    }
}
