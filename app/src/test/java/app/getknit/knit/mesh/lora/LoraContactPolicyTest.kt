package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.b64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether our board is taught a pinned contact's key after a `39`, and what the `add_contact` carries (ADR 2026-10.aajj):
 * every guard against writing a key the profile does not bind, the board's own key winning, the memory that bounds the
 * writes, and a `User` that never carries a peer's name or flags.
 */
class LoraContactPolicyTest {
    private val own = 0x0abc1234u

    private class Board(
        val node: UInt,
        val key: ByteArray,
    ) {
        val b64: String get() = b64(key)
    }

    private fun board(seed: Int): Board {
        val key = ByteArray(MeshtasticProto.NODE_PUBLIC_KEY_BYTES) { (seed * 17 + it).toByte() }
        return Board(LoraContactPolicy.crc32(key), key)
    }

    private fun holder(
        board: Board,
        key: String? = board.b64,
        contact: Boolean = true,
        contested: Boolean = false,
        node: UInt = board.node,
    ) = LoraBoardHolder(BoardAddr(node), direct = true, loraKey = key, contact = contact, contested = contested)

    /** [judge] with the refused send issued at stamp [SENT], and [user] streamed after it ([raced]) or before. */
    private fun LoraContactPolicy.judgeAt(
        peer: String,
        h: LoraBoardHolder,
        own: UInt,
        user: BoardOwnerRaw?,
        now: Long,
        raced: Boolean = true,
    ) = judge(peer, h, own, user?.let { StreamedUser(it, if (raced) SENT + 1 else SENT - 1) }, SENT, now)

    private fun refusal(verdict: LoraContactPolicy.Verdict): LoraContactPolicy.Refusal? =
        (verdict as? LoraContactPolicy.Verdict.Refused)?.reason

    @Test
    fun theNodeNumberIsTheFirmwaresCrc32OfTheKey() {
        // ErriezCRC32 is the IEEE CRC-32; its check value over "123456789" is 0xCBF43926.
        assertEquals(0xCBF43926u, LoraContactPolicy.crc32("123456789".encodeToByteArray()))
    }

    @Test
    fun aContactsBoundKeyIsWrittenAsTheBoardWouldNameAKnitBoard() {
        val bob = board(1)
        val verdict = LoraContactPolicy().judgeAt("bob", holder(bob), own, null, now = 0)
        val write = verdict as LoraContactPolicy.Verdict.Write
        assertEquals(bob.node, write.node)
        assertEquals(bob.b64, write.key)
        val contact = checkNotNull(readBytesField(write.admin, 66))
        assertEquals(bob.node.toLong(), readVarintField(contact, 1))
        assertNull("never should_ignore", readVarintField(contact, 3))
        assertNull("never manually_verified", readVarintField(contact, 4))
        assertNull("no session passkey on a local admin message", readBytesField(write.admin, 101))
        val user = BoardOwnerRaw(checkNotNull(readBytesField(contact, 2)))
        assertEquals("!%08x".format(bob.node.toLong()), readStringField(user.raw, MeshtasticProto.USER_ID))
        assertEquals("Knit ${BoardName.suffix(bob.node)}", user.owner.longName)
        assertEquals("Knit", user.owner.shortName)
        assertTrue("unmonitored, as a Knit board says of itself", user.owner.unmessagable)
        assertEquals(bob.b64, user.owner.publicKey)
        assertNull("no hw_model", readVarintField(user.raw, HW_MODEL))
        assertNull("no role", readVarintField(user.raw, ROLE))
        assertNull("no is_licensed", readVarintField(user.raw, MeshtasticProto.USER_IS_LICENSED))
    }

