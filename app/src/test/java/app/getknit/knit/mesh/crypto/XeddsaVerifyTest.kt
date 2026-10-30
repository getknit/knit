package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.crypto.XeddsaVerify.SignedFields
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side check of a Meshtastic broadcast signature, in firmware 2.8.1's layout — the only one Knit accepts
 * since Meshtastic withdrew 2.8.0 (ADR 2026-09.ggq4, amendment 2026-10-29; the user's decision of 2026-10-30).
 *
 * The 2.8.1 vectors (`roomPost`, `tapback`, `wantResponse`, `noBitfield`, `mergedWantResponse`) were signed by
 * the firmware's **own code** at tag `v2.8.1.8e6a88d` (issue #242): `buildSigningBuffer` and `putLE32` taken
 * verbatim from `src/mesh/CryptoEngine.cpp`, the constants from `CryptoEngine.h`, and the `meshtastic/Crypto`
 * library's `XEdDSA` from the heltec-v4 build's `.pio/libdeps`, compiled natively. Each verified under the
 * firmware's own `XEdDSA::verify` and, independently, under OpenSSL with the layout rebuilt from the fields. The
 * key is a fixed test key tied to no device, clamped as `Curve25519::dh1` leaves a board's, and the hedge Z is
 * fixed so the vectors reproduce. Their `from` (`0x28f1a7c3`) is **not** CRC32 of that key, as a real 2.8 node
 * number would be; nothing here depends on it. They are sign-side only: the end-to-end oracle is an on-air 2.8.1
 * packet read through `MeshtasticProto`, owed by the board trial.
 *
 * Two older vectors are real and stay as pins. `labCurvePub` is the lab board `!e681a7c3`'s own Curve25519 key,
 * whose Ed25519 image is the one whose sign bit the signer had to negate away — the key conversion does not depend
 * on the layout. `onAir*` is a frame the Heltec `!64761b18` transmitted on 2.8.0 and the USB board read off its phone
 * API on 2026-09-05 with the board's `xeddsa_signed = true` intact: a genuine 2.8.0 signature, which must **no
 * longer** verify.
 */
class XeddsaVerifyTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- vector (a): the key conversion, on the lab board's real key ---
    private val labCurvePub = hex("ed6b64c3dd70eb86a192677c1124ac0ad439a129e3cba94c47e7e27ace503e5b")
    private val labEdPub = hex("bc82eb9c9b9b5a094899f52de16cba730af9b7a8f8498e360948ea4eb9445a38")

    // --- vector (b): a real 2.8.0 packet off the air (PRIVATE_APP, a Knit frame under the signature cliff) ---
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

    // --- vector (c): a 2.8.0 TEXT_MESSAGE_APP post signed under the lab board's key — the room's shape ---
    private val textFrom = 0xe681a7c3u
    private val textId = 0x1234abcdu
    private val textPayload = "hello from Knit a7c3".encodeToByteArray()
    private val textSig =
        hex(
            "fbda52354547235b7400409ac34bdfbaea6a9e5d89cae1134e92efaefbe8179b" +
                "2d581fc366e0af43f04e7228096e0b5b0a6f02da87c2721d8f9a79c31975760d",
        )

    // --- firmware 2.8.1 (#242): five packets the firmware's own code signed, see the class KDoc ---

    /** One firmware-signed 2.8.1 packet, with its fields as the **phone** decodes them (after the board's merge). */
    private class FwVector(
        val id: UInt,
        val seen: SignedFields,
        val payload: ByteArray,
        val signingInput: ByteArray,
        val signature: ByteArray,
    )

    private val fwFrom = 0x28f1a7c3u
    private val fwCurvePub = hex("e7a0ed1ad488a5ff884c273c2a17b3d2cee3195f22308326f3845f70a85b5a2b")
    private val fwEdPub = hex("6633d32c5d686628340e071c9f8c0738f5a9725488b74fd7fdc6ca970f9ec032")

    private val roomPost =
        FwVector(
            id = 0x5eed0001u,
            seen =
                SignedFields(
                    to = 0xffffffffu,
                    wantResponse = false,
                    requestId = 0u,
                    replyId = 0x00000000u,
                    emoji = 0u,
                    bitfield = 1u,
                ),
            payload = hex("68656c6c6f2066726f6d206120322e382e3120626f617264"),
            signingInput =
                hex(
                    "01c3a7f1280100ed5effffffff01000000000000000000000000000000010000000268656c6c6f2066726f6d20612032" +
                        "2e382e3120626f617264",
                ),
            signature =
                hex(
                    "f934ee948475ffb5722d955e8db775bb8fa65d7eb7823a9d4c90d63a7a88b40b" +
                        "64d90938a8c86838daa7a92cc3b0d8cb7fc5e78526f8a62a254a8c639b43980c",
                ),
        )

    private val tapback =
        FwVector(
            id = 0x5eed0002u,
            seen =
                SignedFields(
                    to = 0xffffffffu,
                    wantResponse = false,
                    requestId = 0u,
                    replyId = 0x5eed0001u,
                    emoji = 1u,
                    bitfield = 0u,
                ),
            payload = hex("f09f918d"),
            signingInput =
                hex(
                    "01c3a7f1280200ed5effffffff01000000000000000100ed5e010000000000000002f09f918d",
                ),
            signature =
                hex(
                    "a6d552725c4a84c325a2fad70fb9d258df8d35465153335a1a300cbea8b133fd" +
                        "7abfd03089e992b9eed4e5094d5f6d22ab02dbaae4771c03e441582ceef97105",
                ),
        )

    private val wantResponse =
        FwVector(
            id = 0x5eed0003u,
            seen =
                SignedFields(
                    to = 0xffffffffu,
                    wantResponse = true,
                    requestId = 0u,
                    replyId = 0x00000000u,
                    emoji = 0u,
                    bitfield = 3u,
                ),
            payload = hex("616e796f6e652074686572653f"),
            signingInput =
                hex(
                    "01c3a7f1280300ed5effffffff010000000000000000000000000000000300000003616e796f6e652074686572653f",
                ),
            signature =
                hex(
                    "344da9a49a80466a0ea2c6d671bc8cf5bebae477ee38afd6b0ea39f36eb31518" +
                        "ad4914ee0ed2d110810d03f8b0caf761ce6aca14814ffe614e86a419caffc70b",
                ),
        )

    private val noBitfield =
        FwVector(
            id = 0x5eed0004u,
            seen =
                SignedFields(
                    to = 0xffffffffu,
                    wantResponse = false,
                    requestId = 0u,
                    replyId = 0x00000000u,
                    emoji = 0u,
                    bitfield = null,
                ),
            payload = hex("6e6f206269746669656c64"),
            signingInput =
                hex(
                    "01c3a7f1280400ed5effffffff0100000000000000000000000000000000000000006e6f206269746669656c64",
                ),
            signature =
                hex(
                    "90adfb42cd8a0e4db7c1cba172c1ae1be6f92bc1efc6b78058e1bc3f22abc7b4" +
                        "5b0c3b181a9253f9cafb155c1f57a29b7580e45fa98b119705748910614e7209",
                ),
        )

    private val mergedWantResponse =
        FwVector(
            id = 0x5eed0005u,
            seen =
                SignedFields(
                    to = 0xffffffffu,
                    wantResponse = true,
                    requestId = 0u,
                    replyId = 0x00000000u,
                    emoji = 0u,
                    bitfield = 3u,
                ),
            payload = hex("6d657267656420616674657220766572696679"),
            signingInput =
                hex(
                    "01c3a7f1280500ed5effffffff0100000000000000000000000000000003000000026d65726765642061667465722076" +
                        "6572696679",
                ),
            signature =
                hex(
                    "ef315a8b7984a8b52b66015ca92cc4adc58015c4dc906a6140e0db9955055283" +
                        "8aa6e477280f754430fdd9a2f0a7c17b79040c95a9d81f345cb5c54be057a502",
                ),
        )

    private val fwVectors = listOf(roomPost, tapback, wantResponse, noBitfield, mergedWantResponse)

    private fun fw(
        v: FwVector,
        key: ByteArray = fwCurvePub,
        from: UInt = fwFrom,
        id: UInt = v.id,
        portnum: Int = 1,
        payload: ByteArray = v.payload,
        signature: ByteArray? = v.signature,
        fields: SignedFields? = v.seen,
    ): Boolean = XeddsaVerify.verify(key, from, id, portnum, payload, signature, fields)

    @Test
    fun theBoardsCurveKeyConvertsToTheEdKeyItSignsWith() {
        // `y = (u − 1)/(u + 1)`, sign bit clear — CryptoEngine::curve_to_ed_pub, and what XEdDSA's key
        // derivation lands on after negating the scalar for this particular key.
        assertArrayEquals(labEdPub, XeddsaVerify.edPublicKey(labCurvePub))
        assertTrue("the image has its sign bit clear", (labEdPub[31].toInt() and 0x80) == 0)
        assertArrayEquals("and on the 2.8.1 vectors' test key", fwEdPub, XeddsaVerify.edPublicKey(fwCurvePub))
    }

    @Test
    fun eachFirmwareSignedPacketVerifiesWithTheFieldsAsThePhoneSeesThem() {
        for (v in fwVectors) assertTrue("0x%08x".format(v.id.toInt()), fw(v))
    }

    @Test
    fun aTwoEightZeroSignatureNoLongerVerifies() {
        // Meshtastic withdrew 2.8.0 and Knit dropped its layout (2026-10-30): a genuine 2.8.0 signature off the air, and
        // one the lab board made on the text port, both fail — whatever the phone decoded for the envelope.
        for (fields in listOf(SignedFields(to = 0xffffffffu), SignedFields(to = 0xffffffffu, bitfield = 1u))) {
            assertFalse(XeddsaVerify.verify(onAirKey, onAirFrom, onAirId, onAirPort, onAirPayload, onAirSig, fields))
            assertFalse(XeddsaVerify.verify(labCurvePub, textFrom, textId, 1, textPayload, textSig, fields))
        }
    }

    @Test
    fun theSigningInputIsTheFirmwaresBufferByteForByte() {
        for (v in listOf(roomPost, tapback, wantResponse, noBitfield)) {
            assertArrayEquals(v.signingInput, XeddsaVerify.signingInput(fwFrom, v.id, 1, v.payload, v.seen))
        }
        val m = mergedWantResponse
        assertArrayEquals(m.signingInput, XeddsaVerify.signingInput(fwFrom, m.id, 1, m.payload, m.seen.copy(wantResponse = false)))
    }

    @Test
    fun aFirmwareSignatureRejectsEveryCoveredFieldFlipped() {
        // tapback carries a reply id, an emoji and a present-zero bitfield; wantResponse the flag and bit 1.
        for (v in listOf(tapback, wantResponse)) {
            val f = v.seen
            val payload = v.payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFalse("to", fw(v, fields = f.copy(to = 0x12345678u)))
            assertFalse("request_id", fw(v, fields = f.copy(requestId = f.requestId + 1u)))
            assertFalse("reply_id", fw(v, fields = f.copy(replyId = f.replyId + 1u)))
            assertFalse("emoji", fw(v, fields = f.copy(emoji = f.emoji xor 1u)))
            assertFalse("bitfield value", fw(v, fields = f.copy(bitfield = f.bitfield!! xor 4u)))
            assertFalse("bitfield presence", fw(v, fields = f.copy(bitfield = null)))
            assertFalse("want_response", fw(v, fields = f.copy(wantResponse = !f.wantResponse)))
            assertFalse("payload", fw(v, payload = payload))
            assertFalse("a byte short", fw(v, payload = v.payload.copyOf(v.payload.size - 1)))
            assertFalse("from", fw(v, from = fwFrom + 1u))
            assertFalse("id", fw(v, id = v.id + 1u))
            assertFalse("portnum", fw(v, portnum = 256))
        }
    }

    @Test
    fun theMergedWantResponseCandidateIsTriedOnlyForTheExactMergeShape() {
        // Signed with want_response false and bitfield bit 1 set; the receiving board merged the bit in after
        // verifying (Router.cpp:1102-1108), so the phone reads true — and it must still verify.
        assertTrue(fw(mergedWantResponse))
        // Seen true over bit 1 clear: no board merged that, so it is a tamper.
        assertFalse("bit 1 clear", fw(roomPost, fields = roomPost.seen.copy(wantResponse = true)))
        // Seen true with no bitfield at all: nothing to merge from.
        assertFalse("no bitfield", fw(noBitfield, fields = noBitfield.seen.copy(wantResponse = true)))
        // Never the reverse: a packet signed true and read false is not a merge.
        assertFalse("signed true, seen false", fw(wantResponse, fields = wantResponse.seen.copy(wantResponse = false)))
    }

    @Test
    fun aBitfieldsPresenceIsSignedApartFromItsValue() {
        assertFalse("present zero read as absent", fw(tapback, fields = tapback.seen.copy(bitfield = null)))
        assertFalse("absent read as present zero", fw(noBitfield, fields = noBitfield.seen.copy(bitfield = 0u)))
    }

    @Test
    fun aFirmwareSignedPacketFailsUnderAnotherBoardsKey() {
        // Both are real, valid keys — just not the one that signed.
        for (v in fwVectors) {
            assertFalse(fw(v, key = onAirKey))
            assertFalse(fw(v, key = labCurvePub))
        }
    }

    @Test
    fun aMissingShortLongOrFlippedSignatureIsFalseNotAnError() {
        for (v in fwVectors) {
            assertFalse("null", fw(v, signature = null))
            assertFalse("63 bytes", fw(v, signature = v.signature.copyOf(63)))
            assertFalse("65 bytes", fw(v, signature = v.signature + byteArrayOf(0)))
            val flipped = v.signature.copyOf().also { it[10] = (it[10].toInt() xor 0x20).toByte() }
            assertFalse("flipped", fw(v, signature = flipped))
        }
    }

    @Test
    fun noEnvelopeIsNoVerdict() {
        // The fields 2.8.1 signs were not carried: there is nothing to check the signature over.
        for (v in fwVectors) assertFalse(fw(v, fields = null))
    }

    @Test
    fun aGarbageKeyIsRefusedNotThrown() {
        assertNull("wrong length", XeddsaVerify.edPublicKey(ByteArray(31)))
        assertNull("wrong length", XeddsaVerify.edPublicKey(ByteArray(33)))
        // u = p − 1 is the one field element with no (u + 1) inverse.
        val pMinusOne = hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")
        assertNull("no inverse", XeddsaVerify.edPublicKey(pMinusOne))
        assertFalse(fw(tapback, key = pMinusOne))
        assertFalse(fw(tapback, key = ByteArray(31)))
        // A random 32 bytes is (almost always) not on the curve; whatever Tink makes of it, the answer is false.
        assertFalse(fw(tapback, key = ByteArray(32) { (it * 37 + 11).toByte() }))
    }

    @Test
    fun theAllZeroKeyVerifiesNothing() {
        for (v in fwVectors) assertFalse(fw(v, key = ByteArray(32)))
    }

    @Test
    fun theTopBitOfTheCurveKeyIsMaskedLikeTheFirmwareDoes() {
        // RFC 7748 and fe_frombytes both ignore bit 255 of a Curve25519 key, so a key with it set is the
        // same key — and a signature under it still verifies.
        val withTopBit = fwCurvePub.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
        assertArrayEquals(fwEdPub, XeddsaVerify.edPublicKey(withTopBit))
        assertTrue(fw(tapback, key = withTopBit))
        val labWithTopBit = labCurvePub.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }
        assertArrayEquals(labEdPub, XeddsaVerify.edPublicKey(labWithTopBit))
    }
}
