package app.getknit.knit.contacts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.spool.CommonsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The fold behind knit-next #285 step 5b's lost removal trigger (`PresenceKeyFeedLabTest`,
 * `aContactRemovedMidSessionIsRefusedTagNextAtTheFreshKeysFirstHandOff`, about 1 chaos run in 1000), and why the key
 * engine's trigger is [DirectContacts.sets], never [DirectContacts.observe].
 *
 * Both build the set from each input's last emission (`combine`). When the authored-threads query lags (Room's flows
 * re-query after each invalidation, conflated), a contact added and removed inside the lag is never computed. The value
 * computed after the removal can then equal [DirectContacts.observe]'s last emission, and its `distinctUntilChanged`
 * drops it, though [DirectContacts.read] saw the contact in between (in the lab, the root pass that put Carol in the
 * key's audience). [DirectContacts.sets] is not deduplicated: the lagging input's late emission recomputes the set and
 * it is emitted, so a reader comparing with what it last read itself (`MeshManager.watchPresenceKeys`) sees the change.
 *
 * The lag here is deterministic: the queries of the one collector named [LAGGING] are held at Room's query dispatcher
 * (every other query and write passes), which is what a 40–400 ms chaos tail on that dispatch did in the failing lab
 * run (seed 811160061: the watch collector's authored query sat from Room's messages version 2 to 7, about 380 ms,
 * across Alice's DM to Carol and the removal). Real Room (in-memory, framework SQLite), real repositories, real
 * DataStore-backed settings.
 */
@RunWith(AndroidJUnit4::class)
class DirectContactsObserveFoldTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gate = HoldingDispatcher(Dispatchers.IO)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: KnitDatabase

    @Before
    fun openDb() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryCoroutineContext(gate)
                .build()
    }

    @After
    fun closeDb() {
        gate.release()
        settingsScope.cancel()
        db.close()
    }

    @Test
    fun aContactAddedAndRemovedWhileTheAuthoredQueryLagsIsFollowedByTheSetAfterTheRemoval() =
        runBlocking {
            val identity = mockk<IdentitySource> { coEvery { nodeId() } returns ME }
            val messages = MessageRepository(db.messageDao())
            val peers = PeerRepository(db.peerDao(), mockk(relaxed = true), identity)
            val settings =
                SettingsStore(PreferenceDataStoreFactory.create(scope = settingsScope) { File(tmp.root, "s.preferences_pb") })
            val commons = mockk<CommonsStore> { coEvery { allMembers() } returns emptyList() }
            val direct = DirectContacts(settings, peers, messages, commons, identity)

            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            // The engine's contacts collector (`MeshManager.watchPresenceKeys`), on a dispatcher of its own.
            val watch = launch(Dispatchers.Default + CoroutineName(LAGGING)) { direct.sets().collect { seen.send(it) } }
            try {
                assertEquals(emptySet<String>(), withTimeout(WAIT_MS) { seen.receive() })
                messages.save(dm(BOB))
                awaitValue(seen, setOf(BOB))
                // The collector may still be re-running a query for Bob's write; hold only once it has gone quiet, so the
                // hold parks the next query, not one already past the gate.
                awaitQuiet(seen)

                gate.hold()
                messages.save(dm(CAROL))
                gate.awaitHeld()
                // Another reader of the same stores sees Carol: in the lab, the root pass that put her in the audience.
                assertEquals(setOf(BOB, CAROL), direct.read())
                // The removal's first write (`ContactRemover.remove`).
                messages.deleteByConversation(CAROL)
                assertEquals(setOf(BOB), direct.read())
                // Every query of the collector is parked now, so nothing it emitted before the hold may stand in for the
                // emission after the release.
                do {
                    val drained = seen.tryReceive().isSuccess
                } while (drained)
                gate.release()

                // Whatever was emitted before the release, the lagging query's late answer recomputes the set and sets()
                // emits it: the set after the removal, which a reader that last read {bob, carol} acts on. observe()
                // computes the same value and may drop it, equal to its own last emission.
                val after = withTimeoutOrNull(EMISSION_WAIT_MS) { seen.receive() }
                assertNotNull(
                    "sets() emitted nothing after Carol was added and removed while its authored query lagged, though read() " +
                        "saw her in between: the engine's contacts kick never comes, and the removal waits for a heal",
                    after,
                )
                assertEquals("the set after the removal", setOf(BOB), after)
            } finally {
                watch.cancelAndJoin()
            }
        }

    /** Receives from [seen] until [value] arrives: sets() may repeat a set (one per input emission) on the way. */
    private suspend fun awaitValue(
        seen: Channel<Set<String>>,
        value: Set<String>,
    ) = withTimeout(WAIT_MS) {
        do {
            val got = seen.receive()
        } while (got != value)
    }

    /** Drains [seen] until nothing arrives for [QUIET_MS] (bounded by [WAIT_MS]). */
    private suspend fun awaitQuiet(seen: Channel<Set<String>>) =
        withTimeout(WAIT_MS) {
            do {
                val got = withTimeoutOrNull(QUIET_MS) { seen.receive() }
            } while (got != null)
        }

    private fun dm(to: String) = MessageEntity(id = "m-$to", senderId = ME, recipientId = to, conversationId = to, body = "hi", sentAt = 1L)

    /**
     * A query dispatcher that, while held, parks every dispatch made for the coroutine named [LAGGING] and passes every
     * other one: the lagging collector's queries wait, the writes and the other readers do not.
     */
    private class HoldingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        private val lock = Any()
        private var holding = false
        private val held = ArrayList<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(lock) {
                if (holding && context[CoroutineName]?.name == LAGGING) {
                    held += context to block
                    return
                }
            }
            delegate.dispatch(context, block)
        }

        fun hold() = synchronized(lock) { holding = true }

        suspend fun awaitHeld() =
            withTimeout(WAIT_MS) {
                while (synchronized(lock) { held.isEmpty() }) delay(1)
            }

        fun release() {
            val parked =
                synchronized(lock) {
                    holding = false
                    held.toList().also { held.clear() }
                }
            parked.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    private companion object {
        const val LAGGING = "lagging-direct-contacts-collector"
        const val WAIT_MS = 10_000L
        const val EMISSION_WAIT_MS = 3_000L
        const val QUIET_MS = 300L
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CAROL = "cccccccccccccccccccccccccc"
    }
}