    @Test
    fun nothingIsWrittenThatTheProfileDoesNotBindOrTheBoardAlreadyAnswers() {
        val bob = board(1)
        val p = LoraContactPolicy()

        fun judge(
            h: LoraBoardHolder,
            user: BoardOwnerRaw? = null,
        ) = refusal(p.judgeAt("bob", h, own, user, now = 0))
        assertEquals(LoraContactPolicy.Refusal.NOT_CONTACT, judge(holder(bob, contact = false)))
        assertEquals(LoraContactPolicy.Refusal.CONTESTED, judge(holder(bob, contested = true)))
        assertEquals(LoraContactPolicy.Refusal.OWN_BOARD, judge(holder(bob, node = own)))
        assertEquals(LoraContactPolicy.Refusal.NOT_A_BOARD, judge(holder(bob, node = 0u)))
        assertEquals(LoraContactPolicy.Refusal.NOT_A_BOARD, judge(holder(bob, node = MeshtasticProto.BROADCAST)))
        assertEquals(LoraContactPolicy.Refusal.NO_PROFILE_KEY, judge(holder(bob, key = null)))
        assertEquals(LoraContactPolicy.Refusal.NO_PROFILE_KEY, judge(holder(bob, key = b64(ByteArray(31)))))
        assertEquals(LoraContactPolicy.Refusal.NO_PROFILE_KEY, judge(holder(bob, key = "not base64!")))
        assertEquals(
            "a third board's number claimed beside one's own key",
            LoraContactPolicy.Refusal.CRC_MISMATCH,
            judge(holder(bob, node = board(2).node)),
        )
        val licensed =
            ProtoWriter()
                .string(
                    MeshtasticProto.USER_LONG_NAME,
                    "KD0XYZ",
                ).varint(MeshtasticProto.USER_IS_LICENSED, 1)
                .toByteArray()
        assertEquals(LoraContactPolicy.Refusal.LICENSED, judge(holder(bob), BoardOwnerRaw(licensed)))
        assertEquals(LoraContactPolicy.Refusal.BOARD_HOLDS_KEY, judge(holder(bob), userWithKey(bob.key)))
        assertEquals("the board's key wins", LoraContactPolicy.Refusal.BOARD_KEY_CONFLICT, judge(holder(bob), userWithKey(board(3).key)))
    }

    @Test
    fun oneWritePerBoardAndKeyUntilADmReachedThatBoard() {
        val bob = board(1)
        val p = LoraContactPolicy()
        val write = p.judgeAt("bob", holder(bob), own, null, now = 0) as LoraContactPolicy.Verdict.Write
        p.onWritten("bob", own, write.node, write.key, now = 0)
        assertEquals(LoraContactPolicy.Refusal.WRITTEN, refusal(p.judgeAt("bob", holder(bob), own, null, now = 1)))
        assertTrue(
            "another board of ours is another write",
            p.judgeAt("bob", holder(bob), own + 1u, null, now = 1) is LoraContactPolicy.Verdict.Write,
        )
        p.onDelivered(own, bob.node)
        assertTrue("one more once a DM reached it", p.judgeAt("bob", holder(bob), own, null, now = 2) is LoraContactPolicy.Verdict.Write)
        p.onWritten("bob", own, write.node, write.key, now = 2)
        assertEquals("and then one only", LoraContactPolicy.Refusal.WRITTEN, refusal(p.judgeAt("bob", holder(bob), own, null, now = 3)))
    }

    @Test
    fun aChangedKeyIsANewWriteUpToTheDailyBound() {
        val p = LoraContactPolicy()
        val keys = (1..3).map(::board)
        keys.take(LoraContactPolicy.MAX_KEYS_PER_DAY).forEachIndexed { i, b ->
            val write = p.judgeAt("bob", holder(b), own, null, now = i.toLong()) as LoraContactPolicy.Verdict.Write
            p.onWritten("bob", own, write.node, write.key, now = i.toLong())
        }
        val third = keys.last()
        assertEquals(LoraContactPolicy.Refusal.KEY_CAP, refusal(p.judgeAt("bob", holder(third), own, null, now = 10)))
        assertTrue(
            "another peer has its own bound",
            p.judgeAt("carol", holder(third), own, null, now = 10) is LoraContactPolicy.Verdict.Write,
        )
        assertTrue(
            "a day on, it lapses",
            p.judgeAt("bob", holder(third), own, null, now = LoraContactPolicy.DAY_MS + 1) is LoraContactPolicy.Verdict.Write,
        )
    }

    @Test
    fun theWrittenUserIsAnAllowlistWithTheStoredNames() {
        val bob = board(1)
        val stored =
            ProtoWriter()
                .string(MeshtasticProto.USER_ID, "!%08x".format(bob.node.toLong()))
                .string(MeshtasticProto.USER_LONG_NAME, "Bob's T-Beam")
                .string(MeshtasticProto.USER_SHORT_NAME, "BOB")
                .varint(HW_MODEL, 4)
                .varint(ROLE, 1)
                .varint(MeshtasticProto.USER_IS_UNMESSAGABLE, 1)
                .bytes(UNKNOWN_FIELD, byteArrayOf(1, 2, 3))
                .toByteArray()
        val user = BoardOwnerRaw(LoraContactPolicy.contactUser(bob.node, bob.key, BoardOwnerRaw(stored).owner))
        assertEquals("Bob's T-Beam", user.owner.longName)
        assertEquals("BOB", user.owner.shortName)
        assertArrayEquals(bob.key, readBytesField(user.raw, MeshtasticProto.USER_PUBLIC_KEY))
        assertNull("never hw_model off the air", readVarintField(user.raw, HW_MODEL))
        assertNull("never role", readVarintField(user.raw, ROLE))
        assertNull("is_unmessagable only in the Knit fallback", readVarintField(user.raw, MeshtasticProto.USER_IS_UNMESSAGABLE))
        assertNull("never an unknown field", readBytesField(user.raw, UNKNOWN_FIELD))
    }

