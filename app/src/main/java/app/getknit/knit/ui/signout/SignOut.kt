package app.getknit.knit.ui.signout

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.getknit.knit.mesh.MeshService

/**
 * "Sign out here" (work item #80, ADR 2026-09.ypcc): this phone gives up the identity it shares with another
 * one. The mesh has no multi-device concept, so the only honest exit is the one a restore promised — a
 * move, not a copy — made enforceable from this side: the app closes and everything it holds on this phone
 * goes, and the next open is a fresh install's onboarding with a new identity.
 *
 * The wipe is [ActivityManager.clearApplicationUserData], the platform's own "Clear storage": every private
 * file (the wrapped identity and database keys, the database, the DataStore, the blobs, any restore
 * staging), the runtime permission grants, the notifications and the process itself. Nothing of the old
 * identity can linger for a file list to miss, and `IdentityKeyStore` / `DatabaseKey` mint fresh secrets the
 * next time they are touched, exactly as on first run. The cost is the grants: onboarding asks for the
 * radios again, which is also what makes the front door route there (entry is gated on those grants alone,
 * ADR 2026-09.nzpr). The pre-wipe checklist is `BackupViewModel.confirmRestore`'s: a plain `stopService`
 * (not `MeshService.stop`, which records "mesh off" — moot here, but the same door), then the notifications
 * and conversation shortcuts that name threads the next identity never had.
 */
object SignOut {
    private const val TAG = "SignOut"

    /** Does not return normally: the platform kills the process once the wipe is requested. */
    fun here(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, MeshService::class.java))
        NotificationManagerCompat.from(app).cancelAll()
        runCatching {
            val flags =
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or
                    ShortcutManagerCompat.FLAG_MATCH_PINNED
            val ids = ShortcutManagerCompat.getShortcuts(app, flags).map { it.id }
            ShortcutManagerCompat.removeAllDynamicShortcuts(app)
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(app, ids)
        }
        Log.i(TAG, "signing out: clearing this phone's data")
        val cleared = app.getSystemService(ActivityManager::class.java).clearApplicationUserData()
        if (!cleared) Log.w(TAG, "the platform refused to clear the app's data")
    }
}
