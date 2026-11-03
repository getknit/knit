package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.lora.LORA_DIRECT_OVERHEAD
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Protocol
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pair seal of a compact LoRa DM (ADR 2026-10.x9fa): a round trip, the refusals that keep the door shut — a wrong
 * key, a reflection, a stranger, a forgery, a stale or future stamp — and a fresh nonce for every seal of one frame.
 * The bytes themselves are pinned by `LoraDirectVectorTest`.
 */
class LoraDirectSealTest {
    /** One identity: its X25519 scalar and the node id a 16-byte bundle hash would give it (any canonical id will do). */
    private class Party(
        seed: Int,
    ) {
        val priv: ByteArray = X25519.generatePrivateKey()
        val pub: ByteArray = X25519.publicFromPrivate(priv)
        val nodeId: String = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it * 13 + seed).toByte() })
    }

    private val alice = Party(1)
    private val bob = Party(2)
    private val carol = Party(3)
    private val now = 1_800_000_000_000L

    /** [self]'s seal with every party in [pinned] indexed by its key. */
    private fun sealFor(
        self: Party,
        vararg pinned: Party,
    ): LoraDirectSeal =
        LoraDirectSeal(ownDhPrivate = { self.priv }, clock = { now }).apply {
            rebuild(self.nodeId, pinned.associate { it.nodeId to it.pub })
        }

    private val plaintext = "See you at the north gate".encodeToByteArray()

    @Test
    fun aSealOpensForItsRecipientWithTheSenderFromTheIndex() {
        val frameId = FrameId.new()
        val packet = checkNotNull(sealFor(alice, bob).seal(bob.nodeId, frameId, now, plaintext))

        assertEquals(LORA_DIRECT_TAG, packet[0])
        assertEquals("51 bytes around the plaintext", LORA_DIRECT_OVERHEAD + plaintext.size, packet.size)
        assertEquals(LORA_DIRECT_OVERHEAD - 16, LoraDirectSeal.HEADER_BYTES)
        val opened = sealFor(bob, alice, carol).open(packet) as LoraDirectOpen.Opened
        assertEquals(alice.nodeId, opened.direct.peerId)
        assertEquals(frameId, opened.direct.frameId)
        assertEquals(now, opened.direct.sentAtMs)
        assertArrayEquals(plaintext, opened.direct.plaintext)
    }

    @Test
    fun aPacketForSomeoneElseIsAStrangersAndAForgeryUnderTheRightTagFailsAuth() {
        val packet = checkNotNull(sealFor(alice, bob, carol).seal(bob.nodeId, FrameId.new(), now, plaintext))
        // Carol pins alice, but the tag is alice→bob's: no candidate.
        assertEquals(LoraDirectOpen.Stranger, sealFor(carol, alice, bob).open(packet))
        // Bob's index finds alice under the tag; any byte the AAD or the ciphertext covers then fails the AEAD.
        for (at in listOf(5, 13, 29, LoraDirectSeal.HEADER_BYTES, packet.size - 1)) {
            val forged = packet.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertEquals("byte $at", LoraDirectOpen.AuthFailed, sealFor(bob, alice).open(forged))
        }
    }

    @Test
    fun aWrongPairKeyNeverOpens() {
        // Alice seals under a pin of bob's key that bob does not hold (a stale pin, or an impostor's key under bob's id).
        val impostor = X25519.publicFromPrivate(X25519.generatePrivateKey())
        val seal =
            LoraDirectSeal(
                ownDhPrivate = { alice.priv },
                clock = { now },
            ).apply { rebuild(alice.nodeId, mapOf(bob.nodeId to impostor)) }
        val packet = checkNotNull(seal.seal(bob.nodeId, FrameId.new(), now, plaintext))
        assertFalse(sealFor(bob, alice).open(packet) is LoraDirectOpen.Opened)
    }

    @Test
    fun aPacketReflectedBackAtItsAuthorNeverOpens() {
        val packet = checkNotNull(sealFor(alice, bob).seal(bob.nodeId, FrameId.new(), now, plaintext))
        // B→A is its own key and its own tag: alice's index answers to bob's tag toward her, never her own toward him.
        assertEquals(LoraDirectOpen.Stranger, sealFor(alice, bob).open(packet))
    }

    @Test
    fun aStrangerIsAStrangerAndOurOwnIdIsNeverIndexed() {
        val packet = checkNotNull(sealFor(alice, bob).seal(bob.nodeId, FrameId.new(), now, plaintext))
        assertEquals("bob pins nobody", LoraDirectOpen.Stranger, sealFor(bob).open(packet))
        // A self pin (45763fb6's shape) is never a pair: nothing seals to ourselves.
        assertNull(sealFor(alice, alice, bob).seal(alice.nodeId, FrameId.new(), now, plaintext))
        assertEquals(LoraDirectOpen.Stranger, LoraDirectSeal(ownDhPrivate = { bob.priv }).open(packet))
    }

    @Test
    fun everySealOfOneFrameDrawsAFreshSaltAndNonce() {
        val seal = sealFor(alice, bob)
        val frameId = FrameId.new()
        val first = checkNotNull(seal.seal(bob.nodeId, frameId, now, plaintext))
        val second = checkNotNull(seal.seal(bob.nodeId, frameId, now, plaintext))
        val saltOf = { p: ByteArray -> p.copyOfRange(5, 13).toList() }
        assertFalse("a resend never reuses the salt", saltOf(first) == saltOf(second))
        assertFalse("nor the ciphertext", first.copyOfRange(35, first.size).contentEquals(second.copyOfRange(35, second.size)))
        val nonceKey = ByteArray(32) { it.toByte() }
        val id = checkNotNull(FrameId.toBytesOrNull(frameId))
        assertFalse(
            "the nonce follows the salt",
            LoraDirectSeal
                .nonce(
                    nonceKey,
                    id,
                    first.copyOfRange(5, 13),
                ).contentEquals(LoraDirectSeal.nonce(nonceKey, id, second.copyOfRange(5, 13))),
        )
        val bobSeal = sealFor(bob, alice)
        assertTrue(bobSeal.open(first) is LoraDirectOpen.Opened)
        assertTrue(bobSeal.open(second) is LoraDirectOpen.Opened)
    }

    @Test
    fun theSentAtWindowIsCustodysTtlBackAndTheSkewForward() {
        val seal = sealFor(alice, bob)
        val bobSeal = sealFor(bob, alice)

        fun at(stamp: Long) = bobSeal.open(checkNotNull(seal.seal(bob.nodeId, FrameId.new(), stamp, plaintext)))

        assertEquals(ForwardRepository.DEFAULT_TTL_MS, LoraDirectSeal.WINDOW_MS)
        assertTrue(at(now - LoraDirectSeal.WINDOW_MS) is LoraDirectOpen.Opened)
        assertEquals(LoraDirectOpen.ClockRefused, at(now - LoraDirectSeal.WINDOW_MS - 1))
        assertTrue(at(now + Protocol.MAX_FUTURE_SKEW_MS) is LoraDirectOpen.Opened)
        assertEquals(LoraDirectOpen.ClockRefused, at(now + Protocol.MAX_FUTURE_SKEW_MS + 1))
    }

    @Test
    fun aPeerLearnedSinceTheLastRebuildSealsAndOpensAtOnce() {
        val seal = sealFor(alice)
        assertNull(seal.seal(bob.nodeId, FrameId.new(), now, plaintext))
        seal.learn(bob.nodeId, bob.pub)
        val packet = checkNotNull(seal.seal(bob.nodeId, FrameId.new(), now, plaintext))
        assertTrue(sealFor(bob, alice).open(packet) is LoraDirectOpen.Opened)
        // A key that changed re-derives: a pin swapped for an impostor's no longer reaches bob.
        seal.learn(bob.nodeId, carol.pub)
        assertFalse(sealFor(bob, alice).open(checkNotNull(seal.seal(bob.nodeId, FrameId.new(), now, plaintext))) is LoraDirectOpen.Opened)
    }

    @Test
    fun malformedInputsAreRefusedBeforeAnyKeyIsTried() {
        val seal = sealFor(bob, alice)
        assertEquals(LoraDirectOpen.Malformed, seal.open(ByteArray(LORA_DIRECT_OVERHEAD - 1) { LORA_DIRECT_TAG }))
        assertEquals(LoraDirectOpen.Malformed, seal.open(ByteArray(LORA_DIRECT_OVERHEAD + 4)))
        val sender = sealFor(alice, bob)
        assertNull("a non-canonical frame id has no 16-byte form", sender.seal(bob.nodeId, "not-a-frame-id", now, plaintext))
        assertNull("a stamp the u48 cannot carry", sender.seal(bob.nodeId, FrameId.new(), -1L, plaintext))
        assertNotNull(sender.seal(bob.nodeId, FrameId.new(), (1L shl 48) - 1, plaintext))
    }

    @Test
    fun aLowOrderPeerKeyLeavesThatPeerOutAndNobodyElse() {
        // FSR §2.1: a signature proves a key was chosen, not that X25519 takes it; such a pin is skipped, never thrown on.
        val lowOrder = ByteArray(32)
        val seal =
            LoraDirectSeal(ownDhPrivate = { alice.priv }, clock = { now }).apply {
                rebuild(
                    alice.nodeId,
                    mapOf(
                        bob.nodeId to lowOrder,
                        carol.nodeId to carol.pub,
                    ),
                )
            }
        assertNull(seal.seal(bob.nodeId, FrameId.new(), now, plaintext))
        assertNotNull(seal.seal(carol.nodeId, FrameId.new(), now, plaintext))
    }
}
