package app.getknit.knit.mesh.crypto.ratchet

import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The lock-order regression: **the DB transaction is taken before the ratchet mutex, on every path.**
 *
 * This is the invariant that a lab device's deadlock came down to (see [SessionTransactor]). Room over
 * SQLCipher serves the app through one connection, so a facade method that took the mutex and *then*
 * needed the connection would deadlock against the decrypt path, which takes the connection and then
 * the mutex. Both parties are suspended coroutines, so the failure is invisible in a thread dump —
 * hence a test that pins the ordering directly rather than hoping to observe the hang.
 *
 * The fake below models Room faithfully enough for that: one connection ([connection]), and a
 * transaction that is reentrant for the same coroutine. Every store call asserts a transaction is open,
 * so a locked block that bypassed `locked()` fails here loudly instead of wedging a phone.
 */
class SessionTransactorOrderTest {
    /** Marks "this coroutine already holds the transaction", the way Room's context element does. */
    private class InTransaction : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<InTransaction>
    }

    /** The single connection every transaction must hold — Room's `PassthroughConnection`, modelled. */
    private val connection = Mutex()

    private val order = mutableListOf<String>()

    private val transactor =
        object : SessionTransactor {
            override suspend fun <T> transact(block: suspend () -> T): T =
                if (currentCoroutineContext()[InTransaction] != null) {
                    block() // reentrant: already inside one, exactly like Room
                } else {
                    connection.withLock {
                        synchronized(order) { order.add("tx") }
                        withContext(InTransaction()) { block() }
                    }
                }
        }

    /** A store that refuses to be touched outside a transaction — the assertion that does the work. */
    private inner class GuardedStore : GroupRatchetStore {
        private var chain: GroupRatchetEngine.SendChain? = null

        private suspend fun guard(what: String) {
            checkNotNull(currentCoroutineContext()[InTransaction]) {
                "$what touched the store with no enclosing transaction — the mutex was taken first, " +
                    "which is the inversion that deadlocks against the decrypt path"
            }
            synchronized(order) { order.add(what) }
            yield() // a real DB call suspends; give the other coroutine a chance to interleave
        }

        override suspend fun sendChain(groupId: String): GroupRatchetEngine.SendChain? {
            guard("sendChain")
            return chain
        }

        override suspend fun sendChains(groupId: String): List<GroupRatchetEngine.SendChain> {
            guard("sendChains")
            return listOfNotNull(chain)
        }

        override suspend fun commitSend(chain: GroupRatchetEngine.SendChain) {
            guard("commitSend")
            this.chain = chain
        }

        override suspend fun retireSendChains(groupId: String) = guard("retireSendChains")

        override suspend fun recvChains(
            groupId: String,
            senderId: String,
            epoch: Int,
        ): List<GroupRatchetEngine.RecvChain> = emptyList()

        override suspend fun skippedKeys(
            groupId: String,
            senderId: String,
            epoch: Int,
            idx: Int,
        ): List<GroupRatchetEngine.SkippedKey> = emptyList()

        override suspend fun insertRecvChain(chain: GroupRatchetEngine.RecvChain) = Unit

        override suspend fun applyOpen(
            groupId: String,
            senderId: String,
            delta: GroupRatchetEngine.OpenDelta,
            headerSe: Int,
            headerN: Int,
        ) = guard("applyOpen")

        override suspend fun purgeGroup(groupId: String) = Unit

        override suspend fun keySend(
            groupId: String,
            memberId: String,
        ): GroupKeySendState? = null

        override suspend fun keySends(groupId: String): List<GroupKeySendState> = emptyList()

        override suspend fun markKeySent(
            groupId: String,
            memberId: String,
            epoch: Int,
            at: Long,
        ) = Unit

        override suspend fun markKeyAcked(
            groupId: String,
            memberId: String,
            epoch: Int,
            at: Long,
        ) = Unit

        override suspend fun deleteKeySend(
            groupId: String,
            memberId: String,
        ) = Unit

        override suspend fun sweep(now: Long) = guard("sweep")
    }

    /** The DM store under the same guard: the reads [RatchetSessions.retiredBase] makes on the decrypt-failure path. */
    private inner class GuardedRatchetStore : RatchetStore {
        private suspend fun guard(what: String) {
            checkNotNull(currentCoroutineContext()[InTransaction]) { "$what touched the store with no enclosing transaction" }
            synchronized(order) { order.add(what) }
            yield()
        }

        override suspend fun session(peerId: String): RatchetEngine.SessionState {
            guard("session")
            return RatchetEngine.SessionState(
                peerId = peerId,
                confirmed = true,
                weAreInitiator = true,
                root = ByteArray(32),
                establishedAt = 1L,
                highestPeAcked = 20,
            )
        }

        override suspend fun sessionPeerIds(): List<String> = emptyList()

        override suspend fun upsertSession(state: RatchetEngine.SessionState) = guard("upsertSession")

        override suspend fun localEpochPriv(
            peerId: String,
            epoch: Int,
        ): ByteArray? = null

        override suspend fun lowestLocalEpoch(peerId: String): Int {
            guard("lowestLocalEpoch")
            return 12
        }

        override suspend fun recvEpochs(
            peerId: String,
            epoch: Int,
        ): List<RatchetEngine.RecvEpoch> = emptyList()

        override suspend fun skippedKeys(
            peerId: String,
            epoch: Int,
            idx: Int,
        ): List<RatchetEngine.SkippedKey> = emptyList()

        override suspend fun applyOpen(
            peerId: String,
            delta: RatchetEngine.OpenDelta,
            headerSe: Int,
            headerN: Int,
        ) = guard("applyOpen")

        override suspend fun commitSend(
            state: RatchetEngine.SessionState,
            newLocalEpoch: RatchetEngine.LocalEpoch?,
        ) = guard("commitSend")

        override suspend fun purgePeerRecvState(peerId: String) = guard("purgePeerRecvState")

        override suspend fun deletePeer(peerId: String) = guard("deletePeer")

        override suspend fun sweep(now: Long) = guard("sweep")
    }

    private fun dmSessions(mutex: Mutex) =
        RatchetSessions(store = GuardedRatchetStore(), dhIdentityPriv = {
            ByteArray(32)
        }, spkPrivFor = { null }, mutex = mutex, transact = transactor)

    private fun sessions(mutex: Mutex = Mutex()) = GroupRatchetSessions(store = GuardedStore(), mutex = mutex, transact = transactor)

    @Test
    fun `sealGroup opens the transaction before touching the store`() =
        runTest {
            sessions().sealGroup("g-1", "aaaa", "hi".toByteArray(), ByteArray(0), now = 1L)
            assertEquals("the transaction must be the first thing acquired", "tx", order.first())
            assertTrue("the store must have been used", order.contains("sendChain"))
        }

    @Test
    fun `currentSeeds and sweep also run inside a transaction`() =
        runTest {
            val s = sessions()
            s.currentSeeds("g-1")
            s.sweep(now = 1L)
            assertTrue(order.contains("sendChains"))
            assertTrue(order.contains("sweep"))
        }

    @Test
    fun `a caller that already opened a transaction joins it rather than deadlocking`() =
        runTest {
            // The decrypt path's shape: db.withWriteTransaction { commitOpen(...) }. The facade takes the
            // transaction again internally; Room's is reentrant, so this must not self-deadlock.
            val s = sessions()
            withTimeout(5_000) {
                transactor.transact {
                    s.sealGroup("g-1", "aaaa", "hi".toByteArray(), ByteArray(0), now = 1L)
                }
            }
            assertEquals("only one transaction was opened", 1, order.count { it == "tx" })
        }

    @Test
    fun `the decrypt order and the seal order run concurrently without deadlocking`() =
        runTest {
            // The exact pair that wedged the device: one coroutine taking transaction-then-mutex, another
            // going through the seal path — which, before the fix, took mutex-then-connection. With one
            // shared mutex and one shared connection, the wrong order hangs here forever.
            val mutex = Mutex()
            val s = sessions(mutex)
            withTimeout(10_000) {
                val decryptSide =
                    async {
                        transactor.transact {
                            // Hold the connection while the other side runs, so an inverted order meets it holding
                            // the mutex and deadlocks here instead of slipping in after this side is done.
                            yield()
                            mutex.withLock { yield() } // stands in for commitOpen's locked body
                        }
                    }
                val sealSide = async { s.sealGroup("g-1", "aaaa", "hi".toByteArray(), ByteArray(0), now = 1L) }
                decryptSide.await()
                sealSide.await()
            }
        }

    @Test
    fun `retiredBase reads the session and the epochs inside one transaction, beside the decrypt order`() =
        runTest {
            // ADR 2026-10.9wvr: the gate runs on the decrypt-failure path, while another frame's commit may hold the
            // transaction and the mutex. Its two reads go through `locked()`, so it queues behind them, never inverts.
            val mutex = Mutex()
            val s = dmSessions(mutex)
            withTimeout(10_000) {
                // The connection is held across a yield, as in the seal-order case: an inverted `locked()` deadlocks.
                val decryptSide =
                    async {
                        transactor.transact {
                            yield()
                            mutex.withLock { yield() }
                        }
                    }
                val gate = async { s.retiredBase("aaaa", pe = 5) }
                decryptSide.await()
                assertEquals(12, gate.await()?.lowestRetained)
            }
            // The guard refused any store read outside a transaction; both reads happened under one.
            assertTrue(order.contains("session") && order.contains("lowestLocalEpoch"))
            assertEquals("one transaction each side", 2, order.count { it == "tx" })
        }
}
