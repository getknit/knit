package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side check of a Meshtastic 2.8 broadcast signature, pinned to the lab boards' real keys.
 *
 * Two of the vectors are not fabricated. [ON_AIR_*] is a frame the Heltec `!64761b18` transmitted, heard by
 * the USB board `!e681a7c3` and read off its phone API on 2026-09-05 with the signature and the board's
 * own `xeddsa_signed = true` verdict intact; it verified off-board under OpenSSL before it verified here.
 * [LAB_CURVE_PUB] is `!e681a7c3`'s own Curve25519 key, and its Ed25519 image is the one whose sign bit the
 * signer had to negate away — the branch a made-up key might never exercise.
 */
class XeddsaVerifyTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- vector (a): the key conversion, on the lab board's real key ---
    private val labCurvePub = hex("ed6b64c3dd70eb86a192677c1124ac0ad439a129e3cba94c47e7e27ace503e5b")
    private val labEdPub = hex("bc82eb9c9b9b5a094899f52de16cba730af9b7a8f8498e360948ea4eb9445a38")

    // --- vector (b): a real packet off the air (PRIVATE_APP, a Knit frame under the signature cliff) ---
    private val onAirKey = b64d("oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=")
    private val onAirFrom = 1685461784u // !64761b18 — and CRC32 of the key above, as 2.8 numbers are
    private val onAirId = 468855968u
    private val onAirPort = 256
    private val onAirPayload =
        hex(
            "0400261284cbe5f7f0c36e650601071907ff08a3010202582053bbb70841abd25a52209492417e553cca3b0969f26f1bca4fab3b" +
                "e5e4c65f670358400001f097e33b78bb90c1deef7dd9d51c8739e2a91cb3a8b7e186f91614647687fc81f7989a13fad3b87b1538" +
                "860e7563ea21a67c340f06fe906171134bd3fa00094601a06e950444",
        )
    private val onAirSig =
        hex(
            "d94bef2bf5cb3167b7b8f3b7f74d3d1710631a4fa22cdafccc7d62ccc7e8dc51" +
                "eefca375856e549de20aef6cba1c44f40e25caad1c41183b9d8f02219e341103",
        )

    // --- vector (c): a TEXT_MESSAGE_APP post signed under the lab board's key — the Meshtastic room's shape ---
    private val textFrom = 0xe681a7c3u
    private val textId = 0x1234abcdu
    private val textPayload = "hello from Knit a7c3".encodeToByteArray()
    private val textSig =
        hex(
            "fbda52354547235b7400409ac34bdfbaea6a9e5d89cae1134e92efaefbe8179b" +
                "2d581fc366e0af43f04e7228096e0b5b0a6f02da87c2721d8f9a79c31975760d",
        )

    @Test
    fun theBoardsCurveKeyConvertsToTheEdKeyItSignsWith() {
        // `y = (u − 1)/(u + 1)`, sign bit clear — CryptoEngine::curve_to_ed_pub, and what XEdDSA's key
        // derivation lands on after negating the scalar for this particular key.
        assertArrayEquals(labEdPub, XeddsaVerify.edPublicKey(labCurvePub))
        assertTrue("the image has its sign bit clear", (labEdPub[31].toInt() and 0x80) == 0)
    }

    @Test
    fun aSignatureTheBoardMadeOnTheAirVerifies() {
        assertTrue(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
    }

    @Test
    fun aTextPostSignedByTheLabBoardVerifies() {
        assertTrue(XeddsaVerify.verify(labCurvePub, textFrom, textId, 1, textPayload, textSig))
    }

    @Test
    fun theSigningInputIsTheFirmwaresLittleEndianHeaderThenThePayload() {
        // CryptoEngine::buildSigningBuffer: [fromNode(4) | packetId(4) | portnum(4) | payload(N)], LE.
        val input = XeddsaVerify.signingInput(0xe681a7c3u, 0x1234abcdu, 256, byteArrayOf(0x41))
        assertArrayEquals(hex("c3a781e6cdab341200010000" + "41"), input)
    }

    @Test
    fun aSignatureRejectsAnotherSenderPacketIdOrPort() {
        assertFalse("from", XeddsaVerify.verify(onAirKey, onAirFrom + 1u, onAirId, onAirPort, onAirPayload, onAirSig))
        assertFalse("id", XeddsaVerify.verify(onAirKey, onAirFrom, onAirId + 1u, onAirPort, onAirPayload, onAirSig))
        assertFalse("portnum", XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, 1, onAirPayload, onAirSig))
    }

    @Test
    fun aSignatureRejectsATamperedPayload() {
        val flipped = onAirPayload.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        assertFalse(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, flipped, onAirSig))
        assertFalse(
            "a byte short",
            XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload.copyOf(onAirPayload.size - 1), onAirSig),
        )
    }

    @Test
    fun aSignatureRejectsAnotherBoardsKey() {
        // The lab board's own key is a real, valid key — just not the one that signed this packet.
        assertFalse(XeddsaVerify.verify(labCurvePub, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
    }

    @Test
    fun aSixtyThreeByteSignatureIsRefused() {
        assertFalse(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig.copyOf(63)))
        assertFalse(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig + byteArrayOf(0)))
    }

    @Test
    fun aNullSignatureIsFalseNotAnError() {
        assertFalse(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload, null))
    }

    @Test
    fun aGarbageKeyIsRefusedNotThrown() {
        assertNull("wrong length", XeddsaVerify.edPublicKey(ByteArray(31)))
        assertNull("wrong length", XeddsaVerify.edPublicKey(ByteArray(33)))
        // u = p − 1 is the one field element with no (u + 1) inverse.
        val pMinusOne = hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")
        assertNull("no inverse", XeddsaVerify.edPublicKey(pMinusOne))
        assertFalse(XeddsaVerify.verify(pMinusOne, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
        assertFalse(XeddsaVerify.verify(ByteArray(31), onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
        // A random 32 bytes is (almost always) not on the curve; whatever Tink makes of it, the answer is false.
        assertFalse(XeddsaVerify.verify(ByteArray(32) { (it * 37 + 11).toByte() }, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
    }

    @Test
    fun theAllZeroKeyVerifiesNothing() {
        assertFalse(XeddsaVerify.verify(ByteArray(32), onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig))
        assertFalse(XeddsaVerify.verify(ByteArray(32), textFrom, textId, 1, textPayload, textSig))
    }

    @Test
    fun theTopBitOfTheCurveKeyIsMaskedLikeTheFirmwareDoes() {
        // RFC 7748 and fe_frombytes both ignore bit 255 of a Curve25519 key, so a key with it set is the
        // same key — and a signature under it still verifies.
        val withTopBit = labCurvePub.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
        assertArrayEquals(labEdPub, XeddsaVerify.edPublicKey(withTopBit))
        assertTrue(XeddsaVerify.verify(withTopBit, textFrom, textId, 1, textPayload, textSig))
    }
}
