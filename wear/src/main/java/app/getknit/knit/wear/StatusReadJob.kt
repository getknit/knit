package app.getknit.knit.wear

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Where every read of the phone runs (ADR 2026-09.wetm, fourth amendment). A complication or tile request only
 * lifts this process out of the freezer for the call itself: the system freezes a cached process ten seconds
 * later whatever it is still doing, and a Classic page after the watch has idled can take longer than that — the
 * phone accepted, wrote, lingered and closed while the watch sat frozen. A running job keeps the process out of
 * the cached state, so the read finishes.
 *
 * Expedited, so it starts at once; when the expedited quota is spent (or on Wear OS 3, which has none) it goes as
 * a plain job with no constraints, which also starts at once while the watch is awake — and a read is only asked
 * for while it is. One job id: scheduling it again replaces a pending one, and [StatusRefresh] never schedules
 * while one is running (that would stop it).
 */
class StatusReadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        val force = params.extras.getBoolean(EXTRA_FORCE, false)
        // Atomic: a stop that lands before the body starts still enters it, so [StatusRefresh.run]'s finally
        // clears the in-flight mark (the read gives up at its first cancellable step) — or the mark would stick.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                StatusRefresh.run(applicationContext, force)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KnitWear"
        private const val JOB_ID = 1
        private const val EXTRA_FORCE = "force"

        /** Schedules one read; false if the scheduler took neither form, so the caller can clear its in-flight mark. */
        fun schedule(
            context: Context,
            force: Boolean,
        ): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val extras = PersistableBundle().apply { putBoolean(EXTRA_FORCE, force) }

            fun scheduled(expedited: Boolean): Boolean =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, StatusReadJob::class.java))
                    .setExtras(extras)
                    .apply { if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(true) }
                    .build()
                    .let { scheduler.schedule(it) == JobScheduler.RESULT_SUCCESS }
            return runCatching {
                // Out of expedited quota the scheduler refuses at once; a plain job still starts while awake.
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scheduled(expedited = true)) ||
                    scheduled(expedited = false)
            }.onFailure { Log.w(TAG, "read job not scheduled: ${it.message}") }
                .getOrDefault(false)
        }
    }
}
