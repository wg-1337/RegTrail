package io.github.wg1337.regtrail.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A one-line summary that reveals its body when tapped, collapsed by default.
 *
 * ## Why the whole app needed this
 * An Agent turn can make eight tool calls, each with a JSON argument object and a
 * JSON result. Rendering them as chat bubbles produced screens of `{"ok":true,…}`
 * — the user's actual answer scrolled away above it, and the transcript became
 * something to scroll past rather than read. The fix is not "show less": it is
 * **show the one line that identifies the step, and keep the rest one tap away**.
 *
 * Collapsed is the default on purpose. A collapsed row still answers "what did it
 * do, and did it work" ([isError] tints it, [summary] names the tool and the
 * gist), which is what the user needs while skimming; the full text is for the
 * moment they suspect something.
 *
 * No animation: a disclosure that animates is nice once and annoying when eight
 * of them open at the same time, and it would also need an extra Compose artifact
 * for no functional gain.
 */
@Composable
fun ExpandableRow(
    /** Always visible: the identifying line, e.g. `script_create · ok`. */
    summary: String,
    /** Optional second line, e.g. the truncated arguments. */
    subtitle: String? = null,
    /** The body revealed on tap. When null the row is not expandable at all. */
    body: String? = null,
    /** Tint the summary as a failure and start expanded (a failure is worth reading). */
    isError: Boolean = false,
    /** Render the body in a monospace face (JSON, stack traces, file contents). */
    mono: Boolean = true,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(summary, body) {
        mutableStateOf(initiallyExpanded || (isError && body != null))
    }
    val expandable = body != null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) {
                        Modifier.clickable { expanded = !expanded }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (expandable) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.Filled.KeyboardArrowRight
                        },
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f).padding(start = if (expandable) 0.dp else 2.dp)) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null && subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = if (expanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (expanded && body != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    body,
                    style = if (mono) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = IntrinsicHeightLimit)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/**
 * Cap on how tall an expanded body may grow.
 *
 * An expanded `dump_class` result is thousands of characters; letting it stretch
 * the bubble would push the rest of the conversation out of the scroll view. The
 * cap plus its own scroll keeps one long result from becoming the whole screen.
 */
private val IntrinsicHeightLimit = 320.dp
