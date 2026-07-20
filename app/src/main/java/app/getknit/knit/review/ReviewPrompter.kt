package app.getknit.knit.review

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.ISSUES_URL
import app.getknit.knit.ui.PLAY_LISTING_URL
import app.getknit.knit.ui.REPO_URL
import app.getknit.knit.ui.review.ReviewPromptInbox
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when to ask an engaged user to rate Knit and — with no Play dependency — routes the ask to the
 * right place for however this build was installed. Gated by [ReviewPromptPolicy] over engagement derived
 * from the messages table (no counters to maintain), evaluated whenever the user lands on the chat list
 * (see the trigger in `KnitApp`).
 *
 * When the gates pass it records the attempt and signals [ReviewPromptInbox]; `KnitApp` observes that and
 * shows [app.getknit.knit.ui.review.RateReviewDialog] over the chat list. The dialog's buttons open
 * [rateUrl] (a happy rating) or [feedbackUrl] (steer a lukewarm user to the issue tracker, not a public
 * 1-star). "Attempt" = the dialog was shown; we don't track the button (or a dismiss), so the policy's
 * 30-day cooldown + lifetime cap do the rest. Once-per-process on top, so a config change never re-shows
 * within the same run. Demo builds skip it entirely.
 *
 * This replaces the old Play In-App Review integration: no GMS, no proprietary Play Core — just our own
 * dialog + a plain `ACTION_VIEW`, so it fires on de-googled / F-Droid / sideloaded builds too (the
 * installer only picks *where* the rating goes, it no longer gates *whether* we ask).
 */
class ReviewPrompter(
    private val context: Context,
    private val settings: SettingsStore,
    private val messages: MessageRepository,
    private val identity: Identity,
    private val inbox: ReviewPromptInbox,
) {
    private val shownThisProcess = AtomicBoolean(false)

    /**
     * The rating destination for how this build was installed: the Play listing when Play installed it
     * (where the user can actually leave a rating), the source repo otherwise — a de-googled / F-Droid /
     * sideloaded user can't rate on Play, so send them somewhere a star is meaningful.
     */
    fun rateUrl(): String = if (installedFromPlay()) PLAY_LISTING_URL else REPO_URL

    /** Where the "not really" branch goes: private feedback on the issue tracker, not a public review. */
    val feedbackUrl: String = ISSUES_URL

    /** True when Google Play installed this package. */
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

    /**
     * Evaluates the gates and, when they all pass, records the attempt and signals [ReviewPromptInbox] so
     * `KnitApp` shows the dialog. Fires on each chat-list landing; the cheap pre-gates keep it a no-op in
     * demo builds and after it has already fired this process.
     */
    suspend fun maybePrompt() {
        if (BuildConfig.SEED_DEMO) return
        if (shownThisProcess.get()) return
        val inputs = gateInputs(System.currentTimeMillis())
        if (inputs.engagementStartedAt == 0L && inputs.peerMessageCount > 0) {
            // First peer message observed: stamp the local-clock watermark. The age gate can't pass on
            // this same evaluation, so just start the clock and wait for a later chat-list visit.
            settings.setReviewEngagementStartedAt(inputs.now)
            return
        }
        if (!ReviewPromptPolicy.shouldPrompt(inputs)) return
        if (!shownThisProcess.compareAndSet(false, true)) return
        // Record before showing (attempt = shown); which button the user taps doesn't change the cooldown.
        settings.recordReviewAttempt(System.currentTimeMillis())
        inbox.offer()
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