    @Test
    fun namesAreNeverBlank() {
        val bob = board(1)
        val user = BoardOwnerRaw(LoraContactPolicy.contactUser(bob.node, bob.key, BoardOwner("", "")))
        assertEquals("Knit ${BoardName.suffix(bob.node)}", user.owner.longName)
        assertEquals("Knit", user.owner.shortName)
        assertTrue(user.owner.unmessagable)
        val longOnly = BoardOwnerRaw(LoraContactPolicy.contactUser(bob.node, bob.key, BoardOwner("Bob's T-Beam", "")))
        assertEquals("Bob's T-Beam", longOnly.owner.longName)
        assertEquals("a blank short name is filled on its own", "Knit", longOnly.owner.shortName)
    }

    /**
     * The 39 is the board's word: a key, or ham mode, the stream showed before the send was issued is stale or anybody's
     * and is set aside; only one streamed after it — the NodeInfo that raced the 39 — stops the write.
     */
    @Test
    fun onlyAKeyStreamedAfterTheSendStopsTheWrite() {
        val bob = board(1)
        val p = LoraContactPolicy()

        fun verdict(
            user: BoardOwnerRaw,
            raced: Boolean,
        ) = p.judgeAt("bob", holder(bob), own, user, now = 0, raced = raced)
        assertTrue("stale, its own key", verdict(userWithKey(bob.key), raced = false) is LoraContactPolicy.Verdict.Write)
        assertTrue("stale or forged, another key", verdict(userWithKey(board(3).key), raced = false) is LoraContactPolicy.Verdict.Write)
        val licensed =
            BoardOwnerRaw(
                ProtoWriter().string(MeshtasticProto.USER_LONG_NAME, "KD0XYZ").varint(MeshtasticProto.USER_IS_LICENSED, 1).toByteArray(),
            )
        assertTrue("a stale or forged ham mode", verdict(licensed, raced = false) is LoraContactPolicy.Verdict.Write)
        assertEquals(LoraContactPolicy.Refusal.BOARD_KEY_CONFLICT, refusal(verdict(userWithKey(board(3).key), raced = true)))
        assertEquals(LoraContactPolicy.Refusal.BOARD_HOLDS_KEY, refusal(verdict(userWithKey(bob.key), raced = true)))
        assertEquals(LoraContactPolicy.Refusal.LICENSED, refusal(verdict(licensed, raced = true)))
    }

    @Test
    fun aTimedOutWriteIsNotRecordedButIsNeverRepeatedUntilADmLands() {
        val bob = board(1)
        val p = LoraContactPolicy()
        val write = p.judgeAt("bob", holder(bob), own, null, now = 0) as LoraContactPolicy.Verdict.Write
        p.onTimedOut(own, write.node, write.key)
        assertEquals(LoraContactPolicy.Refusal.WRITTEN, refusal(p.judgeAt("bob", holder(bob), own, null, now = 1)))
        p.onDelivered(own, bob.node)
        assertTrue(p.judgeAt("bob", holder(bob), own, null, now = 2) is LoraContactPolicy.Verdict.Write)
    }

    @Test
    fun aNameIsCutOnACodePointAtTheFirmwaresSize() {
        assertEquals("Knit", LoraContactPolicy.fitUtf8("Knit", LoraContactPolicy.SHORT_NAME_BYTES))
        assertEquals("ab", LoraContactPolicy.fitUtf8("ab😀", 4))
        assertEquals(
            "never half a character",
            38,
            LoraSizeHint.utf8Length(LoraContactPolicy.fitUtf8("é".repeat(40), LoraContactPolicy.LONG_NAME_BYTES)),
        )
    }

    private fun userWithKey(key: ByteArray) =
        BoardOwnerRaw(
            ProtoWriter()
                .string(
                    MeshtasticProto.USER_LONG_NAME,
                    "Meshtastic 1234",
                ).bytes(MeshtasticProto.USER_PUBLIC_KEY, key)
                .toByteArray(),
        )

    private companion object {
        /** `User.hw_model` and `User.role` (`mesh.proto`): fields Knit never writes from a peer. */
        const val HW_MODEL = 5
        const val ROLE = 7

        /** A field this codec does not know. */
        const val UNKNOWN_FIELD = 99

        /** The stream's stamp when the refused send was issued. */
        const val SENT = 10L
    }
}
