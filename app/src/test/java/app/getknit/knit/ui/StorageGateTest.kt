package app.getknit.knit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.crypto.KeystoreUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.InvalidKeyException

/**
 * What `MainActivity` shows on a cold start (ADR 2026-10.47rw): the app once storage opens, Try again while the
 * Keystore refuses — found under Koin's wrappers — and the crash a failed graph always was for anything else.
 * Robolectric only for `android.util.Log`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StorageGateTest {
    private val dispatcher = StandardTestDispatcher()
    private var opens = 0
    private val crashes = mutableListOf<Throwable>()

    /** What the next opens throw, in order; an empty queue opens. */
    private val failures = ArrayDeque<Throwable>()

    private fun gate() =
        StorageGate(
            scope = CoroutineScope(dispatcher),
            openStorage = {
                opens++
                failures.removeFirstOrNull()?.let { throw it }
            },
            io = dispatcher,
            crash = { crashes += it },
        )

    /** A refusal as Koin hands it up: DatabaseKey's exception under one InstanceCreationException per single. */
    private fun refusal(): Throwable =
        RuntimeException(
            "Could not create instance for 'KnitDatabase'",
            KeystoreUnavailableException("database passphrase", InvalidKeyException("Keystore operation failed")),
        )

    @Test
    fun anOpenThatWorksIsReady() =
        runTest(dispatcher) {
            val gate = gate()
            assertSame(StorageGate.State.Opening, gate.state.value)
            gate.open()
            advanceUntilIdle()
            assertSame(StorageGate.State.Ready, gate.state.value)
            gate.open()
            advanceUntilIdle()
            assertEquals("Ready is terminal", 1, opens)
        }

    @Test
    fun aRefusalIsUnavailableAndTryAgainOpens() =
        runTest(dispatcher) {
            val gate = gate()
            failures += refusal()
            gate.open()
            advanceUntilIdle()
            assertEquals(StorageGate.State.Unavailable(trying = false), gate.state.value)

            gate.open()
            assertEquals(StorageGate.State.Unavailable(trying = true), gate.state.value)
            advanceUntilIdle()
            assertSame(StorageGate.State.Ready, gate.state.value)
            assertTrue(crashes.isEmpty())
        }

    @Test
    fun opensWhileOneRunsAreOne() =
        runTest(dispatcher) {
            val gate = gate()
            repeat(3) { gate.open() }
            advanceUntilIdle()
            assertEquals(1, opens)
        }

    @Test
    fun anyOtherFailureIsACrash() =
        runTest(dispatcher) {
            val gate = gate()
            val failure = IllegalStateException("identity keys unwrapped but do not parse")
            failures += failure
            gate.open()
            advanceUntilIdle()
            assertEquals(listOf<Throwable>(failure), crashes)
            assertSame(StorageGate.State.Opening, gate.state.value)
        }
}
