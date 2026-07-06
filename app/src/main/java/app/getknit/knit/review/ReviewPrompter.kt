package app.getknit.knit.review

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks for a Play Store rating at the moment the mesh has proven itself — gated by
 * [ReviewPromptPolicy] over engagement derived from the messages table (no counters to maintain),
 * evaluated whenever the user lands on the chat list (see the trigger in `KnitApp`).
 *
 * Deliberately harmless everywhere the prompt can't work: demo builds and non-Play installs
 * (de-googled devices, and sideloads via the offline share-APK flow — Play can't review those anyway)
 * are filtered by cheap pre-gates before the Play API is ever touched, and any API failure — e.g. a
 * Play-Store-less device that somehow passed the installer gate — is swallowed. The API also gives no
 * feedback (a quota-suppressed dialog and a completed rating look identical), so all we record is that
 * an attempt was made ([SettingsStore.recordReviewAttempt]); the policy's cooldown + lifetime cap do
 * the rest. Once-per-process on top, so a transient failure is never retried in a hot loop.
 */
class ReviewPrompter(
    private val context: Context,
    private val settings: SettingsStore,
    private val messages: MessageRepository,
    private val identity: Identity,
) {
    private val attemptedThisProcess = AtomicBoolean(false)

    /** True when Play installed (and therefore can review) this package. */
    fun installedFromPlay(): Boolean =
        try {
            // getInstallSourceInfo is API 30; on 29 the deprecated getInstallerPackageName gives the installer.
            @Suppress("DEPRECATION")
            val installer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            installer == PLAY_STORE_PACKAGE
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** The policy inputs as they stand right now — read-only, shared with the debug bridge dump. */
    suspend fun gateInputs(now: Long): ReviewPromptPolicy.Inputs {
        val me = identity.nodeId()
        val msgs = messages.observeMessages().first()
        return ReviewPromptPolicy.Inputs(
            peerMessageCount = msgs.count { it.senderId != me && it.kind == MessageEntity.KIND_NORMAL },
            sentMessageCount = msgs.count { it.senderId == me },
            engagementStartedAt = settings.reviewEngagementStartedAt.first(),
            lastAttemptAt = settings.reviewLastAttemptAt.first(),
            attemptCount = settings.reviewAttemptCount.first(),
            now = now,
        )
    }

    /** Evaluates the gates and, when they all pass, launches the In-App Review flow over [activity]. */
    suspend fun maybePrompt(activity: Activity) {
        if (BuildConfig.SEED_DEMO) return
        if (attemptedThisProcess.get()) return
        if (!installedFromPlay()) return
        val inputs = gateInputs(System.currentTimeMillis())
        if (inputs.engagementStartedAt == 0L && inputs.peerMessageCount > 0) {
            // First peer message observed: stamp the local-clock watermark. The age gate can't pass on
            // this same evaluation, so just start the clock and wait for a later chat-list visit.
            settings.setReviewEngagementStartedAt(inputs.now)
            return
        }
        if (!ReviewPromptPolicy.shouldPrompt(inputs)) return
        if (!attemptedThisProcess.compareAndSet(false, true)) return
        runCatching {
            val manager = ReviewManagerFactory.create(context)
            val info = manager.requestReview()
            // Count the attempt only after a successful request (a transient failure doesn't burn the
            // 30-day cooldown) but before launch — Play may quota-suppress the dialog with no signal.
            settings.recordReviewAttempt(System.currentTimeMillis())
            manager.launchReview(activity, info)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.d(TAG, "in-app review unavailable: $it")
        }
    }

    private companion object {
        const val TAG = "ReviewPrompter"
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
