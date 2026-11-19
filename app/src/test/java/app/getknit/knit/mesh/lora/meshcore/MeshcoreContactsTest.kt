package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.MeshcoreRank
import app.getknit.knit.mesh.lora.BoardAddr
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [MeshcoreContacts] (ADR 2026-11.gsqu, Stage 5b-2): which keys our MeshCore board is taught — each skip, the 6-byte prefix
 * contest (security B3), the ranking under the board's capacity, the daily bound and the write memo — and the Ready
 * reconcile's plan over the board's own listing, with its per-Ready bounds, and the record Knit writes.
 */
class MeshcoreContactsTest {
    private fun key(): ByteArray = Ed25519Sign.KeyPair.newKeyPair().publicKey

    private fun b64(key: ByteArray) = Base64.getEncoder().encodeToString(key)

    /** The same point negated: a valid key that differs only in the sign bit, so its first six bytes are the same. */
    private fun sharer(key: ByteArray) = key.copyOf().also { it[31] = (it[31].toInt() xor 0x80).toByte() }

    private fun holder(
        key: ByteArray,
        rank: MeshcoreRank = MeshcoreRank.ACCEPTED,
        contested: Boolean = false,
    ) = MeshcoreBoardHolder(b64(key), rank, latestProfileVersion = 1, contested = contested)

    private val board = BoardAddr.MeshCore(ByteArray(6) { 9 })

    // --- which keys ---

    @Test
    fun theBoardMapsHoldersAreAddressedByPrefixAndCarryTheKeyAndRank() {
        val bob = key()
        val holders = MeshcoreContacts.holdersOf(mapOf("bob" to holder(bob, MeshcoreRank.VERIFIED)))
        val h = holders.getValue("bob")
        assertEquals(MeshcorePins.addrOf(bob), h.addr)
        assertEquals(b64(bob), h.loraKey)
        assertTrue("the key is the door's claim", h.direct)
        assertTrue(h.contact)
        assertFalse(h.contested)
        assertFalse("never the key in a line", h.toString().contains(b64(bob).take(12)))
    }

    @Test
    fun twoKeysSharingAPrefixAreBothContestedAndNeitherIsTaught() {
        val bob = key()
        val mallory = sharer(bob)
        assertTrue(MeshcorePins.addrOf(bob) == MeshcorePins.addrOf(mallory))
        val holders = MeshcoreContacts.holdersOf(mapOf("bob" to holder(bob), "mallory" to holder(mallory), "carol" to holder(key())))
        assertTrue(holders.getValue("bob").contested && !holders.getValue("bob").direct)
        assertTrue(holders.getValue("mallory").contested && !holders.getValue("mallory").direct)
        val wanted = MeshcoreContacts().wanted(holders, ownKey = null, capacity = null, now = 0)
        assertEquals(listOf(holders.getValue("carol").loraKey), wanted.ranked.map(::b64))
        assertEquals(2, wanted.skipped[MeshcoreContacts.Skip.CONTESTED])
    }

    @Test
    fun aContestedKeyOurOwnKeyAndAKeylessHolderAreSkipped() {
        val own = key()
        val shared = key()
        val holders =
            MeshcoreContacts.holdersOf(
                mapOf("us" to holder(own), "bob" to holder(shared, contested = true), "carol" to holder(shared, contested = true)),
            ) + ("dave" to MeshcoreContacts.holdersOf(mapOf("x" to holder(key()))).getValue("x").copy(loraKey = "not a key"))
        val wanted = MeshcoreContacts().wanted(holders, ownKey = own, capacity = null, now = 0)
        assertTrue(wanted.ranked.isEmpty())
        assertEquals(1, wanted.skipped[MeshcoreContacts.Skip.OWN_KEY])
        assertEquals(2, wanted.skipped[MeshcoreContacts.Skip.CONTESTED])
        assertEquals(1, wanted.skipped[MeshcoreContacts.Skip.NO_KEY])
    }

    @Test
    fun aHolderThatIsNotAContactIsNeverTaught() {
        val bob = key()
        val stranger = MeshcoreContacts.holdersOf(mapOf("bob" to holder(bob))).getValue("bob").copy(contact = false)
        val wanted = MeshcoreContacts().wanted(mapOf("bob" to stranger), ownKey = null, capacity = null, now = 0)
        assertTrue(wanted.ranked.isEmpty())
        assertEquals(1, wanted.skipped[MeshcoreContacts.Skip.NOT_CONTACT])
    }

