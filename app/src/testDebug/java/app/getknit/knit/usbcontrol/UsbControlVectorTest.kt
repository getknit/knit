@file:Suppress("MagicNumber") // fixture scalars and counters are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.protocol.VectorFiles
import app.getknit.knit.mesh.protocol.fromHex
import app.getknit.knit.mesh.protocol.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * USB control's pairing and session known answers (`vectors/usb-control-v2.json`, `docs/USB_CONTROL.md`): one pair
 * exchange with fixed ephemerals and a fixed code, its stretch, info and the four outputs; one session under the
 * pairing it left, both direction keys, the first frames each way and a padded one; and the frames each side must
 * refuse. knit-cli's tests read a copy of the same file. Rebuilt and compared on every run; `KNIT_WRITE_VECTORS=1`
 * rewrites it, which only an intended change of the pairing or the seal does.
 */
class UsbControlVectorTest {
    /** The pair exchange's fixtures and what they derive. */
    private class Pair {
        val host = LinkSeal.Ephemeral.fixed(scalar(0x40))
        val phone = LinkSeal.Ephemeral.fixed(scalar(0x60))
        val eH: ByteArray = host.pub
        val eP: ByteArray = phone.pub
        val ss = UsbSeal.sharedSecret(host, eP)
        val client = CLIENT.encodeToByteArray()
        val outputs = UsbPairingCrypto.schedule(ss, CODE, eH, eP, client)
        val wrong = UsbPairingCrypto.schedule(ss, WRONG_CODE, eH, eP, client)
    }

    private fun pairJson(p: Pair) =
        buildJsonObject {
            put("hostScalar", scalar(0x40).toHex())
            put("phoneScalar", scalar(0x60).toHex())
            put("eH", p.eH.toHex())
            put("eP", p.eP.toHex())
            put("ss", p.ss.toHex())
            put("code", CODE)
            put("codeShown", UsbPairingCrypto.display(CODE))
            put("client", CLIENT)
            put("stretched", UsbPairingCrypto.stretch(CODE, p.eH, p.eP).toHex())
            put("info", UsbPairingCrypto.info(p.eH, p.eP, p.client).toHex())
            put("confirmHost", p.outputs.confirmHost.toHex())
            put("confirmPhone", p.outputs.confirmPhone.toHex())
            put("psk", p.outputs.psk.toHex())
            put("handle", p.outputs.handle.toHex())
            put("wrongCode", WRONG_CODE)
            put("wrongCodeConfirmHost", p.wrong.confirmHost.toHex())
        }

    private fun sessionJson(p: Pair): JsonObject {
        val openHost = LinkSeal.Ephemeral.fixed(scalar(0x80))
        val oH = openHost.pub
        val oP = LinkSeal.Ephemeral.fixed(scalar(0xa0)).pub
        val oss = UsbSeal.sharedSecret(openHost, oP)
        val host = UsbSeal.sessionKeys(UsbSeal.Role.HOST, p.outputs.psk, oss, oH, oP, p.outputs.handle)
        val hostSealer = UsbSeal.Sealer(host.send)
        val hello0 = UsbControlFrames.encodeSealed(hostSealer, HELLO)
        val status1 = UsbControlFrames.encodeSealed(hostSealer, STATUS)
        val padded2 = UsbControlFrames.encodeSealed(hostSealer, PADDED)
        val reply0 = UsbControlFrames.encodeSealed(UsbSeal.Sealer(host.recv), REPLY)
        val flipped = hello0.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val offPsk =
            p.outputs.psk
                .copyOf()
                .also { it[0] = (it[0] + 1).toByte() }
        val wrongPsk = UsbSeal.Sealer(UsbSeal.sessionKeys(UsbSeal.Role.HOST, offPsk, oss, oH, oP, p.outputs.handle).send)
        return buildJsonObject {
            put("hostScalar", scalar(0x80).toHex())
            put("phoneScalar", scalar(0xa0).toHex())
            put("eH", oH.toHex())
            put("eP", oP.toHex())
            put("ss", oss.toHex())
            put("keyH2P", host.send.toHex())
            put("keyP2H", host.recv.toHex())
            putJsonObject("frames") {
                put("hostHello", frame(HELLO, 0, hello0))
                put("hostStatus", frame(STATUS, 1, status1))
                put("hostPadded", frame(PADDED, 2, padded2))
                put("phoneReply", frame(REPLY, 0, reply0))
            }
            putJsonObject("rejects") {
                put("flippedBit", reject(flipped, "h2p", 0, "the last tag byte flipped"))
                put("skippedCounter", reject(status1, "h2p", 0, "frame 1 opened where frame 0 is due"))
                put("wrongDirection", reject(hello0, "p2h", 0, "a host frame opened under the phone's own key"))
                put("wrongPsk", reject(wrongPsk.seal(HELLO.encodeToByteArray()), "h2p", 0, "sealed under a psk one bit off"))
                put("lowOrderKeyShare", "0000000000000000000000000000000000000000000000000000000000000000")
            }
        }
    }

