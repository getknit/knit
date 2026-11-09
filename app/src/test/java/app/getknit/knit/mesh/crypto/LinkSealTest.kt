package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [LinkSeal] on plain JVM (#286): the key schedule both ends derive, the frame layout, the implicit counter, and every
 * way a frame or a key share must fail. The schedule and the frame are each checked against an independent build from
 * Tink's HKDF and JCA's AES-GCM, so a drift in [LinkSeal] fails here rather than being pinned. The vectors
 * (`keyed-v1.json` `link.seal`) pin the bytes; this pins the rules.
 */
class LinkSealTest {
    private val aliceScalar = ByteArray(32) { (0x40 + it).toByte() }
    private val bobScalar = ByteArray(32) { (0x60 + it).toByte() }

    private fun pair(): Pair<LinkSeal.Keys, LinkSeal.Keys> {
        val a = LinkSeal.Ephemeral.fixed(aliceScalar)
        val b = LinkSeal.Ephemeral.fixed(bobScalar)
        return LinkSeal.derive(LinkProof.Role.DIALER, a, b.pub) to LinkSeal.derive(LinkProof.Role.RESPONDER, b, a.pub)
    }

    @Test
    fun bothEndsDeriveTheSameKeysEachSealingWithItsOwnDirection() {
        val (d, r) = pair()
        assertArrayEquals(d.send, r.recv)
        assertArrayEquals(d.recv, r.send)
        assertFalse(d.send.contentEquals(d.recv))
        assertArrayEquals(d.binding.eD, r.binding.eD)
        assertArrayEquals(d.binding.eR, r.binding.eR)
        assertArrayEquals(X25519.publicFromPrivate(aliceScalar), d.binding.eD)
    }

    @Test
    fun theScheduleIsHkdfOverTheSharedSecretSaltedByBothKeySharesDialerFirst() {
        val eD = X25519.publicFromPrivate(aliceScalar)
        val eR = X25519.publicFromPrivate(bobScalar)
        val ss = X25519.computeSharedSecret(aliceScalar, eR)
        val (d, _) = pair()
        val d2r = Hkdf.computeHkdf("HMACSHA256", ss, eD + eR, "knit/link/v1/seal/d2r".toByteArray(), 32)
        val r2d = Hkdf.computeHkdf("HMACSHA256", ss, eD + eR, "knit/link/v1/seal/r2d".toByteArray(), 32)
        assertArrayEquals(d2r, d.send)
        assertArrayEquals(r2d, d.recv)
        assertEquals(21, LinkSeal.LABEL_D2R.length)
        assertEquals(21, LinkSeal.LABEL_R2D.length)
    }

    @Test
    fun anEphemeralDerivesOnce() {
        val a = LinkSeal.Ephemeral.fixed(aliceScalar)
        val peer = LinkSeal.Ephemeral.fixed(bobScalar).pub
        LinkSeal.derive(LinkProof.Role.DIALER, a, peer)
        assertThrows(IllegalStateException::class.java) { LinkSeal.derive(LinkProof.Role.DIALER, a, peer) }
    }

    @Test
    fun freshEphemeralsDiffer() {
        assertFalse(
            LinkSeal.Ephemeral
                .fresh()
                .pub
                .contentEquals(LinkSeal.Ephemeral.fresh().pub),
        )
    }

    @Test
    fun aLowOrderOrWrongLengthKeyShareIsRefusedAsASeal() {
        val lowOrder = listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 })
        for (bad in lowOrder + listOf(ByteArray(31), ByteArray(33))) {
            assertThrows(SealException::class.java) {
                LinkSeal.derive(LinkProof.Role.RESPONDER, LinkSeal.Ephemeral.fixed(bobScalar), bad)
            }
        }
    }

    @Test
    fun aFrameIsTheHeaderThenAesGcmUnderTheCounterNonceWithTheHeaderAsAad() {
        val (d, _) = pair()
        val plain = "hello".toByteArray()
        val frame = LinkSeal.seal(d.send, 7, plain)
        val header = byteArrayOf(0x0B, 0, 0, 0, (plain.size + 16).toByte())
        assertArrayEquals(header, frame.copyOf(5))
        val nonce = ByteArray(12).also { it[11] = 7 }
        val jca =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(d.send, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
                doFinal(plain)
            }
        assertArrayEquals(header + jca, frame)
    }

    @Test
    fun aFrameOpensOnlyAtItsCounterUnderTheOtherEndsKey() {
        val (d, r) = pair()
        val plain = ByteArray(1000) { it.toByte() }
        val frame = LinkSeal.seal(d.send, 3, plain)
        assertArrayEquals(plain, LinkSeal.open(r.recv, 3, frame))
        for (counter in listOf(0L, 2L, 4L)) {
            assertThrows("counter $counter", SealException::class.java) { LinkSeal.open(r.recv, counter, frame) }
        }
        // Its own direction's key, or the responder's sending key, opens nothing.
        assertThrows(SealException::class.java) { LinkSeal.open(r.send, 3, frame) }
    }

    @Test
    fun aFlippedBitAnywhereInTheFrameFailsToOpen() {
        val (d, r) = pair()
        val frame = LinkSeal.seal(d.send, 0, byteArrayOf(1, 2, 3))
        for (i in frame.indices) {
            val flipped = frame.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows("byte $i", SealException::class.java) { LinkSeal.open(r.recv, 0, flipped) }
        }
    }

    @Test
    fun aFrameHoldsOneTo65536BytesAndAnEmptyOrOversizeOneIsRefused() {
        val (d, r) = pair()
        val full = ByteArray(LinkSeal.MAX_PLAINTEXT_BYTES) { it.toByte() }
        assertArrayEquals(full, LinkSeal.open(r.recv, 0, LinkSeal.seal(d.send, 0, full)))
        assertThrows(IllegalArgumentException::class.java) { LinkSeal.seal(d.send, 0, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { LinkSeal.seal(d.send, 0, ByteArray(LinkSeal.MAX_PLAINTEXT_BYTES + 1)) }
        // An empty frame (length 16) and an oversize one (65,553) are refused from the header alone.
        val empty = byteArrayOf(0x0B, 0, 0, 0, 16) + ByteArray(16)
        assertThrows(SealException::class.java) { LinkSeal.open(r.recv, 0, empty) }
        assertThrows(SealException::class.java) { LinkSeal.checkHeader(byteArrayOf(0x0B, 0, 1, 0, 0x11)) }
        assertEquals(65_552, LinkSeal.checkHeader(byteArrayOf(0x0B, 0, 1, 0, 0x10)))
        assertThrows(SealException::class.java) { LinkSeal.checkHeader(byteArrayOf(0x0A, 0, 0, 0, 0x20)) }
    }

    @Test
    fun counter2To48IsNeverSealedNorOpened() {
        val (d, r) = pair()
        val last = LinkSeal.MAX_COUNTER - 1
        assertArrayEquals(byteArrayOf(9), LinkSeal.open(r.recv, last, LinkSeal.seal(d.send, last, byteArrayOf(9))))
        assertThrows(SealException::class.java) { LinkSeal.seal(d.send, LinkSeal.MAX_COUNTER, byteArrayOf(9)) }
        assertThrows(SealException::class.java) { LinkSeal.seal(d.send, -1, byteArrayOf(9)) }
        assertEquals(1L shl 48, LinkSeal.MAX_COUNTER)
    }

    @Test
    fun aSealExceptionNamesTheFaultAndNoBytes() {
        val (d, r) = pair()
        val frame = LinkSeal.seal(d.send, 0, "secret plaintext".toByteArray())
        val e = assertThrows(SealException::class.java) { LinkSeal.open(r.recv, 1, frame) }
        val message = e.message.orEmpty()
        assertFalse(message.contains("secret"))
        assertTrue(message.length < 40)
    }
}
