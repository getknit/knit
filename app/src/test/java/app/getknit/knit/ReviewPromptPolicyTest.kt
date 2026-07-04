package app.getknit.knit

import app.getknit.knit.review.ReviewPromptPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ReviewPromptPolicy] — the pure five-gate review-prompt decision, virtual clock. */
class ReviewPromptPolicyTest {
    /** Inputs that pass every gate; each test flips one. */
    private fun inputs(
        peerMessages: Int = ReviewPromptPolicy.MIN_PEER_MESSAGES,
        sentMessages: Int = ReviewPromptPolicy.MIN_SENT_MESSAGES,
        engagementStartedAt: Long = 1L,
        lastAttemptAt: Long = 0L,
        attemptCount: Long = 0L,
        now: Long = 1L + ReviewPromptPolicy.MIN_ENGAGEMENT_AGE_MS,
    ) = ReviewPromptPolicy.Inputs(peerMessages, sentMessages, engagementStartedAt, lastAttemptAt, attemptCount, now)

    @Test
    fun promptsWhenEveryGatePasses() {
        assertTrue(ReviewPromptPolicy.shouldPrompt(inputs()))
    }

    @Test
    fun blocksOnTooFewPeerMessages() {
        assertFalse(ReviewPromptPolicy.shouldPrompt(inputs(peerMessages = ReviewPromptPolicy.MIN_PEER_MESSAGES - 1)))
    }

    @Test
    fun blocksWhenNothingSent() {
        assertFalse(ReviewPromptPolicy.shouldPrompt(inputs(sentMessages = 0)))
    }

    @Test
    fun blocksWhileEngagementWatermarkUnset() {
        // Watermark 0 = no peer message ever observed; a huge `now` must not sneak past the age gate.
        assertFalse(ReviewPromptPolicy.shouldPrompt(inputs(engagementStartedAt = 0)))
    }

    @Test
    fun blocksWhileEngagementIsTooYoung() {
        val start = 1L
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(
                inputs(engagementStartedAt = start, now = start + ReviewPromptPolicy.MIN_ENGAGEMENT_AGE_MS - 1),
            ),
        )
    }

    @Test
    fun promptsAtExactlyTheEngagementAgeThreshold() {
        val start = 1L
        assertTrue(
            ReviewPromptPolicy.shouldPrompt(
                inputs(engagementStartedAt = start, now = start + ReviewPromptPolicy.MIN_ENGAGEMENT_AGE_MS),
            ),
        )
    }

    @Test
    fun blocksAtTheLifetimeAttemptCap() {
        assertFalse(ReviewPromptPolicy.shouldPrompt(inputs(attemptCount = ReviewPromptPolicy.MAX_ATTEMPTS.toLong())))
    }

    @Test
    fun blocksWithinTheRepromptCooldown() {
        val now = inputs().now
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(
                inputs(lastAttemptAt = now - ReviewPromptPolicy.REPROMPT_COOLDOWN_MS + 1, attemptCount = 1, now = now),
            ),
        )
    }

    @Test
    fun promptsAgainAtExactlyTheCooldown() {
        // Keep the engagement gate satisfied at the later `now` too.
        val now = 10L * ReviewPromptPolicy.REPROMPT_COOLDOWN_MS
        assertTrue(
            ReviewPromptPolicy.shouldPrompt(
                inputs(lastAttemptAt = now - ReviewPromptPolicy.REPROMPT_COOLDOWN_MS, attemptCount = 1, now = now),
            ),
        )
    }

    @Test
    fun neverPromptsOnCompletelyFreshState() {
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(
                inputs(peerMessages = 0, sentMessages = 0, engagementStartedAt = 0, now = Long.MAX_VALUE / 2),
            ),
        )
    }
}
