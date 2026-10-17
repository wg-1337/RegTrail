package io.github.wg1337.regtrail.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the settings tree.
 *
 * They exist so the hub and the sub-pages look like one thing: the previous
 * screens had each invented their own card/row/toggle styling, which is a large
 * part of why the app read as scattered. Everything here is Material 3 list
 * vocabulary — a labelled section, rows that look tappable only when they are,
 * and switches that keep their supporting text.
 */

/**
 * One labelled section of settings.
 *
 * The label sits outside the card (Material 3 list-section convention) so a
 * group boundary is visible even while scrolling past it.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

/**
 * A row that navigates to a sub-page.
 *
 * The chevron is the only affordance that says "this goes somewhere"; a row
 * without one is a switch or plain information, which is a distinction the old
 * screens did not make.
 *
 * @param dividerAbove draws the hairline that separates two rows in a group.
 *   Callers set it on every row but the first — explicit here rather than
 *   magic, because the groups are written as plain composable calls.
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
    dividerAbove: Boolean = false,
    danger: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp))
        ListItem(
            headlineContent = {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            supportingContent = {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = leading ?: icon?.let { vector ->
                {
                    Icon(
                        vector,
                        contentDescription = null,
                        tint = if (danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            trailingContent = {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        )
    }
}

/**
 * A row whose whole surface toggles a boolean.
 *
 * The supporting text is not optional: every switch in this app changes
 * behaviour that is expensive to discover by accident (a hidden overlay, a hook
 * that never fires), so the consequence is spelled out next to the control.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    dividerAbove: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            // The switch is driven by the row's clickable, so its own callback is
            // null: two overlapping handlers would toggle twice per tap.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * Header used by every sub-page.
 *
 * A sub-page has no bottom-bar tab of its own (the owning tab stays
 * highlighted), so it needs an in-content way back; relying on the system back
 * gesture alone made the old 外观 screen feel like a dead end.
 */
@Composable
internal fun SubPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

/** A titled card used inside sub-pages for a single concern. */
@Composable
internal fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/**
 * A solid colour dot, used as the leading visual of every colour row.
 *
 * The same shape is used for the current value and for the preset palette, so
 * "this is a colour you can change" is recognisable before reading the label —
 * which is the whole point of surfacing the picker on the settings hub.
 */
@Composable
internal fun ColorDot(
    color: Color,
    selected: Boolean = false,
    size: Dp = 24.dp,
) {
    Surface(
        modifier = Modifier.size(size),
        color = color,
        shape = CircleShape,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {}
}
