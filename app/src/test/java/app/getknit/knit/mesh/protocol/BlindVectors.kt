package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.link.LinkFraming
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `keyed-v1.json`'s `blind` entries (ADR 2026-11.xp6d, knit-next #285), built and checked for [KeyedVectorTest]: the
 * blinded advert's tag construction, a link dialed by tag with its proof, and the HELLO's `t` / `a` parse contract.
 *
 * Every tag is checked three ways: the pinned bytes, [BlindTag], and [specTag], an independent build of the layout in
 * `BlindTag`'s KDoc from JCA's HMAC, so a drift in `BlindTag` fails here rather than being re-pinned. Write mode refuses
 * to pin a tag the two builds disagree on. The HELLOs are written by hand at fixed caps (`c0009`), as the link proof's
 * are, so a change to `Protocol.LOCAL_CAPABILITIES` never moves them; [Protocol.hello] is checked to write the same
 * fields after the nonce.
 *
 * The Bluetooth plane's reader adds `advert` (Alice's blinded service data with its parse, a prefixless one a reader
 * drops, and a clear advert carrying her tag's bytes, which stays clear) and `tagNext` (the `TAG_NEXT` record naming
 * her next slot's tag, and a 15-byte one a reader refuses).
 */
internal class BlindVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
    /** The vectors' wall clock: the slot every "now" tag is minted in. */
    private val wallMs: Long,
) {
    private val aliceKey = fixtureBytes(BlindTag.KEY_BYTES, ALICE_KEY_SEED)
    private val bobKey = fixtureBytes(BlindTag.KEY_BYTES, BOB_KEY_SEED)
    private val aliceId = NodeId.toBytes(alice.nodeId)
    private val bobId = NodeId.toBytes(bob.nodeId)
    private val slot = BlindTag.slotOf(wallMs)

    /** One pinned tag: [keyName]'s presence [key], aired by [ownerName], whose node id's raw 16 bytes are [owner]. */
    private class TagCase(
        val name: String,
        val keyName: String,
        val key: ByteArray,
        val ownerName: String,
        val owner: ByteArray,
        val slot: Long,
        val restart: Int,
    )

    private val tagCases: List<TagCase> =
        listOf(
            TagCase("aliceSlotMinus1", "alice", aliceKey, "alice", aliceId, -1, 0),
            TagCase("aliceSlot0", "alice", aliceKey, "alice", aliceId, 0, 0),
        ) + (0 until BlindTag.RESTARTS).map { TagCase("aliceNowRestart$it", "alice", aliceKey, "alice", aliceId, slot, it) } +
            TagCase("bobNowRestart0", "bob", bobKey, "bob", bobId, slot, 0) +
            // Alice's key re-sent by Bob as his own: not aliceNowRestart0, so no port can leave the owner out of the MAC.
            TagCase(REGIFTED, "alice", aliceKey, "bob", bobId, slot, 0)

    private val aliceTag = BlindTag.tag(aliceKey, aliceId, slot, 0)
    private val aliceNextTag = BlindTag.tag(aliceKey, aliceId, slot + 1, 0)
    private val aliceTagRestart1 = BlindTag.tag(aliceKey, aliceId, slot, 1)
    private val bobTag = BlindTag.tag(bobKey, bobId, slot, 0)

    private val slotOfCases = listOf(0L, SLOT_MS - 1, SLOT_MS, -1L, -SLOT_MS, -SLOT_MS - 1, wallMs)

    private val prefixCases: List<Triple<String, ByteArray, Boolean>> =
        listOf(
            Triple("tag", aliceTag, true),
            Triple("aliceClearId", NodeId.toBytes(alice.nodeId), false),
            Triple("prefixOnly", byteArrayOf(0xCE.toByte(), 0x73, 0x9F.toByte()) + ByteArray(BlindTag.BYTES - 3), true),
            Triple("lastPrefixBitFlipped", aliceTag.copyOf().also { it[2] = (it[2].toInt() xor 0x10).toByte() }, false),
            Triple("short", aliceTag.copyOf(BlindTag.BYTES - 1), false),
            Triple("long", aliceTag + 0, false),
        )

    // A link dialed by tag: Alice, blinding, dials the tag Bob airs; nonces 40..4f and 50..5f.
    private val nonceD = ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x40 + it).toByte() }
    private val nonceR = ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x50 + it).toByte() }

    private fun base(
        nodeId: String,
        nonce: ByteArray,
    ): String = "$nodeId|${Protocol.VERSION}|${TAG_CAPABILITIES.toString(HEX)}|n${nonce.toHex()}"

    private fun t(form: ByteArray) = "|t${form.toHex()}"

    private fun a(form: ByteArray) = "|a${form.toHex()}"

    private val helloD = (base(alice.nodeId, nonceD) + t(aliceTag) + a(bobTag)).encodeToByteArray()
    private val helloR = (base(bob.nodeId, nonceR) + t(bobTag)).encodeToByteArray()

    /** Bob's reply as a relay rewrote it: its `t` names Alice's tag. Bob's proof must not hold over it. */
    private val helloRRewritten = (base(bob.nodeId, nonceR) + t(aliceTag)).encodeToByteArray()

    private val aliceProver = LinkProver(alice.bundle, alice::sign)
    private val bobProver = LinkProver(bob.bundle, bob::sign)

    private fun proofRecord(payload: ByteArray) = LinkFraming.encode(LinkFraming.Type.PROOF, payload)

    private fun proofDialer() = proofRecord(aliceProver.proof(LinkProof.Role.DIALER, helloD, helloR))

    private fun proofResponder() = proofRecord(bobProver.proof(LinkProof.Role.RESPONDER, helloD, helloR))

    /** One HELLO the parse contract pins: the payload, and the `t` / `a` hex it reads (null: absent). */
    private class FieldCase(
        val name: String,
        val payload: String,
        val told: ByteArray?,
        val addressed: ByteArray?,
        val nonce: Boolean = true,
    )

    private val fieldCases: List<FieldCase> =
        run {
            val b = base(alice.nodeId, nonceD)
            val upper = "|t${aliceTag.toHex().uppercase()}"
            val bare = "${alice.nodeId}|${Protocol.VERSION}|${TAG_CAPABILITIES.toString(HEX)}"
            listOf(
                FieldCase("both", b + t(aliceTag) + a(bobTag), aliceTag, bobTag),
                FieldCase("reversed", b + a(bobTag) + t(aliceTag), aliceTag, bobTag),
                FieldCase("unknownFieldSkipped", "$b|x0123${t(aliceTag)}", aliceTag, null),
                FieldCase("duplicateTold", b + t(aliceTag) + t(aliceTagRestart1) + a(bobTag), null, bobTag),
                FieldCase("duplicateAddressed", b + t(aliceTag) + a(bobTag) + a(aliceTagRestart1), aliceTag, null),
                FieldCase("sameToldTwice", b + t(aliceTag) + t(aliceTag), null, null),
                FieldCase("malformedSecondTold", "$b${t(aliceTag)}|t${"z".repeat(HEX_DIGITS)}", aliceTag, null),
                FieldCase("upperHex", b + upper, null, null),
                FieldCase("shortTold", "$b|t${aliceTag.toHex().dropLast(1)}", null, null),
                FieldCase("longTold", "$b${t(aliceTag)}0", null, null),
                FieldCase("noNonce", bare + t(aliceTag) + a(bobTag), null, null, nonce = false),
                FieldCase("shortNonce", "$bare|n${nonceD.toHex().dropLast(1)}${t(aliceTag)}", null, null, nonce = false),
                FieldCase("nonceNotThird", "$bare|x0123|n${nonceD.toHex()}${t(aliceTag)}", null, null, nonce = false),
            )
        }

    fun json(): JsonObject =
        buildJsonObject {
            put("about", ABOUT)
            putJsonObject("presenceKeys") {
                put("alice", aliceKey.toHex())
                put("bob", bobKey.toHex())
            }
            putJsonObject("constants") {
                put("label", BlindTag.LABEL)
                put("slotMs", SLOT_MS)
                put("restarts", BlindTag.RESTARTS)
                put("windowPastSlots", BlindTag.WINDOW_PAST_SLOTS)
                put("windowAheadSlots", BlindTag.WINDOW_AHEAD_SLOTS)
            }
            putJsonArray("slotOf") {
                for (wall in slotOfCases) {
                    addJsonObject {
                        put("wallMs", wall)
                        put("slot", BlindTag.slotOf(wall))
                    }
                }
            }
            putJsonObject("window") {
                val window = BlindTag.window(wallMs)
                put("wallMs", wallMs)
                put("from", window.first)
                put("to", window.last)
            }
            put("tags", tagsJson())
            putJsonObject("hasPrefix") {
                for ((name, bytes, expected) in prefixCases) {
                    putJsonObject(name) {
                        put("bytes", bytes.toHex())
                        put("hasPrefix", expected)
                    }
                }
            }
            put("link", linkJson())
            put("advert", advertJson())
            put("tagNext", tagNextJson())
            putJsonObject("helloFields") {
                for (c in fieldCases) {
                    putJsonObject(c.name) {
                        put("payload", c.payload)
                        put("nonce", c.nonce)
                        put("told", c.told?.toHex())
                        put("addressed", c.addressed?.toHex())
                    }
                }
            }
        }

    private fun tagsJson(): JsonObject =
        buildJsonObject {
            for (c in tagCases) {
                val tag = BlindTag.tag(c.key, c.owner, c.slot, c.restart)
                check(tag.contentEquals(specTag(c.key, c.owner, c.slot, c.restart))) { "BlindTag drifted from its layout: ${c.name}" }
                putJsonObject(c.name) {
                    put("key", c.keyName)
                    put("owner", c.ownerName)
                    put("ownerId", c.owner.toHex())
                    put("slot", c.slot)
                    put("restart", c.restart)
                    put("macInput", macInput(c.owner, c.slot, c.restart).toHex())
                    put("mac", mac(c.key, macInput(c.owner, c.slot, c.restart)).toHex())
                    put("tag", tag.toHex())
                    put("asNodeId", NodeId.fromBytes(tag))
                }
            }
        }

    private fun linkJson(): JsonObject =
        buildJsonObject {
            putJsonObject("helloTaggedDialer") {
                put("payload", helloD.decodeToString())
                put("record", LinkFraming.encode(LinkFraming.Type.HELLO, helloD).toHex())
            }
            putJsonObject("helloTaggedReply") {
                put("payload", helloR.decodeToString())
                put("record", LinkFraming.encode(LinkFraming.Type.HELLO, helloR).toHex())
            }
            putJsonObject("proofTranscriptDialer") {
                put("signer", "alice")
                put("hex", LinkProof.transcript(LinkProof.Role.DIALER, helloD, helloR).toHex())
            }
            putJsonObject("proofTranscriptResponder") {
                put("signer", "bob")
                put("hex", LinkProof.transcript(LinkProof.Role.RESPONDER, helloD, helloR).toHex())
            }
            putJsonObject("proofDialer") {
                put("signer", "alice")
                put("record", proofDialer().toHex())
            }
            putJsonObject("proofResponder") {
                put("signer", "bob")
                put("record", proofResponder().toHex())
            }
            putJsonObject("toldRewritten") {
                put("checkedAs", "bob, responder, helloTaggedDialer and this payload")
                put("payload", helloRRewritten.decodeToString())
                put("record", "proofResponder")
                put("reason", "the signature does not cover this transcript")
            }
        }

    // Alice's blinded presence advert: her tag in bytes 1–16, the digest cue zeroed, FLAG_BLINDED and FLAG_AWARE set.
    private fun blindedAdvert(): ByteArray =
        BleAdvertPayload.advert(
            NodeId.fromBytes(aliceTag),
            TAG_CAPABILITIES,
            DIGEST_VERSION,
            ADVERT_PSM,
            sideLive = false,
            gattPeers = false,
            aware = true,
            blinded = true,
        )

    /** The same layout with a prefixless "tag" (Alice's clear id) under FLAG_BLINDED: malformed, dropped unread. */
    private fun malformedAdvert(): ByteArray =
        BleAdvertPayload.encode(alice.nodeId, TAG_CAPABILITIES, 0L, ADVERT_PSM, BleAdvertPayload.FLAG_BLINDED)

    /** A clear advert (no FLAG_BLINDED) whose bytes 1–16 are Alice's tag: a clear advert is its own id, whatever its bytes. */
    private fun clearAdvertWithTagBytes(): ByteArray =
        BleAdvertPayload.encode(NodeId.fromBytes(aliceTag), TAG_CAPABILITIES, DIGEST_VERSION, ADVERT_PSM, 0)

    private fun advertJson(): JsonObject =
        buildJsonObject {
            putJsonObject("blinded") {
                put("tag", aliceTag.toHex())
                put("capabilities", TAG_CAPABILITIES.toString(HEX))
                put("digestVersionBeforeZeroing", DIGEST_VERSION)
                put("psm", ADVERT_PSM)
                put("flags", BleAdvertPayload.FLAG_BLINDED or BleAdvertPayload.FLAG_AWARE)
                put("serviceData", blindedAdvert().toHex())
                putJsonObject("parsed") {
                    put("form", NodeId.fromBytes(aliceTag))
                    put("blinded", true)
                    put("capabilities", TAG_CAPABILITIES and CAP_BYTE)
                    put("digestCue", 0)
                    put("psm", ADVERT_PSM)
                    put("aware", true)
                }
            }
            putJsonObject("malformed") {
                put("serviceData", malformedAdvert().toHex())
                put("reason", "FLAG_BLINDED over bytes without the tag prefix: dropped before anything is computed")
            }
            putJsonObject("clearWithTagBytes") {
                put("serviceData", clearAdvertWithTagBytes().toHex())
                put("blinded", false)
                put("reason", "no FLAG_BLINDED: read as a clear id, never keyed with the tag")
            }
        }

    private fun tagNextJson(): JsonObject =
        buildJsonObject {
            put("tag", aliceNextTag.toHex())
            put("slot", slot + 1)
            put("restart", 0)
            put("record", LinkFraming.encodeTagNext(aliceNextTag).toHex())
            putJsonObject("wrongLength") {
                put("record", LinkFraming.encode(LinkFraming.Type.TAG_NEXT, aliceNextTag.copyOf(BlindTag.BYTES - 1)).toHex())
                put("reason", "a TAG_NEXT is exactly 16 bytes; any other length is a framing error that drops the link")
            }
        }

    /** The blinded advert rebuilds byte for byte and parses to its tag; the prefixless one is dropped; a clear one stays clear. */
    fun assertAdvert(blind: JsonObject) {
        val advert = blind.obj("advert")
        val blinded = advert.obj("blinded")
        assertEquals(blindedAdvert().toHex(), blinded.str("serviceData"))
        val parsed = checkNotNull(BleAdvertPayload.parse(blinded.str("serviceData").fromHex()))
        val expected = blinded.obj("parsed")
        assertTrue(parsed.blinded)
        assertEquals(expected.str("form"), parsed.nodeId)
        assertEquals(expected.getValue("capabilities").jsonPrimitive.long, parsed.capabilities)
        assertEquals(expected.getValue("digestCue").jsonPrimitive.int, parsed.digestCue)
        assertEquals(expected.getValue("psm").jsonPrimitive.int, parsed.psm)
        assertEquals(expected.getValue("aware").jsonPrimitive.boolean, parsed.aware)
        assertTrue(BlindTag.hasPrefix(NodeId.toBytes(parsed.nodeId)))

        val malformed = advert.obj("malformed").str("serviceData").fromHex()
        assertEquals(malformedAdvert().toHex(), malformed.toHex())
        assertTrue(BleAdvertPayload.isMalformedBlinded(malformed))
        assertNull(BleAdvertPayload.parse(malformed))

        val clear = advert.obj("clearWithTagBytes").str("serviceData").fromHex()
        assertEquals(clearAdvertWithTagBytes().toHex(), clear.toHex())
        val clearParsed = checkNotNull(BleAdvertPayload.parse(clear))
        assertFalse(clearParsed.blinded)
        assertTrue("its form is not the tag's form", clearParsed.form != parsed.form)
    }

    /** The `TAG_NEXT` record rebuilds byte for byte and reads back; the 15-byte one is refused. */
    fun assertTagNext(blind: JsonObject) {
        val tagNext = blind.obj("tagNext")
        assertEquals(aliceNextTag.toHex(), tagNext.str("tag"))
        assertEquals(LinkFraming.encodeTagNext(aliceNextTag).toHex(), tagNext.str("record"))
        val read = checkNotNull(LinkFraming.read(tagNext.str("record").fromHex().inputStream()))
        assertEquals(LinkFraming.Type.TAG_NEXT, read.type)
        assertArrayEquals(aliceNextTag, read.payload)
        val wrong = tagNext.obj("wrongLength").str("record").fromHex()
        assertTrue(runCatching { LinkFraming.read(wrong.inputStream()) }.exceptionOrNull() is IOException)
    }

    /** Every pinned tag is what [BlindTag] and the layout build, from the pinned MAC input and MAC. */
    fun assertTags(blind: JsonObject) {
        val keys = blind.obj("presenceKeys")
        assertEquals(aliceKey.toHex(), keys.str("alice"))
        assertEquals(bobKey.toHex(), keys.str("bob"))
        val constants = blind.obj("constants")
        assertEquals(BlindTag.LABEL, constants.str("label"))
        assertEquals(SLOT_MS, constants.getValue("slotMs").jsonPrimitive.long)
        assertEquals(BlindTag.RESTARTS, constants.getValue("restarts").jsonPrimitive.int)
        assertEquals(BlindTag.WINDOW_PAST_SLOTS, constants.getValue("windowPastSlots").jsonPrimitive.long)
        assertEquals(BlindTag.WINDOW_AHEAD_SLOTS, constants.getValue("windowAheadSlots").jsonPrimitive.long)

        val slots = blind.getValue("slotOf").jsonArray.map { it.jsonObject }
        assertEquals(slotOfCases, slots.map { it.getValue("wallMs").jsonPrimitive.long })
        for (s in slots) {
            assertEquals(s.getValue("slot").jsonPrimitive.long, BlindTag.slotOf(s.getValue("wallMs").jsonPrimitive.long))
        }
        val window = blind.obj("window")
        assertEquals(wallMs, window.getValue("wallMs").jsonPrimitive.long)
        assertEquals(slot - BlindTag.WINDOW_PAST_SLOTS, window.getValue("from").jsonPrimitive.long)
        assertEquals(slot + BlindTag.WINDOW_AHEAD_SLOTS, window.getValue("to").jsonPrimitive.long)
        assertEquals(BlindTag.window(wallMs), window.getValue("from").jsonPrimitive.long..window.getValue("to").jsonPrimitive.long)

        val tags = blind.obj("tags")
        assertEquals(tagCases.map { it.name }.toSet(), tags.keys)
        for (c in tagCases) {
            val pinned = tags.obj(c.name)
            assertEquals(c.name, c.keyName, pinned.str("key"))
            assertEquals(c.name, c.ownerName, pinned.str("owner"))
            assertEquals(c.name, c.owner.toHex(), pinned.str("ownerId"))
            assertEquals(c.name, c.slot, pinned.getValue("slot").jsonPrimitive.long)
            assertEquals(c.name, c.restart, pinned.getValue("restart").jsonPrimitive.int)
            val input = pinned.str("macInput").fromHex()
            assertEquals(c.name, MAC_INPUT_BYTES, input.size)
            assertArrayEquals(c.name, macInput(c.owner, c.slot, c.restart), input)
            assertArrayEquals(c.name, mac(c.key, input), pinned.str("mac").fromHex())
            val tag = pinned.str("tag").fromHex()
            assertArrayEquals(c.name, specTag(c.key, c.owner, c.slot, c.restart), tag)
            assertArrayEquals(c.name, BlindTag.tag(c.key, c.owner, c.slot, c.restart), tag)
            assertArrayEquals(c.name, BlindTag.Expected(c.key, c.owner).tag(c.slot, c.restart), tag)
            assertEquals(c.name, NodeId.fromBytes(tag), pinned.str("asNodeId"))
            assertEquals(c.name, "zzzz", pinned.str("asNodeId").take(PREFIX_CHARS))
        }
        val minusOne = tags.obj("aliceSlotMinus1").str("macInput").drop(LABEL_HEX)
        assertEquals("the owner follows the label", aliceId.toHex(), minusOne.take(OWNER_HEX))
        assertEquals("u64be(-1) is two's complement", "ffffffffffffffff", minusOne.drop(OWNER_HEX).take(2 * Long.SIZE_BYTES))
        assertTrue(
            "one key under two owners is two tags",
            tags.obj(REGIFTED).str("tag") != tags.obj("aliceNowRestart0").str("tag"),
        )

        val prefix = blind.obj("hasPrefix")
        assertEquals(prefixCases.map { it.first }.toSet(), prefix.keys)
        for ((name, bytes, expected) in prefixCases) {
            val pinned = prefix.obj(name)
            assertEquals(name, bytes.toHex(), pinned.str("bytes"))
            assertEquals(name, expected, pinned.getValue("hasPrefix").jsonPrimitive.boolean)
            assertEquals(name, expected, BlindTag.hasPrefix(bytes))
        }
    }

    /** The tag-dialed link's HELLOs and proofs rebuild byte for byte, verify, and a rewritten `t` fails Bob's proof. */
    fun assertLink(blind: JsonObject) {
        val link = blind.obj("link")
        assertEquals(helloD.decodeToString(), link.obj("helloTaggedDialer").str("payload"))
        assertEquals(LinkFraming.encode(LinkFraming.Type.HELLO, helloD).toHex(), link.obj("helloTaggedDialer").str("record"))
        assertEquals(helloR.decodeToString(), link.obj("helloTaggedReply").str("payload"))
        assertEquals(LinkFraming.encode(LinkFraming.Type.HELLO, helloR).toHex(), link.obj("helloTaggedReply").str("record"))
        assertEquals(LinkProof.transcript(LinkProof.Role.DIALER, helloD, helloR).toHex(), link.obj("proofTranscriptDialer").str("hex"))
        assertEquals(
            LinkProof.transcript(LinkProof.Role.RESPONDER, helloD, helloR).toHex(),
            link.obj("proofTranscriptResponder").str("hex"),
        )
        assertEquals(proofDialer().toHex(), link.obj("proofDialer").str("record"))
        assertEquals(proofResponder().toHex(), link.obj("proofResponder").str("record"))

        // Protocol.hello writes the same fields after the caps that the hand-built HELLOs carry.
        val built = Protocol.hello(alice.nodeId, nonceD, told = aliceTag, addressed = bobTag)
        assertEquals(helloD.decodeToString().substringAfter("|n"), built.substringAfter("|n"))
        assertEquals(helloR.decodeToString().substringAfter("|n"), Protocol.hello(bob.nodeId, nonceR, told = bobTag).substringAfter("|n"))

        val dialer = payloadOf(link.obj("proofDialer").str("record"))
        assertNull(LinkProof.verify(dialer, alice.nodeId, LinkProof.Role.DIALER, helloD, helloR))
        val responder = payloadOf(link.obj("proofResponder").str("record"))
        assertNull(LinkProof.verify(responder, bob.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))

        val parsedD = checkNotNull(Protocol.parse(helloD.decodeToString()))
        assertEquals(TAG_CAPABILITIES, parsedD.capabilities)
        assertEquals(aliceTag.toHex(), parsedD.toldHex)
        assertEquals(bobTag.toHex(), parsedD.addressedHex)
        assertEquals(NodeId.fromBytes(aliceTag), parsedD.told)
        val parsedR = checkNotNull(Protocol.parse(helloR.decodeToString()))
        assertEquals(bobTag.toHex(), parsedR.toldHex)
        assertNull(parsedR.addressedHex)

        val rewritten = link.obj("toldRewritten")
        assertEquals(helloRRewritten.decodeToString(), rewritten.str("payload"))
        assertEquals(
            LinkProof.Failure.SIGNATURE,
            LinkProof.verify(responder, bob.nodeId, LinkProof.Role.RESPONDER, helloD, rewritten.str("payload").encodeToByteArray()),
        )
    }

    /** Every pinned HELLO parses to the `t` / `a` (and nonce) the file states. */
    fun assertHelloFields(blind: JsonObject) {
        val fields = blind.obj("helloFields")
        assertEquals(fieldCases.map { it.name }.toSet(), fields.keys)
        for (c in fieldCases) {
            val pinned = fields.obj(c.name)
            assertEquals(c.name, c.payload, pinned.str("payload"))
            assertEquals(c.name, c.told?.toHex(), pinned.strOrNull("told"))
            assertEquals(c.name, c.addressed?.toHex(), pinned.strOrNull("addressed"))
            assertEquals(c.name, c.nonce, pinned.getValue("nonce").jsonPrimitive.boolean)
            val parsed = checkNotNull(Protocol.parse(pinned.str("payload")))
            assertEquals(c.name, pinned.strOrNull("told"), parsed.toldHex)
            assertEquals(c.name, pinned.strOrNull("addressed"), parsed.addressedHex)
            assertEquals(c.name, c.nonce, parsed.nonceHex != null)
        }
    }

    private fun payloadOf(recordHex: String): ByteArray = checkNotNull(LinkFraming.read(recordHex.fromHex().inputStream())).payload

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.strOrNull(name: String): String? = (getValue(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    companion object {
        const val ABOUT =
            "Blinded adverts (ADR 2026-11.xp6d, knit-next #285). tags: m = HMAC-SHA256(presenceKey, label ‖ ownerNodeId " +
                "(16 raw bytes, ownerId) ‖ u64be(slot) ‖ u8(restart)), a 44-byte message; tag = CE 73 (90 | m[0] >> 4) ‖ " +
                "m[1..13]; slot = floor(wallMs / slotMs). aliceKeyUnderBob is Alice's key re-sent as Bob's own: another " +
                "tag than aliceNowRestart0, since a tag is bound to the id airing it. hasPrefix: 16 bytes " +
                "starting CE 73 9_, saying nothing of whose. link: Alice, blinding, dials Bob's tag; her HELLO tells her " +
                "tag (t) and the tag she dialed (a), his tells his; both proofs sign the HELLOs as sent, so a rewritten " +
                "t fails. helloFields: t and a are read only behind a valid nonce, in any order, as exactly 32 lowercase " +
                "hex digits; a letter whose exact-shape field appears twice is absent (null). Caps c0009 throughout."

        /** v1 caps, the link-proof bit and the blind-advert bit: what a tag-reading build's HELLO carries. */
        val TAG_CAPABILITIES = 0x9L or Protocol.CAP_LINK_PROOF or Protocol.CAP_BLIND_ADVERT

        private const val SLOT_MS = BlindTag.SLOT_MS

        /** The blinded advert's PSM, and the store digest whose low 32 bits a clear advert would carry (zeroed). */
        private const val ADVERT_PSM = 0x0083
        private const val DIGEST_VERSION = 0x1234_5678_9ABCL
        private const val CAP_BYTE = 0xFFL
        private const val ALICE_KEY_SEED = 41
        private const val BOB_KEY_SEED = 42
        private const val HEX = 16
        private const val HEX_DIGITS = 2 * Protocol.HELLO_FORM_BYTES
        private const val PREFIX_CHARS = 4
        private val LABEL_HEX = 2 * BlindTag.LABEL.length
        private const val OWNER_HEX = 2 * NodeId.BYTES
        private const val MAC_INPUT_BYTES = 44
        private const val REGIFTED = "aliceKeyUnderBob"
        private const val HMAC = "HmacSHA256"

        /** label (ASCII) ‖ owner (16 raw) ‖ u64be(slot) ‖ u8(restart), built here rather than read from [BlindTag]. */
        private fun macInput(
            owner: ByteArray,
            slot: Long,
            restart: Int,
        ): ByteArray =
            BlindTag.LABEL.toByteArray(Charsets.US_ASCII) + owner + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(slot).array() +
                byteArrayOf(restart.toByte())

        private fun mac(
            key: ByteArray,
            input: ByteArray,
        ): ByteArray = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(input)

        /** The layout in [BlindTag]'s KDoc, from the MAC: `CE 73 (90 | m[0] >>> 4) ‖ m[1..13]`. */
        private fun specTag(
            key: ByteArray,
            owner: ByteArray,
            slot: Long,
            restart: Int,
        ): ByteArray {
            val m = mac(key, macInput(owner, slot, restart))
            val head = byteArrayOf(0xCE.toByte(), 0x73, (0x90 or ((m[0].toInt() and 0xFF) ushr 4)).toByte())
            return head + m.copyOfRange(1, BlindTag.BYTES - 2)
        }
    }
}
