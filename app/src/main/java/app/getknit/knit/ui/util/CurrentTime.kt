package app.getknit.knit.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * A [State] holding the current wall-clock time in epoch millis, refreshed every [intervalMs] while
 * in composition. Read it where a relative-time label ("5m", "2h ago") is rendered so the label
 * recomposes as time passes instead of freezing at the value captured on first composition — a plain
 * `System.currentTimeMillis()` call is not a tracked state read, so Compose never revisits it on its
 * own.
 *
 * The ticker coroutine is scoped to composition, so it stops automatically when the caller leaves the
 * tree. Hoist one of these per screen and pass the value down rather than ticking per row.
 */
@Composable
fun rememberCurrentTimeMillis(intervalMs: Long = 30_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
