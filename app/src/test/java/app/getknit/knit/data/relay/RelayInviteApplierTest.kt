package app.getknit.knit.data.relay

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.spool.CommonsAdverts
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.CommonsRoots
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolCommonsInfo
import app.getknit.knit.mesh.spool.TicketRefresherTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one apply sequence on plain JVM: consent is recorded through the master switch's own write and
 * only when missing; the relay is stored, un-parked and dialled now; a room is joined, left alone when
 * already joined, and joined beside any other room there, never as a rotation (C-7.4-18); a relay is matched by its
 * redacted URL so a tokened
 * invite replaces an untokened entry and never the reverse; a build with no commons store ignores the
 * room half.
 */
class RelayInviteApplierTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val commons = mockk<CommonsRepository>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)

    private val spoolEnabled = MutableStateFlow(false)
    private val spoolConsented = MutableStateFlow(false)
    private val spoolUrls = MutableStateFlow(emptySet<String>())
    private val parked = MutableStateFlow(emptySet<String>())
    private val roots = mutableListOf<CommonsRoots>()

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun setUp() {
        every { settings.spoolEnabled } returns spoolEnabled
        every { settings.spoolConsented } returns spoolConsented
        every { settings.spoolUrls } returns spoolUrls
        every { settings.disabledSpoolUrls } returns parked
        coEvery { commons.roots() } answers { roots.toList() }
        coEvery { commons.find(any()) } answers
            {
                roots.firstOrNull { it.conversationId == firstArg() }?.let {
                    CommonsRoom(
                        it.conversationId,
                        it.spoolUrl,
                        it.secret,
                        "Home",
                    )
                }
            }
    }

    private val keys = TicketRefresherTest.MemoryKeyJournal()

    private fun applier(
        withCommons: Boolean = true,
        withTickets: Boolean = true,
    ) = RelayInviteApplier(settings, commons.takeIf { withCommons }, mesh, keys = keys.takeIf { withTickets }, clock = { NOW })

    private fun keyed(
        url: String = TOKENED,
        key: String = KEY,
        issuer: String = ISSUER,
    ) = RelayInvite.Parsed.Invite(url, null, null, issuer = issuer, key = key)

    private fun invite(
        url: String = TOKENED,
        secret: ByteArray? = null,
        name: String? = null,
    ) = RelayInvite.Parsed.Invite(url, secret, name)

    @Test
    fun aFirstInviteRecordsConsentStoresTheRelayAndDialsNow() =
        runTest {
            val preview = applier().preview(invite())
            assertTrue(preview.consentNeeded)
            assertTrue(preview.planeOff)
            assertTrue(preview.private)
            assertEquals("home.example.org", preview.host)
            assertFalse(preview.alreadyAdded)
            assertNull(preview.room)
            assertFalse(preview.isNoOp)

            applier().apply(preview)
            coVerifyOrder {
                settings.acceptSpoolConsent()
                settings.addSpoolUrl(TOKENED)
                mesh.refreshRelays()
            }
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
            coVerify(exactly = 0) { settings.setSpoolUrlEnabled(any(), any()) }
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
        }

    @Test
    fun aConsentedButSwitchedOffPlaneIsTurnedOnWithoutASecondConsent() =
        runTest {
            spoolConsented.value = true
            val preview = applier().preview(invite())
            assertFalse(preview.consentNeeded)
            assertTrue(preview.planeOff)
            applier().apply(preview)
            coVerify(exactly = 0) { settings.acceptSpoolConsent() }
            coVerify { settings.setSpoolEnabled(true) }
        }

    @Test
    fun anAlreadyListedRelayIsANoOpUnlessParked() =
        runTest {
            spoolConsented.value = true
            spoolEnabled.value = true
            spoolUrls.value = setOf(TOKENED)
            val same = applier().preview(invite())
            assertTrue(same.alreadyAdded)
            assertTrue(same.isNoOp)
            applier().apply(same)
            coVerify(exactly = 0) { settings.setSpoolUrlEnabled(any(), any()) }
            coVerify { settings.addSpoolUrl(TOKENED) }

            parked.value = setOf(TOKENED)
            val parkedOne = applier().preview(invite())
            assertTrue(parkedOne.parked)
            assertFalse(parkedOne.isNoOp)
            applier().apply(parkedOne)
            coVerify { settings.setSpoolUrlEnabled(TOKENED, true) }
        }

    @Test
    fun aTokenedInviteReplacesAnUntokenedEntry() =
        runTest {
            spoolUrls.value = setOf(UNTOKENED)
            val up = applier().preview(invite(TOKENED))
            assertEquals(TOKENED, up.url)
            assertEquals(UNTOKENED, up.replaces)
            assertFalse(up.alreadyAdded)
            applier().apply(up)
            coVerifyOrder {
                settings.removeSpoolUrl(UNTOKENED)
                settings.addSpoolUrl(TOKENED)
            }
        }

    @Test
    fun anUntokenedInviteNeverDowngradesATokenedEntry() =
        runTest {
            spoolUrls.value = setOf(TOKENED)
            val down = applier().preview(invite(UNTOKENED))
            assertEquals(TOKENED, down.url)
            assertNull(down.replaces)
            assertTrue(down.alreadyAdded)
            assertTrue(down.private)
            applier().apply(down)
            coVerify(exactly = 0) { settings.removeSpoolUrl(any()) }
            coVerify(exactly = 0) { settings.addSpoolUrl(UNTOKENED) }
        }

    @Test
    fun anInviteWithAnUppercaseSchemeIsTheSameRelayAsTheStoredRow() =
        runTest {
            // Matching is by exact string, so the link is folded to the stored form before it is compared:
            // otherwise `WSS://` would add a second row for one host and the untokened one would sit
            // refused `4001` forever — the case this matching rule exists to prevent.
            spoolUrls.value = setOf(UNTOKENED)
            val shouty = applier().preview(invite(TOKENED.replaceFirst("wss://", "WSS://")))
            assertEquals(TOKENED, shouty.url)
            assertEquals(UNTOKENED, shouty.replaces)
            applier().apply(shouty)
            coVerifyOrder {
                settings.removeSpoolUrl(UNTOKENED)
                settings.addSpoolUrl(TOKENED)
            }
        }

    @Test
    fun aRoomJoinedUnderTheReplacedEntryMovesWithTheRelay() =
        runTest {
            spoolUrls.value = setOf(UNTOKENED)
            roots += CommonsRoots("c-1", UNTOKENED, secret)
            val preview = applier().preview(invite(TOKENED, secret))
            val room = preview.room!!
            assertTrue(room.alreadyJoined)
            assertFalse(room.beside)
            applier().apply(preview)
            coVerifyOrder {
                commons.join(TOKENED, secret, "Home", NOW)
                settings.removeSpoolUrl(UNTOKENED)
                settings.addSpoolUrl(TOKENED)
                mesh.refreshRelays()
            }
            // Re-bound once, with its stored name — never joined a second time under the link's empty one.
            coVerify(exactly = 1) { commons.join(any(), any(), any(), any()) }
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
        }

    @Test
    fun aRoomIsJoinedWithTheLinksNameThenTheLiveOne() =
        runTest {
            // A relay that predates `rooms`: its one commons names whichever room is asked about.
            val theirs = CommonsAdverts.of(advert("Theirs"), rooms = null)
            val named = applier().preview(invite(TOKENED, secret, "Ours"), live = theirs)
            assertEquals("Ours", named.room!!.name)
            applier().apply(named)
            coVerify { commons.join(TOKENED, secret, "Ours", NOW) }

            val live = applier().preview(invite(TOKENED, secret), live = theirs)
            assertEquals("Theirs", live.room!!.name)
            assertNull(applier().preview(invite(TOKENED, secret)).room!!.name)
        }

    @Test
    fun theLiveNameIsTheOneTheRelayListsForThisRoomsTag() =
        runTest {
            // C-7.4-17: a relay running two rooms lists each by tag, and `commons` repeats only the first.
            val live =
                CommonsAdverts.of(
                    advert("Home"),
                    listOf(tagOf(secret) to advert("Home"), tagOf(otherSecret) to advert("Team")),
                )
            assertEquals("Team", applier().preview(invite(TOKENED, otherSecret), live = live).room!!.name)
            // A room the relay does not list has no live name, whatever `commons` says.
            assertNull(applier().preview(invite(TOKENED, ByteArray(32) { 9 }), live = live).room!!.name)
        }

    private fun advert(name: String) = SpoolCommonsInfo(name, maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536)

    private fun tagOf(secret: ByteArray) = ScopeCrypto.commonsTag(ScopeCrypto.commonsScopeId(secret))

    @Test
    fun theSameRoomAgainIsANoOpUpsertAndADifferentOneJoinsBesideIt() =
        runTest {
            spoolConsented.value = true
            spoolEnabled.value = true
            spoolUrls.value = setOf(TOKENED)
            roots += CommonsRoots("c-1", TOKENED, secret)

            val same = applier().preview(invite(TOKENED, secret))
            val sameRoom = same.room!!
            assertTrue(sameRoom.alreadyJoined)
            assertFalse(sameRoom.beside)
            assertTrue(same.isNoOp)
            applier().apply(same)
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
            // Not re-joined: the upsert would overwrite the name the relay advertised at join time.
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }

            // Two secrets at one spool are two rooms (C-7.4-18): the second joins and the first stays, history and all.
            val second = applier().preview(invite(TOKENED, otherSecret))
            val secondRoom = second.room!!
            assertFalse(secondRoom.alreadyJoined)
            assertTrue(secondRoom.beside)
            assertFalse(second.isNoOp)
            applier().apply(second)
            coVerifyOrder {
                commons.join(TOKENED, otherSecret, null, NOW)
                mesh.refreshRelays()
            }
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
            coVerify(exactly = 0) { commons.leave(any()) }
        }

    @Test
    fun aBuildWithNoCommonsStoreAppliesTheRelayAndIgnoresTheRoom() =
        runTest {
            val preview = applier(withCommons = false).preview(invite(TOKENED, secret, "Home"))
            assertNull(preview.room)
            applier(withCommons = false).apply(preview)
            coVerify { settings.addSpoolUrl(TOKENED) }
            coVerify { mesh.refreshRelays() }
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
        }

    @Test
    fun theRoomSecretIsCarriedIntact() =
        runTest {
            val preview = applier().preview(invite(TOKENED, secret))
            assertArrayEquals(secret, preview.room!!.secret)
        }

    @Test
    fun anInvitesKeyIsHeldForTheRelaysHostInTheSameConfirmation() =
        runTest {
            val preview = applier().preview(keyed())
            val key = preview.ticketKey!!
            assertEquals("home.example.org", key.host)
            assertEquals("anywhere.example.org", key.issuerHost)
            assertFalse(key.alreadyHeld)
            assertFalse(key.replacesKey)
            assertTrue(keys.held.value.isEmpty()) // nothing before the tap
            applier().apply(preview)
            assertEquals(HeldKey(ISSUER, KEY), keys.held.value["home.example.org"])
            coVerify { settings.addSpoolUrl(TOKENED) }
        }

    @Test
    fun theSameKeyAgainIsANoOpAndKeepsTheRefreshersState() =
        runTest {
            spoolEnabled.value = true
            spoolConsented.value = true
            spoolUrls.value = setOf(TOKENED)
            keys.putKey("home.example.org", HeldKey(ISSUER, KEY))
            keys.states.value = mapOf("home.example.org" to KeyState(KeyAnswer.OK, notBefore = 42))
            val preview = applier().preview(keyed())
            assertTrue(preview.ticketKey!!.alreadyHeld)
            assertTrue(preview.isNoOp)
            applier().apply(preview)
            assertEquals(KeyState(KeyAnswer.OK, notBefore = 42), keys.states.value["home.example.org"])
        }

    @Test
    fun aDifferentKeyForTheSameRelayReplacesTheHeldOne() =
        runTest {
            spoolEnabled.value = true
            spoolConsented.value = true
            spoolUrls.value = setOf(TOKENED)
            keys.putKey("home.example.org", HeldKey(ISSUER, OTHER_KEY))
            keys.states.value = mapOf("home.example.org" to KeyState(KeyAnswer.UNKNOWN_KEY))
            val preview = applier().preview(keyed())
            assertTrue(preview.ticketKey!!.replacesKey)
            assertFalse(preview.isNoOp)
            applier().apply(preview)
            assertEquals(HeldKey(ISSUER, KEY), keys.held.value["home.example.org"])
            assertNull(keys.states.value["home.example.org"]) // a fresh key lifts the unknown-key park
        }

    @Test
    fun anInviteWithoutAKeyNeverRemovesOne() =
        runTest {
            spoolUrls.value = setOf(TOKENED)
            keys.putKey("home.example.org", HeldKey(ISSUER, KEY))
            val preview = applier().preview(invite(TOKENED))
            assertNull(preview.ticketKey)
            applier().apply(preview)
            assertEquals(HeldKey(ISSUER, KEY), keys.held.value["home.example.org"])
        }

    @Test
    fun aBuildWithoutTicketsAppliesTheRelayAndIgnoresTheKey() =
        runTest {
            val preview = applier(withTickets = false).preview(keyed())
            assertNull(preview.ticketKey)
            applier(withTickets = false).apply(preview)
            coVerify { settings.addSpoolUrl(TOKENED) }
            assertTrue(keys.held.value.isEmpty())
        }

    @Test
    fun aKeyedPreviewNeverPrintsTheKey() =
        runTest {
            val key = applier().preview(keyed()).ticketKey!!
            assertFalse(key.toString().contains(KEY.removePrefix("kak1_").take(12)))
            assertFalse(keyed().toString().contains(KEY.removePrefix("kak1_").take(12)))
        }

    private companion object {
        const val ISSUER = "https://anywhere.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val NOW = 1_757_800_000_000L
        const val TOKENED = "wss://home.example.org/spool/v1?k=t0ken"
        const val UNTOKENED = "wss://home.example.org/spool/v1"
    }
}
