package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshcoreForget] (ADR 2026-11.gsqu's Stage 7 amendment): the unbind first, then one edit a branch, each under the claim
 * fence; a pending cleanup keeps the binding unless asked anyway; a failure writes nothing; and it runs on the application's
 * scope, so a caller that leaves does not cut it short.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreForgetTest {
    private val events = mutableListOf<String>()
    private var unbindAnswer = MeshcoreUnbind.CLEARED
    private var bound: String? = ADDRESS
    private var unbindGate: CompletableDeferred<Unit>? = null
    private var clearThrows = false
    private var withdrawThrows = 0

    private fun TestScope.forget(): MeshcoreForget =
        MeshcoreForget(
            scope = backgroundScope,
            boundAddress = { bound },
            unbind = { address ->
                events += "unbind $address"
                unbindGate?.await()
                unbindAnswer
            },
            fenceClaim = { address, edit ->
                events += "fence $address"
                edit()
            },
            clearBinding = { address ->
                if (clearThrows) throw java.io.IOException("DataStore")
                events += "clear $address"
            },
            withdrawClaim = { address ->
                if (withdrawThrows-- > 0) throw java.io.IOException("DataStore")
                events += "withdraw $address"
            },
        )

    @Test
    fun aClearedOrEmptyUnbindForgetsTheWholeBindingUnderTheFence() =
        runTest {
            assertEquals(MeshcoreForgetResult.CLEARED, forget().forget())
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "clear $ADDRESS"), events)
            events.clear()
            unbindAnswer = MeshcoreUnbind.NOTHING
            assertEquals(MeshcoreForgetResult.CLEARED, forget().forget())
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "clear $ADDRESS"), events)
        }

    @Test
    fun aPendingCleanupKeepsTheBindingAndWithdrawsTheClaimUnlessAskedAnyway() =
        runTest {
            unbindAnswer = MeshcoreUnbind.PENDING
            assertEquals(MeshcoreForgetResult.PENDING, forget().forget())
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "withdraw $ADDRESS"), events)
            events.clear()
            assertEquals(MeshcoreForgetResult.FORGOTTEN_PENDING, forget().forget(anyway = true))
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "clear $ADDRESS"), events)
        }

    @Test
    fun aMismatchForgetsTheBindingAndSaysKnitCannotCleanIt() =
        runTest {
            unbindAnswer = MeshcoreUnbind.MISMATCH
            assertEquals(MeshcoreForgetResult.MISMATCH, forget().forget())
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "clear $ADDRESS"), events)
        }

    @Test
    fun aFailedUnbindOrAFailedEditWritesNothingOfItsOwn() =
        runTest {
            unbindAnswer = MeshcoreUnbind.FAILED
            assertEquals(MeshcoreForgetResult.FAILED, forget().forget(anyway = true))
            assertEquals(listOf("unbind $ADDRESS"), events)
            events.clear()
            unbindAnswer = MeshcoreUnbind.CLEARED
            clearThrows = true
            assertEquals(MeshcoreForgetResult.FAILED, forget().forget())
            assertTrue(events.none { it.startsWith("clear") })
        }

    /**
     * Security-reviewer (Stage 7a): the unbind withdrew, then the Forget's own edit threw. FAILED, the binding untouched, and
     * the claim's withdrawal owed — retried behind the fence on the claim backoff until it lands, a throw costing one round.
     */
    @Test
    fun aForgetWhoseEditThrowsAnswersFailedAndOwesTheClaimsWithdrawal() =
        runTest {
            clearThrows = true
            withdrawThrows = 1
            assertEquals(MeshcoreForgetResult.FAILED, forget().forget())
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS"), events)
            // The first retry at 1 s, fenced, and its withdrawal threw.
            advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS + 1)
            assertEquals("the first retry fenced: $events", 2, events.count { it == "fence $ADDRESS" })
            assertTrue("the first retry threw: $events", events.none { it.startsWith("withdraw") })
            // The backoff doubles: nothing more before 3 s, the second retry's withdrawal at 3 s.
            advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS * 2 - 2)
            assertTrue("not before the doubled wait: $events", events.none { it.startsWith("withdraw") })
            advanceTimeBy(2)
            assertEquals("withdraw $ADDRESS", events.last())
            assertTrue("never the binding", events.none { it.startsWith("clear") })
        }

    /** An unbind that failed withdrew nothing for sure: a throw after it owes nothing either. */
    @Test
    fun aFailedUnbindOwesNoWithdrawal() =
        runTest {
            unbindAnswer = MeshcoreUnbind.FAILED
            assertEquals(MeshcoreForgetResult.FAILED, forget().forget())
            advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_MAX_MS * 4)
            assertEquals(listOf("unbind $ADDRESS"), events)
        }

    @Test
    fun nothingBoundIsNothingToForget() =
        runTest {
            bound = null
            assertEquals(MeshcoreForgetResult.NOT_BOUND, forget().forget())
            assertTrue(events.isEmpty())
        }

    /** Lora L2: the unbind can wait long on the actor; the caller leaving never skips the clear that follows it. */
    @Test
    fun aCallerThatLeavesNeverCutsTheForgetShort() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            unbindGate = gate
            val f = forget()
            val caller = async { f.forget() }
            runCurrent()
            assertEquals(listOf("unbind $ADDRESS"), events)
            caller.cancel()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("unbind $ADDRESS", "fence $ADDRESS", "clear $ADDRESS"), events)
        }

    private companion object {
        const val ADDRESS = "CC:CC"
    }
}
