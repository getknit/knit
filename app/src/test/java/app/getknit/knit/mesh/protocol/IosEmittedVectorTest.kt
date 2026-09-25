package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.SafetyNumber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames the iOS port emits (`vectors/ios-emitted-v1.json`, written by knit-ios): Carol's identity from fixed
 * keys, her signed profile and room post, a v1 DM she sealed to Bob from `keyed-v1.json`, and their safety
 * number. Each frame must verify with Tink, re-encode with kotlinx to the exact bytes Carol signed, and pin or
 * open the way an Android frame would. When this fails after knit-ios regenerated the file, the iOS encoder
 * and ours disagree about the wire, and that is the bug.
 */
class IosEmittedVectorTest {
    private val file: JsonObject by lazy { VectorFiles.read("ios-emitted-v1.json") }
    private val carol by lazy { VectorParty.identity(file, "carol") }
    private val bob by lazy { VectorParty.identity(VectorFiles.read("keyed-v1.json"), "bob") }

    private fun frame(name: String): JsonObject =
        file
            .getValue("frames")
            .jsonObject
            .getValue(name)
            .jsonObject

    private fun field(
        entry: JsonObject,
        key: String,
    ): String = entry.getValue(key).jsonPrimitive.content

    /** The frame's relay envelope, once its signature verifies and both layers re-encode to their own bytes. */
    private fun relayOf(name: String): RelayEnvelope {
        val bytes = field(frame(name), "wire").fromHex()
        val wire = checkNotNull(WireCodec.decodeWire(bytes)) { "$name does not decode" }
        assertArrayEquals("$name's WireEnvelope re-encodes differently", bytes, WireCodec.encodeWire(wire))
        assertTrue("$name's signature does not verify", MessageCrypto.verify(carol.bundle, wire.sig, wire.signed))
        val relay = checkNotNull(WireCodec.decodeEnvelope(wire.signed)) { "$name's RelayEnvelope does not decode" }
        assertArrayEquals("$name's RelayEnvelope re-encodes differently", wire.signed, WireCodec.encodeEnvelope(relay))
        assertEquals(carol.nodeId, relay.senderId)
        return relay
    }

    @Test
    fun `carol's keys derive the bundle and node id the iOS port wrote`() {
        val entry =
            file
                .getValue("identities")
                .jsonObject
                .getValue("carol")
                .jsonObject
        assertEquals(field(entry, "bundle"), carol.bundle.encoded)
        assertEquals(field(entry, "nodeId"), carol.nodeId)
    }

    @Test
    fun `carol and bob have the safety number the iOS port computed`() {
        val expected =
            file
                .getValue("safetyNumber")
                .jsonObject
                .getValue("number")
                .jsonPrimitive.content
        assertEquals(expected, SafetyNumber.compute(carol.nodeId, carol.bundle.encoded, bob.nodeId, bob.bundle.encoded))
    }

    @Test
    fun `carol's profile pins through the ordinary door`() {
        val relay = relayOf("carolProfile")
        assertEquals(FrameType.PROFILE, relay.type)
        assertEquals("profile-${carol.nodeId}-${relay.sentAt}", relay.id)
        val profile = checkNotNull(WireCodec.decodePayload<ProfileContent>(relay.payload))
        assertArrayEquals(relay.payload, WireCodec.encodePayload(profile))
        assertEquals(carol.bundle.encoded, profile.pubKey)
        assertEquals(relay.senderId, NodeId.fromPublicKeyBundle(checkNotNull(profile.pubKey)))
        assertEquals(DeviceTag.derive("vector-device-carol"), profile.deviceTag)
        assertEquals(Protocol.VERSION, profile.protoVersion)
        assertEquals(0x9L, profile.capabilities)
    }

    @Test
    fun `carol's room post carries its body`() {
        val relay = relayOf("carolRoomPost")
        assertNull(relay.recipientId)
        assertEquals(field(frame("carolRoomPost"), "body"), WireCodec.decodePayload<ChatContent>(relay.payload)?.body)
    }

    @Test
    fun `bob opens carol's DM`() {
        val relay = relayOf("carolDmToBob")
        assertEquals(bob.nodeId, relay.recipientId)
        val chat = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload))
        assertEquals("a DM carries no cleartext body", "", chat.body)
        val header = MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, bob.nodeId)
        val opened = bob.crypto.open(checkNotNull(chat.enc), header, bob.nodeId)
        assertEquals(field(frame("carolDmToBob"), "body"), opened?.body)
    }
}