    @Test
    fun theTaughtSetIsRankedVerifiedAcceptedAuthoredAndCutToTheCapacity() {
        val a = key()
        val v = key()
        val w = key()
        val holders =
            MeshcoreContacts.holdersOf(
                mapOf(
                    "authored" to holder(a, MeshcoreRank.AUTHORED),
                    "verified" to holder(v, MeshcoreRank.VERIFIED),
                    "accepted" to holder(w, MeshcoreRank.ACCEPTED),
                ),
            )
        val all = MeshcoreContacts().wanted(holders, ownKey = null, capacity = null, now = 0)
        assertEquals(listOf(v, w, a).map(::b64), all.ranked.map(::b64))
        val cut = MeshcoreContacts().wanted(holders, ownKey = null, capacity = 2, now = 0)
        assertEquals(listOf(v, w).map(::b64), cut.ranked.map(::b64))
        assertEquals(1, cut.skipped[MeshcoreContacts.Skip.CAPACITY])
        assertFalse(cut.has(a))
    }

    @Test
    fun aPeerIsTaughtAtMostTwoDistinctKeysADay() {
        val policy = MeshcoreContacts()
        val keys = List(3) { key() }
        keys.forEachIndexed { i, k ->
            val wanted = policy.wanted(MeshcoreContacts.holdersOf(mapOf("bob" to holder(k))), null, null, now = i * 1_000L)
            if (i < 2) {
                assertEquals(listOf(b64(k)), wanted.ranked.map(::b64))
            } else {
                assertTrue("a third key in a day is not taught", wanted.ranked.isEmpty())
                assertEquals(1, wanted.skipped[MeshcoreContacts.Skip.KEY_CAP])
            }
        }
        val dayLater = policy.wanted(MeshcoreContacts.holdersOf(mapOf("bob" to holder(keys[2]))), null, null, now = 86_400_000L + 2_000)
        assertEquals(listOf(b64(keys[2])), dayLater.ranked.map(::b64))
    }

    @Test
    fun aReactiveTeachIsWrittenOncePerBoardAndKeyUntilADmToItIsAcked() {
        val policy = MeshcoreContacts()
        val bob = key()
        policy.wanted(MeshcoreContacts.holdersOf(mapOf("bob" to holder(bob))), null, null, 0)
        assertNull(policy.judgeTeach("bob", bob, board))
        assertEquals(
            "another peer's key is not theirs to teach",
            MeshcoreContacts.Refusal.NOT_WANTED,
            policy.judgeTeach("carol", bob, board),
        )
        assertEquals(MeshcoreContacts.Refusal.NOT_WANTED, policy.judgeTeach("bob", key(), board))
        policy.onWritten(board, bob)
        assertEquals(MeshcoreContacts.Refusal.WRITTEN, policy.judgeTeach("bob", bob, board))
        assertNull("another board of ours is its own memo", policy.judgeTeach("bob", bob, BoardAddr.MeshCore(ByteArray(6) { 1 })))
        policy.onDelivered(board, checkNotNull(MeshcorePins.addrOf(bob)))
        assertNull("acked since: one more write if the board lost it", policy.judgeTeach("bob", bob, board))
    }

    @Test
    fun aBlockedOrRemovedContactLeavesTheTaughtSet() {
        val policy = MeshcoreContacts()
        val bob = key()
        val carol = key()
        val before = policy.wanted(MeshcoreContacts.holdersOf(mapOf("bob" to holder(bob), "carol" to holder(carol))), null, null, 0)
        assertTrue(before.has(bob))
        // Blocked: the board map (DirectContacts' set) drops the row.
        val after = policy.wanted(MeshcoreContacts.holdersOf(mapOf("carol" to holder(carol))), null, null, 1)
        assertFalse(after.has(bob))
        assertEquals(MeshcoreContacts.Refusal.NOT_WANTED, policy.judgeTeach("bob", bob, board))
    }

    // --- the record ---

    @Test
    fun theRecordKnitWritesIsAChatFavouriteNamedFromTheKeyPinnedZeroHop() {
        val bob = key()
        val record = ContactRecord.knit(bob, MeshcoreNames.forKey(bob))
        assertTrue(MeshcoreContacts.isKnitRecord(record))
        val frame = MeshcoreProto.addUpdateContact(record, withPosition = false)
        assertEquals(MeshcoreProto.CMD_ADD_UPDATE_CONTACT, frame[0].toInt())
        assertArrayEquals(bob, frame.copyOfRange(1, 33))
        assertEquals("CHAT", MeshcoreProto.ADV_TYPE_CHAT, frame[33].toInt())
        assertEquals("favourite, no permissions", 0x01, frame[34].toInt())
        assertEquals("the zero-hop pin", 0, frame[35].toInt())
        assertEquals(MeshcoreNames.forKey(bob), MeshcoreProto.cBytes(frame, 100, 32).decodeToString())
        assertTrue(MeshcoreNames.forKey(bob).startsWith("Knit "))
        assertFalse("a routed copy is not Knit's record", MeshcoreContacts.isKnitRecord(foreign(bob, outPathLen = 2)))
        assertFalse("another name is not", MeshcoreContacts.isKnitRecord(foreign(bob, name = "Bob Smith")))
        // ADR 2026-11.f4d3: a peer's own advert names its contact; the routing is what the reconcile and a teach read.
        assertTrue(
            "renamed by the peer's advert, it still routes as Knit's",
            MeshcoreContacts.isKnitRouting(foreign(bob, name = "Bob Smith")),
        )
        assertFalse(MeshcoreContacts.isKnitRouting(foreign(bob, outPathLen = 2)))
        assertFalse(MeshcoreContacts.isKnitRouting(foreign(bob, flags = 0)))
    }