    private fun build(): JsonObject {
        val p = Pair()
        return buildJsonObject {
            put("about", ABOUT)
            put("pair", pairJson(p))
            put("session", sessionJson(p))
        }
    }

    private fun frame(
        plain: String,
        counter: Int,
        bytes: ByteArray,
    ) = buildJsonObject {
        put("counter", counter)
        put("plaintext", plain)
        put("frame", bytes.toHex())
    }

    private fun reject(
        bytes: ByteArray,
        direction: String,
        counter: Int,
        why: String,
    ) = buildJsonObject {
        put("frame", bytes.toHex())
        put("openWith", direction)
        put("atCounter", counter)
        put("why", why)
    }

    @Test
    fun thePairingAndTheSessionRebuildByteForByte() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)
        val built = build()
        for (key in listOf(
            "about",
            "pair",
            "session",
        )) {
            assertEquals("usb-control vector '$key' drifted", file.getValue(key), built.getValue(key))
        }
    }

    @Test
    fun thePinnedFramesOpenInOrderAndEveryRejectIsRefused() {
        assumeFalse(VectorFiles.writing)
        val session = VectorFiles.read(FILE).getValue("session").jsonObject

        fun hex(
            o: JsonObject,
            k: String,
        ) = o
            .getValue(k)
            .jsonPrimitive.content
            .fromHex()
        val keys = mapOf("h2p" to hex(session, "keyH2P"), "p2h" to hex(session, "keyP2H"))
        val frames = session.getValue("frames").jsonObject
        val fromHost = UsbSeal.Opener(keys.getValue("h2p"))
        for (name in listOf("hostHello", "hostStatus", "hostPadded")) {
            val f = frames.getValue(name).jsonObject
            val plain = fromHost.open(hex(f, "frame")).decodeToString()
            assertEquals(name, f.getValue("plaintext").jsonPrimitive.content, plain.trimEnd())
        }
        assertTrue(
            "the padded frame carries its space inside the seal",
            hex(frames.getValue("hostPadded").jsonObject, "frame").size % 64 != 0,
        )
        val rejects = session.getValue("rejects").jsonObject
        for ((name, value) in rejects) {
            if (name == "lowOrderKeyShare") continue
            val r = value.jsonObject
            val opener = UsbSeal.Opener(keys.getValue(r.getValue("openWith").jsonPrimitive.content))
            val thrown = runCatching { opener.open(hex(r, "frame")) }.exceptionOrNull()
            assertTrue("$name opened", thrown is UsbSealException)
        }
        val low = hex(rejects, "lowOrderKeyShare")
        assertTrue(runCatching { UsbSeal.sharedSecret(LinkSeal.Ephemeral.fixed(scalar(0x80)), low) }.exceptionOrNull() is UsbSealException)
        val reply = frames.getValue("phoneReply").jsonObject
        assertEquals(
            "the phone's frame opens under the other direction's key",
            reply.getValue("plaintext").jsonPrimitive.content,
            UsbSeal.Opener(keys.getValue("p2h")).open(hex(reply, "frame")).decodeToString(),
        )
    }

    @Test
    fun knitWriteVectorsRewritesTheUsbControlVectors() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build())
    }

    private companion object {
        fun scalar(start: Int) = ByteArray(32) { (start + it).toByte() }

        const val FILE = "usb-control-v2.json"
        const val CODE = "7KQFM2XD9R"
        const val WRONG_CODE = "7KQFM2XD9S"
        const val CLIENT = "knit-cli 0.3.0 on vectors"
        const val HELLO = """{"id":1,"op":"hello","v":1,"nonce":"vector","client":"knit-cli"}"""
        const val STATUS = """{"id":2,"op":"status"}"""
        const val REPLY = """{"id":1,"ok":true}"""

        // 4 + 492 + 16 = 512: sealed with one trailing space, 493 bytes of plaintext.
        val PADDED = "{\"id\":3,\"op\":\"raw\",\"pad\":\"" + "p".repeat(492 - 28) + "\"}"

        const val ABOUT =
            "USB control pairing and sessions (docs/USB_CONTROL.md; experimental, debug builds only). Pair: ss = X25519(scalar, " +
                "peer's public key); c = PBKDF2-HMAC-SHA256(code ASCII, salt = eH || eP, 200000 iterations, 32); okm = " +
                "HKDF-SHA256(ikm = ss, salt = c, info = 'knit/usb/v2/pair' || eH || eP || u32be(len client) || client, 112) = " +
                "confirmHost (32) || confirmPhone (32) || psk (32) || handle (16). Session: kH2P/kP2H = HKDF-SHA256(ikm = psk || " +
                "ss, salt = eH || eP, info = 'knit/usb/v2/seal/h2p' or '.../p2h' || handle, 32). Frame = u32be(0x80000000 | " +
                "n + 16) || AES-256-GCM(k, nonce = 4 zero bytes || u64be counter, plaintext n, aad = the 4 header bytes) with " +
                "its tag; each direction counts from 0. A plaintext whose frame would be a multiple of 64 bytes gets one " +
                "trailing space before it is sealed. Scalars are raw (X25519 clamps them)."
    }
}
