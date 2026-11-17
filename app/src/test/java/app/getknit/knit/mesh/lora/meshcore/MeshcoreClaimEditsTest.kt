package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshcoreClaimEdits]' Forget (ADR 2026-11.gsqu's Stage 7a amendment): a Forget's own edit is never skipped for a grant that
 * landed while it waited on the lock (lora-reviewer), while an owed retry fenced at an earlier seq is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreClaimEditsTest {
    @Test
    fun aForgetsOwnEditRunsEvenWhenANewerGrantLandedWhileItWaited() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val edits =
                MeshcoreClaimEdits(backgroundScope, MeshcorePlaneCounters()) { key, _, _ ->
                    if (order.isEmpty()) gate.await()
                    order += if (key != null) "grant" else "withdraw"
                }
            // A grant stalls in the lock; a newer one queues behind it, ahead of the Forget.
            backgroundScope.launch { edits.claim(ADDRESS, ByteArray(32), null, MeshcoreConsent.latestSeq()) }
            runCurrent()
            val newer = MeshcoreConsent.latestSeq() + 1_000
            backgroundScope.launch { edits.claim(ADDRESS, ByteArray(32) { 1 }, null, newer) }
            runCurrent()
            val forget = async { edits.forget(ADDRESS) { order += "forget" } }
            runCurrent()
            gate.complete(Unit)
            assertTrue("the Forget's own edit always runs", forget.await())
            assertEquals(listOf("grant", "grant", "forget"), order)
            // An owed retry fenced before that grant skips instead.
            assertFalse(edits.forget(ADDRESS, upTo = newer - 1) { order += "retry" })
            assertEquals(3, order.size)
        }

    private companion object {
        const val ADDRESS = "CC:CC"
    }
}