    private fun foreign(
        key: ByteArray,
        name: String = MeshcoreNames.forKey(key),
        outPathLen: Int = 0,
        flags: Int = MeshcoreProto.CONTACT_FLAG_FAVOURITE,
    ) = ContactRecord(
        key = key,
        type = MeshcoreProto.ADV_TYPE_CHAT,
        flags = flags,
        outPathLen = outPathLen,
        outPath = ByteArray(MeshcoreProto.PATH_FIELD),
        nameField = name.encodeToByteArray().copyOf(MeshcoreProto.NAME_FIELD),
        lastAdvert = 0,
        position = ByteArray(8),
    )

    // --- the reconcile's plan ---

    @Test
    fun theReconcileRemovesTheUntaughtOverwritesTheChangedAndTeachesTheMissing() {
        val kept = key()
        val changed = key()
        val missing = key()
        val stranger = key()
        val renamed = key()
        val listing =
            listOf(
                ContactRecord.knit(kept, MeshcoreNames.forKey(kept)),
                foreign(changed, outPathLen = 2),
                foreign(stranger),
                // A taught peer whose own board carries a handle: its advert renamed the contact (lora N4).
                foreign(renamed, name = "Wren"),
            )
        val plan = MeshcoreContacts.plan(listOf(kept, changed, missing, renamed), listing, listedCount = 4, capacity = 10)
        assertEquals(listOf(b64(stranger)), plan.removals.map(::b64))
        assertEquals("only a routing change is rewritten", listOf(b64(changed)), plan.overwrites.map(::b64))
        assertEquals(listOf(b64(missing)), plan.teaches.map(::b64))
        assertEquals(
            "a renamed taught contact is verified, no flash written (ADR 2026-11.f4d3)",
            listOf(b64(kept), b64(renamed)),
            plan.verified.map(::b64),
        )
        assertTrue(plan.contested.isEmpty())
        assertEquals(0, plan.deferred)
    }

    @Test
    fun theReconcileIsBoundedPerReadySoASecondClientCannotChurnTheFlash() {
        val taught = List(4) { key() }
        val strangers = List(40) { key() }
        val listing = strangers.map { foreign(it) } + taught.map { foreign(it, outPathLen = 3) }
        val plan = MeshcoreContacts.plan(taught, listing, listing.size, capacity = 100, maxRemovals = 16, maxOverwrites = 2)
        assertEquals(16, plan.removals.size)
        assertEquals(2, plan.overwrites.size)
        assertEquals("24 removals and 2 overwrites wait for the next Ready", 26, plan.deferred)
        val defaults = MeshcoreContacts.plan(taught, listing, listing.size, capacity = 100)
        assertEquals(MeshcoreContacts.MAX_REMOVALS_PER_READY, defaults.removals.size)
    }

    @Test
    fun theReconcileTeachesOnlyIntoTheRoomTheRemovalsLeaveRankedFirst() {
        val taught = List(5) { key() }
        val strangers = List(3) { key() }
        val plan = MeshcoreContacts.plan(taught, strangers.map { foreign(it) }, listedCount = 4, capacity = 3, maxRemovals = 2)
        // Capacity 3 keeps the top three; the board counts 4, two removals leave 2 occupied, so one fits.
        assertEquals(listOf(b64(taught[0])), plan.teaches.map(::b64))
    }

    @Test
    fun aTaughtKeyWhosePrefixAListedContactSharesWaitsForItsRemovalOrIsContested() {
        val bob = key()
        val mallory = sharer(bob)
        val carol = key()
        val cuckoo = sharer(carol)
        val listing = listOf(foreign(mallory, name = "Mallory"), foreign(key()), foreign(cuckoo))
        val plan = MeshcoreContacts.plan(listOf(bob, carol), listing, listing.size, capacity = 10, maxRemovals = 2)
        assertTrue("bob waits for mallory's removal", plan.blockedBy.keys.any { it.array().contentEquals(bob) })
        assertTrue(plan.teaches.any { it.contentEquals(bob) })
        assertEquals("cuckoo's removal is deferred, so carol is contested", listOf(b64(carol)), plan.contested.map(::b64))
        assertFalse(plan.teaches.any { it.contentEquals(carol) })
    }
}
