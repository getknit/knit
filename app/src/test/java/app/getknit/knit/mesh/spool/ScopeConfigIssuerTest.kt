package app.getknit.knit.mesh.spool

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.data.settings.HomeStore
import app.getknit.knit.data.settings.SettingsKeys
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * When this phone issues a scope config (ADR 2026-10.8kfx): never while its home is `public-1`, once per DM whose
 * config does not name its home, for a group it created off `public-1` or one that gained a member, after a jitter for
 * a group whose relay drains, and again for whatever it still owes. Sweeps are driven by hand; [ScopeConfigIssuer.start]
 * only calls [ScopeConfigIssuer.sweep] on its events.
 */
class ScopeConfigIssuerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val p1 = PublicSpools.LEGACY_URL
    private val p2 = PublicSpools.urlOf(P2)
    private val p3 = PublicSpools.urlOf(P3)

    private class Rig(
        val issuer: ScopeConfigIssuer,
        val store: HomeStore,
        val data: DataStore<Preferences>,
        val winners: MutableStateFlow<Map<String, ScopeConfig>>,
        val groups: MutableStateFlow<List<IssuerGroup>>,
        val peers: MutableStateFlow<Set<String>>,
        val active: MutableStateFlow<Set<String>>,
        val sent: MutableList<Pair<String, ScopeConfigPayload>>,
        val clock: LongArray,
        val peerRows: HashMap<Pair<String, String>, ScopeConfig>,
    ) {
        var sealable = true

        suspend fun home(host: String) = data.edit { it[stringPreferencesKey(SettingsKeys.SPOOL_HOME)] = host }

        suspend fun directory(vararg entries: PublicSpool) = store.putDirectory(entries.toList(), at = 1L)
    }

    private fun TestScope.rig(jitterMs: Long = 0L): Rig {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val store = HomeStore(data)
        val winners = MutableStateFlow<Map<String, ScopeConfig>>(emptyMap())
        val groups = MutableStateFlow<List<IssuerGroup>>(emptyList())
        val peers = MutableStateFlow(setOf(PEER))
        val active = MutableStateFlow(setOf(PublicSpools.LEGACY_URL))
        val sent = mutableListOf<Pair<String, ScopeConfigPayload>>()
        val clock = longArrayOf(NOW)
        val peerRows = HashMap<Pair<String, String>, ScopeConfig>()
        lateinit var rig: Rig
        val issuer =
            ScopeConfigIssuer(
                journal = store,
                selfId = { ME },
                active = active,
                winners = winners,
                peerOwn = { id, issuer -> peerRows[id to issuer] ?: winners.value[id]?.takeIf { it.issuer == issuer } },
                groups = groups,
                rootChanges = emptyFlow(),
                dmPeers = { peers.value },
                send = { id, payload ->
                    if (rig.sealable) sent += id to payload
                    rig.sealable
                },
                clock = { clock[0] },
                jitter = { jitterMs },
            )
        rig = Rig(issuer, store, data, winners, groups, peers, active, sent, clock, peerRows)
        return rig
    }

    private fun open(host: String) = PublicSpool(host, PublicSpool.OPEN, 1)

    private fun config(
        id: String,
        spools: List<String>,
        issuer: String = OTHER,
        version: Int = 100,
        groupId: String? = null,
    ) = ScopeConfig(id, groupId, issuer, spools, 400, 1_000L, 64, version)

    @Test
    fun aPhoneHomedOnPublicOneIssuesNothing() =
        runTest {
            val rig = rig()
            rig.home(PublicSpools.LEGACY_HOST)
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), ME))
            rig.issuer.sweep()
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), ME), IssuerGroup(NEW_GROUP, setOf(ME, OTHER), ME))
            rig.issuer.sweep()
            assertEquals(emptyList<Pair<String, ScopeConfigPayload>>(), rig.sent)
            assertEquals(emptySet<String>(), rig.store.owed())
        }

    @Test
    fun aHomeOffPublicOneIssuesEachDmOnceNamingTheHomeFirst() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.issuer.sweep()
            val (id, payload) = rig.sent.single()
            assertEquals(PEER, id)
            assertEquals(listOf(p2, p1), payload.spools)
            assertNull(payload.groupId)
            assertEquals(ScopeConfigPolicy.minutesOf(NOW), payload.version)
            assertEquals(emptySet<String>(), rig.store.owed())
            // Once the config stands, nothing more is due.
            rig.winners.value = mapOf(PEER to config(PEER, payload.spools, issuer = ME, version = payload.version))
            rig.issuer.sweep()
            assertEquals(1, rig.sent.size)
        }

    @Test
    fun aPeersConfigThatLeavesTheHomeOutIsAnsweredKeepingThePeersHome() =
        runTest {
            val rig = rig()
            rig.directory(open(P1), open(P2), open(P3))
            rig.home(P2)
            rig.winners.value = mapOf(PEER to config(PEER, listOf(p3, p1), issuer = PEER, version = 500))
            rig.issuer.sweep()
            val payload = rig.sent.single().second
            assertEquals(listOf(p2, p3), payload.spools)
            assertEquals(maxOf(501, ScopeConfigPolicy.minutesOf(NOW)), payload.version)
        }

    @Test
    fun aMoveNamesTheNewHomeAndThePeersAndDropsTheOld() =
        runTest {
            val rig = rig()
            rig.directory(open(P1), open(P2), open(P3))
            rig.home(P2)
            // Our config named our old home (public-3) and the peer's, read from the peer's own (public-1 first).
            rig.winners.value = mapOf(PEER to config(PEER, listOf(p3, p1), issuer = ME, version = 600))
            rig.peerRows[PEER to PEER] = config(PEER, listOf(p1, p3), issuer = PEER, version = 500)
            rig.issuer.sweep()
            assertEquals(
                listOf(p2, p1),
                rig.sent
                    .single()
                    .second.spools,
            )
        }

    @Test
    fun aMoveOntoThePeersHomeDropsTheOldHomeOnceItDrains() =
        runTest {
            val rig = rig()
            rig.directory(open(P1), PublicSpool(P2, PublicSpool.DRAINING, 1), open(P3))
            rig.home(P3)
            // Our home was public-2 and Bob's is public-3: the config already names our new home.
            rig.winners.value = mapOf(PEER to config(PEER, listOf(p3, p2), issuer = PEER, version = 500))
            rig.issuer.sweep()
            val payload = rig.sent.single().second
            assertEquals(listOf(p3), payload.spools)
            // Standing, it is due no more.
            rig.winners.value = mapOf(PEER to config(PEER, payload.spools, issuer = ME, version = payload.version))
            rig.issuer.sweep()
            assertEquals(1, rig.sent.size)
        }

    @Test
    fun aSendThatSealsNothingStaysOwedAndWaitsBeforeTryingAgain() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.sealable = false
            val due = rig.issuer.sweep()
            assertEquals(setOf(PEER), rig.store.owed())
            assertEquals(NOW + ScopeConfigIssuer.RETRY_MS, due)
            // Its own reservation and release wake the next sweep at once: nothing is tried again then, nor short of
            // the wait.
            rig.sealable = true
            rig.issuer.sweep()
            rig.clock[0] = NOW + ScopeConfigIssuer.RETRY_MS - 1
            rig.issuer.sweep()
            assertTrue(rig.sent.isEmpty())
            // A root change may have brought the session; the wait is over.
            rig.issuer.onRootChanged("someone-else")
            rig.issuer.sweep()
            assertEquals(PEER, rig.sent.single().first)
            assertEquals(emptySet<String>(), rig.store.owed())
        }

    @Test
    fun aSendThatSealedNothingIsTriedAgainOnceTheWaitIsOver() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.sealable = false
            rig.issuer.sweep()
            rig.sealable = true
            rig.clock[0] = NOW + ScopeConfigIssuer.RETRY_MS
            rig.issuer.sweep()
            assertEquals(PEER, rig.sent.single().first)
            assertEquals(emptySet<String>(), rig.store.owed())
        }

    @Test
    fun aConfigStillOwedAtStartIsSentAgainAtTheNextVersion() =
        runTest {
            val rig = rig()
            rig.home(P2)
            // Our own row was reserved and the process died before the flood: the row wins, and is owed.
            rig.winners.value = mapOf(PEER to config(PEER, listOf(p2, p1), issuer = ME, version = 900_000))
            rig.store.owe(PEER)
            rig.issuer.sweep()
            val payload = rig.sent.single().second
            assertEquals(listOf(p2, p1), payload.spools)
            assertEquals(900_001, payload.version)
            assertEquals(emptySet<String>(), rig.store.owed())
        }

    @Test
    fun aRootChangeOwesOurOwnConfigAgainAndNotAPeers() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.winners.value =
                mapOf(PEER to config(PEER, listOf(p2, p1), issuer = ME), OTHER to config(OTHER, listOf(p2, p1), issuer = OTHER))
            rig.issuer.onRootChanged(OTHER)
            assertEquals(emptySet<String>(), rig.store.owed())
            rig.issuer.onRootChanged(PEER)
            assertEquals(setOf(PEER), rig.store.owed())
            rig.issuer.sweep()
            assertEquals(PEER, rig.sent.single().first)
        }

    @Test
    fun aGroupThisPhoneCreatesOffPublicOneIsIssuedAndOneJoinedIsNot() =
        runTest {
            val rig = rig()
            rig.directory(open(P1), open(P2), open(P3))
            rig.home(P2)
            rig.peers.value = emptySet()
            rig.issuer.sweep()
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), ME), IssuerGroup(NEW_GROUP, setOf(ME, OTHER), OTHER))
            rig.issuer.sweep()
            val (id, payload) = rig.sent.single()
            assertEquals(GROUP, id)
            assertEquals(GROUP, payload.groupId)
            assertEquals(listOf(p2), payload.spools)
        }

    @Test
    fun aGroupThatExistedAtStartOwesNothing() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.peers.value = emptySet()
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), ME))
            rig.issuer.sweep()
            rig.issuer.sweep()
            assertTrue(rig.sent.isEmpty())
        }

    @Test
    fun aMemberWhoRejoinsGetsOurConfigAgainAndNotAnotherIssuers() =
        runTest {
            val rig = rig()
            rig.directory(open(P1), open(P2), open(P3))
            rig.home(P2)
            rig.peers.value = emptySet()
            rig.winners.value = mapOf(GROUP to config(GROUP, listOf(p3), groupId = GROUP), NEW_GROUP to config(NEW_GROUP, listOf(p3)))
            rig.groups.value = listOf(IssuerGroup(NEW_GROUP, setOf(ME, OTHER), OTHER))
            rig.issuer.sweep()
            rig.groups.value = listOf(IssuerGroup(NEW_GROUP, setOf(ME, OTHER, NEWCOMER), OTHER))
            rig.issuer.sweep()
            assertTrue(rig.sent.isEmpty())
            rig.winners.value = mapOf(GROUP to config(GROUP, listOf(p3), issuer = ME, groupId = GROUP))
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), OTHER))
            rig.issuer.sweep()
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER, NEWCOMER), OTHER))
            rig.issuer.sweep()
            val payload = rig.sent.single().second
            assertEquals(listOf(p3), payload.spools)
            assertEquals(maxOf(101, ScopeConfigPolicy.minutesOf(NOW)), payload.version)
            // A member leaving owes nothing.
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, NEWCOMER), OTHER))
            rig.issuer.sweep()
            assertEquals(1, rig.sent.size)
        }

    @Test
    fun aGroupOnADrainingRelayMovesToTheHomeAfterItsJitter() =
        runTest {
            val rig = rig(jitterMs = 90_000L)
            rig.directory(open(P1), open(P2), PublicSpool(P3, PublicSpool.DRAINING, 1))
            rig.home(P2)
            rig.peers.value = emptySet()
            rig.winners.value = mapOf(GROUP to config(GROUP, listOf(p3), groupId = GROUP))
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), OTHER))
            assertEquals(NOW + 90_000L, rig.issuer.sweep())
            assertTrue(rig.sent.isEmpty())
            rig.clock[0] = NOW + 90_000L
            rig.issuer.sweep()
            assertEquals(
                listOf(p2),
                rig.sent
                    .single()
                    .second.spools,
            )
        }

    @Test
    fun aGroupMoveIsDroppedWhenAnotherMembersMoveLandsFirst() =
        runTest {
            val rig = rig(jitterMs = 90_000L)
            rig.directory(open(P1), open(P2), PublicSpool(P3, PublicSpool.DRAINING, 1))
            rig.home(P2)
            rig.peers.value = emptySet()
            rig.winners.value = mapOf(GROUP to config(GROUP, listOf(p3), groupId = GROUP))
            rig.groups.value = listOf(IssuerGroup(GROUP, setOf(ME, OTHER), OTHER))
            rig.issuer.sweep()
            rig.winners.value = mapOf(GROUP to config(GROUP, listOf(p2), version = 200, groupId = GROUP))
            rig.clock[0] = NOW + 90_000L
            assertNull(rig.issuer.sweep())
            assertTrue(rig.sent.isEmpty())
        }

    @Test
    fun aPhoneOffThePublicRelaysIssuesNothing() =
        runTest {
            val rig = rig()
            rig.home(P2)
            rig.active.value = setOf("wss://relay.example.org/spool/v1?k=t")
            rig.issuer.sweep()
            assertTrue(rig.sent.isEmpty())
        }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER = "cccccccccccccccccccccccccc"
        const val NEWCOMER = "dddddddddddddddddddddddddd"
        const val GROUP = "group-one"
        const val NEW_GROUP = "group-two"
        const val P1 = PublicSpools.LEGACY_HOST
        const val P2 = "public-2.spool.getknit.app"
        const val P3 = "public-3.spool.getknit.app"

        // 2026-10-23T12:00Z.
        const val NOW = 1_792_756_800_000L
    }
}
