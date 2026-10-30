package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.XeddsaVerify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `Data` fields firmware 2.8.1 signs beyond the port and the payload (#242, ADR 2026-09.ggq4's 2026-10-29
 * amendment), as [MeshtasticProto] decodes them into `MeshData.signedFields`: `want_response` (3, varint),
 * `request_id` (6), `reply_id` (7) and `emoji` (8, `fixed32` all three), and `bitfield` (9, `optional uint32`), whose
 * presence 2.8.1 signs apart from its value. Field numbers from `mesh.proto` at tag `v2.8.1.8e6a88d`. Its own class
 * because [MeshtasticProtoTest] sits just under detekt's `LargeClass` threshold on main, where it carries no suppression,
 * and these two would push it over.
 */
class MeshtasticProtoSignedFieldsTest {
    private fun hex(s: String): ByteArray =
        s
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun decodeDataCarriesTheEnvelopeTwoEightOneSigns() {
        // Firmware 2.8.1 signs want_response (3), request_id (6), reply_id (7), emoji (8) and the bitfield (9) with
        // its presence (#242): all of them reach the phone-side check as decoded.
        val bytes =
            hex(
                "12 2A " + // FromRadio.packet, len 42
                    "0D 78 56 34 12 " + // from
                    "15 FF FF FF FF " + // to = broadcast
                    "22 19 " + // decoded, len 25
                    "08 01 " + // portnum = TEXT_MESSAGE_APP
                    "12 02 68 69 " + // payload = "hi"
                    "18 01 " + // want_response = true
                    "35 DD CC BB AA " + // request_id = 0xaabbccdd (fixed32)
                    "3D 01 00 ED 5E " + // reply_id = 0x5eed0001 (fixed32)
                    "45 01 00 00 00 " + // emoji = 1 (fixed32)
                    "48 00 " + // bitfield = 0, present
                    "35 01 01 00 00", // id = 0x101
            )
        val d = (MeshtasticProto.decodeFromRadio(bytes) as FromRadio.Packet).packet.decoded!!
        assertEquals(0xaabbccddu, d.requestId)
        assertEquals(
            XeddsaVerify.DataFields(wantResponse = true, requestId = 0xaabbccddu, replyId = 0x5eed0001u, emoji = 1u, bitfield = 0u),
            d.signedFields,
        )
    }

    @Test
    fun anAbsentBitfieldDecodesApartFromAPresentZero() {
        // `optional uint32 bitfield = 9`: 2.8.1 signs its presence in the flags byte, so absent must stay null.
        val absent =
            hex(
                "12 17 " +
                    "0D 78 56 34 12 " +
                    "15 FF FF FF FF " +
                    "22 06 " +
                    "08 01 " +
                    "12 02 68 69 " +
                    "35 01 01 00 00",
            )
        val fields = (MeshtasticProto.decodeFromRadio(absent) as FromRadio.Packet).packet.decoded!!.signedFields
        assertEquals(XeddsaVerify.DataFields(), fields)
        assertNull(fields!!.bitfield)

        val three =
            hex(
                "12 19 " +
                    "0D 78 56 34 12 " +
                    "15 FF FF FF FF " +
                    "22 08 " +
                    "08 01 " +
                    "12 02 68 69 " +
                    "48 03 " + // bitfield = ok_to_mqtt | want_response
                    "35 01 01 00 00",
            )
        val d = (MeshtasticProto.decodeFromRadio(three) as FromRadio.Packet).packet.decoded!!
        assertEquals(3u, d.signedFields!!.bitfield)
        assertFalse("the field itself was not set", d.signedFields.wantResponse)
    }
}
