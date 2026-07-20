package app.getknit.knit.review

/**
 * Pure "should we ask the user to rate the app right now?" decision, mirroring
 * [app.getknit.knit.mesh.bluetooth.ScanDemandPolicy]: no Android, an injected clock, JVM-unit-testable.
 *
 * The prompt targets the *engaged* user — the mesh has visibly worked more than once and they've
 * participated — following the standard "don't ask too early" guidance. All five gates must pass:
 *  1. ≥ [MIN_PEER_MESSAGES] messages received from real peers (`senderId != me`, normal kind);
 *  2. ≥ [MIN_SENT_MESSAGES] messages sent (participation, not just lurking);
 *  3. ≥ [MIN_ENGAGEMENT_AGE_MS] since the first peer message was observed *locally*
 *     ([Inputs.engagementStartedAt] is a local-clock watermark persisted by ReviewPrompter — deliberately
 *     not derived from a message's `sentAt`, which is the sender's skewable clock);
 *  4. fewer than [MAX_ATTEMPTS] lifetime attempts;
 *  5. ≥ [REPROMPT_COOLDOWN_MS] since the last attempt.
 *
 * An "attempt" means we *showed* the prompt — we deliberately don't record which button the user tapped
 * (rate, feedback, or a dismiss), so the policy re-asks only on a long cooldown and caps the total for good.
 */
object ReviewPromptPolicy {
    /** Messages received from real peers before we consider the mesh proven to this user. */
    const val MIN_PEER_MESSAGES = 3

    /** Messages the user must have sent themselves — a lurker isn't invested enough to ask. */
    const val MIN_SENT_MESSAGES = 1

    /** The first peer message must be at least this old (locally observed) — "it worked more than once". */
    const val MIN_ENGAGEMENT_AGE_MS = 24L * 60 * 60 * 1000

    /** Minimum gap between attempts — covers a dismissed-or-ignored first prompt without nagging. */
    const val REPROMPT_COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000

    /** Lifetime attempt cap; after this we never ask again. */
    const val MAX_ATTEMPTS = 3

    /**
     * @param peerMessageCount normal-kind messages in the DB from senders other than us
     * @param sentMessageCount messages in the DB we sent
     * @param engagementStartedAt local-clock time the first peer message was observed; 0 = not yet
     * @param lastAttemptAt local-clock time of the last prompt shown; 0 = never attempted
     * @param attemptCount lifetime prompts shown so far
     */
    data class Inputs(
        val peerMessageCount: Int,
        val sentMessageCount: Int,
        val engagementStartedAt: Long,
        val lastAttemptAt: Long,
        val attemptCount: Long,
        val now: Long,
    )

    fun shouldPrompt(inputs: Inputs): Boolean =
        inputs.peerMessageCount >= MIN_PEER_MESSAGES &&
            inputs.sentMessageCount >= MIN_SENT_MESSAGES &&
            inputs.engagementStartedAt > 0 &&
            inputs.now - inputs.engagementStartedAt >= MIN_ENGAGEMENT_AGE_MS &&
            inputs.attemptCount < MAX_ATTEMPTS &&
            (inputs.lastAttemptAt == 0L || inputs.now - inputs.lastAttemptAt >= REPROMPT_COOLDOWN_MS)
}
