package app.getknit.knit.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Settings › Block screenshots (`SettingsStore.secureScreens`, ADR 2026-11.j8pf) on this activity's window: `FLAG_SECURE`
 * while it is on, so a screenshot or a recording of the window comes out blank and Recents draws no preview of it.
 * Followed for the activity's whole life, not only while resumed, so switching it in Settings takes effect at once and
 * the flag is already set when Recents snapshots the window on the way out. **Fails closed:** the flag is set here, in
 * `onCreate`, and cleared only by the store's first `false`, because on a cold process that answer comes back from
 * disk after the first frame may have been drawn, and a recording already running would have caught it.
 */
fun ComponentActivity.followSecureScreens(secure: Flow<Boolean>) {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    lifecycleScope.launch {
        secure.distinctUntilChanged().collect { on ->
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
