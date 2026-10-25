package app.getknit.knit.ui.devview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.DEV_VIEW_WIRED
import org.koin.compose.koinInject

private const val KEY_WEIGHT = 0.42f
private const val VALUE_WEIGHT = 0.58f

/**
 * Whether the developer view shows (ADR 2026-10.s7wg): wired into this build and switched on in Diagnostics. A build
 * without it reads nothing, so the setting a debug install left behind never lights it.
 */
@Composable
fun rememberDevViewOn(): Boolean {
    if (!DEV_VIEW_WIRED) return false
    val on by koinInject<SettingsStore>().debugDevView.collectAsStateWithLifecycle(initialValue = false)
    return on
}

/**
 * The developer view's rows, a key and a value a line in monospace, under each group's title. The text is selectable,
 * so an id can be copied; nothing here copies or exports on its own. Each row is one accessibility node.
 */
@Composable
fun InspectGroups(
    groups: List<InspectGroup>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    keyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.primary,
) {
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groups.forEachIndexed { index, group ->
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = titleColor,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                )
                group.rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(
                            text = row.key,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = keyColor,
                            modifier = Modifier.weight(KEY_WEIGHT),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            modifier = Modifier.weight(VALUE_WEIGHT),
                        )
                    }
                }
            }
        }
    }
}
