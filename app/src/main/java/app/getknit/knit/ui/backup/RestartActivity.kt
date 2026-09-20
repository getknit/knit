package app.getknit.knit.ui.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import app.getknit.knit.MainActivity
import kotlin.system.exitProcess

/**
 * The trampoline that turns a staged restore into a running one. It lives in its own process
 * (`android:process=":restart"`, see the manifest), so it survives the main process going away: it kills
 * that process by pid, launches [MainActivity] in a fresh task, and exits. The next main process runs
 * `KnitApplication.onCreate`, where [app.getknit.knit.data.backup.RestoreApplier] moves the staged
 * files into place before Koin builds anything over them.
 *
 * `KnitApplication.onCreate` returns at once in this process (it checks the process name), so no Koin
 * graph, DataStore or database is ever opened from here. Nothing here reads the staged files either —
 * a start that lands out of order (a sticky service restart before this activity) simply applies the
 * restore in *its* new process, and this one's launch finds it done.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, 0)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
        exitProcess(0)
    }

    companion object {
        private const val EXTRA_PID = "pid"

        /** The process-name suffix `KnitApplication.onCreate` checks to stay out of this process. */
        const val PROCESS_SUFFIX = ":restart"

        /**
         * Hands the app over to the trampoline: starts it with this process's pid and exits. The caller
         * has stopped the mesh service and cleared its notifications and shortcuts first; the staged
         * restore is applied by the next main process, not by anything here.
         */
        fun relaunch(context: Context): Nothing {
            context.startActivity(
                Intent(context, RestartActivity::class.java)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            exitProcess(0)
        }
    }
}
