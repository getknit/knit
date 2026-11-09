package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.wifiaware.NanCueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun advertiseParseRoundTrips() {
        // A real 26-char base32 nodeId (contains no '|', so the split-on-'|' round-trips cleanly).
        val id = "ffbbh6thbepahqxsv2gqog45m4"
        val parsed = checkNotNull(Protocol.parse(Protocol.advertise(id)))
        assertEquals(id, parsed.nodeId)
        assertEquals(Protocol.VERSION, parsed.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, parsed.capabilities)
    }

    @Test
    fun bareLegacyNodeIdParsesToUnknownVersion() {
        // A peer that advertises just its nodeId (a pre-protocol build) is "unknown" — version 0, no caps.
        val parsed = checkNotNull(Protocol.parse(ID))
        assertEquals(ID, parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun parseNeverThrowsOnGarbageSegments() {
        val parsed = checkNotNull(Protocol.parse("$ID|notanint|zzz"))
        assertEquals(ID, parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun cryptoV3RidesTheProfileAboveTheBleAdvertsEightBits() {
        // ADR 059: the bit is a send-time input read from the pinned profile; it deliberately sits above the
        // 8 bits a BLE advert carries (0x80 is the transcoder's — ADR 060), and every local build claims it.
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_CRYPTO_V3 != 0L)
        assertTrue(Protocol.CAP_CRYPTO_V3 > 0xFFL)
        assertEquals(0x100L, Protocol.CAP_CRYPTO_V3)
    }

    @Test
    fun theTranscoderBitIsTheLastOneABleAdvertCarries() {
        // ADR 060: read from the advert copy like CAP_FAST_COMPACT (a transport-local encoding choice), so it
        // has to sit inside the 8 bits a BLE advert carries — and it is the last of them.
        assertEquals(0x80L, Protocol.CAP_FRAME_TRANSCODE)
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_FRAME_TRANSCODE != 0L)
        assertTrue(Protocol.CAP_FRAME_TRANSCODE <= 0xFFL)
    }

    @Test
    fun linkPreviewCardsRideTheProfileLikeFiles() {
        // A send-time input read from the pinned profile, the next bit after files, above the BLE advert's
        // eight bits; every local build claims it.
        assertEquals(0x400L, Protocol.CAP_LINK_PREVIEW)
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_PREVIEW != 0L)
        assertTrue(Protocol.CAP_LINK_PREVIEW > 0xFFL)
        assertEquals(Protocol.CAP_FILES shl 1, Protocol.CAP_LINK_PREVIEW)
    }

    @Test
    fun theDoorbellBitIsTheIphonesAndNeverOurs() {
        // ADR 2026-09.dqvb: the next bit after direct transfer, read from the HELLO by the Bluetooth transport only.
        // Android serves no GATT doorbell, so no local build claims it.
        assertEquals(0x1000L, Protocol.CAP_DOORBELL)
        assertEquals(Protocol.CAP_DIRECT_TRANSFER shl 1, Protocol.CAP_DOORBELL)
        assertEquals(0L, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_DOORBELL)
        assertEquals(
            Protocol.CAP_DOORBELL,
            checkNotNull(Protocol.parse("ffbbh6thbepahqxsv2gqog45m4|1|1009")).capabilities and Protocol.CAP_DOORBELL,
        )
    }

    @Test
    fun everyBuildAnswersAHolderQuery() {
        // ADR 2026-10.6e5t: the next bit after the doorbell, above the advert's 8 bits, and in every build — only the
        // asking is gated (BuildConfig.BLOB_QUERY), so a mixed fleet answers whoever asks.
        assertEquals(0x2000L, Protocol.CAP_BLOB_HAVE)
        assertEquals(Protocol.CAP_DOORBELL shl 1, Protocol.CAP_BLOB_HAVE)
        assertEquals(Protocol.CAP_BLOB_HAVE, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_BLOB_HAVE)
        val advertised = checkNotNull(Protocol.parse(Protocol.advertise("ffbbh6thbepahqxsv2gqog45m4"))).capabilities
        assertEquals(Protocol.CAP_BLOB_HAVE, advertised and Protocol.CAP_BLOB_HAVE)
    }

    @Test
    fun theCallBitIsAdvertisedOnlyByABuildThatWiresCalls() {
        // docs/VOICE_CALLS.md §3.1: the next bit after the holder query, above the advert's 8 bits, and never in
        // LOCAL_CAPABILITIES — MeshManager ORs it into its own profile when the call media is wired (di/CallWiring).
        assertEquals(0x4000L, Protocol.CAP_CALL)
        assertEquals(Protocol.CAP_BLOB_HAVE shl 1, Protocol.CAP_CALL)
        assertEquals(0L, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_CALL)
    }

    @Test
    fun nodeIdIsAlwaysTheFirstSegment() {
        // Robust to any future suffix appended after the capabilities field.
        assertEquals(ID, checkNotNull(Protocol.parse("$ID|1|f|future|stuff")).nodeId)
    }

    @Test
    fun aNodeIdThatIsNotCanonicalNamesNoPeer() {
        // #199: the id keys the peers row, and SQLite drops a leading U+FEFF from a UTF-16 bind, so a marked twin
        // would pass every in-memory duplicate check and still land on the real id's row.
        val last = NodeId.ALPHABET.indexOf(ID.last())
        // 128 bits leave the last base32 char two padding bits; setting one decodes to the same bytes but
        // re-encodes differently.
        val padded = ID.dropLast(1) + NodeId.ALPHABET[last or 1]
        val advert = Protocol.advertise(ID).removePrefix(ID)

        assertNull("BOM-led", Protocol.parse("\uFEFF$ID$advert"))
        assertNull("byte-swapped BOM-led", Protocol.parse("\uFFFE$ID$advert"))
        assertNull("padding bits set", Protocol.parse("$padded$advert"))
        assertNull("uppercase", Protocol.parse("${ID.uppercase()}$advert"))
        assertNull("25 chars", Protocol.parse("${ID.dropLast(1)}$advert"))
        assertNull("27 chars", Protocol.parse("${ID}a$advert"))
        assertNull("empty", Protocol.parse(""))
        assertNull("empty id with segments", Protocol.parse(advert))
        assertNull("legacy 8-char id", Protocol.parse("abcd1234"))
    }

    @Test
    fun theLinkProofBitIsTheNextFreeOneAndEveryBuildCarriesIt() {
        // ADR 2026-11.264w: the bit after CAP_INET_LINK, above the advert's 8 bits, in LOCAL_CAPABILITIES so it rides the
        // HELLO and every signed profile.
        assertEquals(0x40000L, Protocol.CAP_LINK_PROOF)
        assertEquals(Protocol.CAP_INET_LINK shl 1, Protocol.CAP_LINK_PROOF)
        // 0x42FFF until the Bluetooth reader added CAP_BLIND_ADVERT (ADR 2026-11.xp6d, step 5a), 0xC2FFF until sealed
        // links added CAP_LINK_SEAL (#286).
        assertEquals(0x1C2FFFL, Protocol.LOCAL_CAPABILITIES)
    }

    @Test
    fun theLinkSealBitIsTheNextFreeOneAndRidesOnlyBesideTheProofBit() {
        // knit-next #286: the bit after CAP_BLIND_ADVERT. A build carries it only together with CAP_LINK_PROOF, since a
        // sealed link is always a proven link.
        assertEquals(0x100000L, Protocol.CAP_LINK_SEAL)
        assertEquals(Protocol.CAP_BLIND_ADVERT shl 1, Protocol.CAP_LINK_SEAL)
        assertEquals(Protocol.CAP_LINK_SEAL, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL)
        assertEquals(Protocol.CAP_LINK_PROOF, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_PROOF)
    }

    @Test
    fun theHelloAddsTheNonceAsAFourthFieldTheAdvertNeverCarries() {
        val nonce = ByteArray(Protocol.HELLO_NONCE_BYTES) { (0xA0 + it).toByte() }
        val hello = Protocol.hello(ID, nonce)

        assertEquals("${Protocol.advertise(ID)}|na0a1a2a3a4a5a6a7a8a9aaabacadaeaf", hello)
        assertEquals(3, Protocol.advertise(ID).split('|').size)
        val parsed = checkNotNull(Protocol.parse(hello))
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", parsed.nonceHex)
        assertTrue(parsed.offersProof)
        assertEquals(Protocol.LOCAL_CAPABILITIES, parsed.capabilities)
    }

    @Test
    fun aHelloNonceOfAnyOtherShapeIsNoNonce() {
        val digits = "0123456789abcdef0123456789abcdef"
        assertEquals(digits, checkNotNull(Protocol.parse("$ID|1|42fff|n$digits")).nonceHex)
        for (field in listOf(
            "",
            "n",
            "n${digits.dropLast(1)}",
            "n${digits}0",
            "N$digits",
            "n${digits.uppercase()}",
            "x$digits",
            "n${digits.dropLast(1)}g",
        )) {
            val parsed = checkNotNull(Protocol.parse("$ID|1|42fff|$field")) { field }
            assertNull(field, parsed.nonceHex)
            assertTrue("the rest still parses: $field", parsed.offersProof && parsed.protoVersion == 1)
        }
        assertNull("three fields", checkNotNull(Protocol.parse(Protocol.advertise(ID))).nonceHex)
        // Fields past the nonce stay ignored, as every field past 2 was before it.
        assertEquals(digits, checkNotNull(Protocol.parse("$ID|1|42fff|n$digits|future")).nonceHex)
    }

    @Test
    fun aFourFieldHelloStillGivesAnOlderReaderItsFirstThree() {
        // What a v2.10.1 build reads: segments 0-2, the fourth ignored (nodeIdIsAlwaysTheFirstSegment's rule).
        val parsed = checkNotNull(Protocol.parse(Protocol.hello(ID, ByteArray(Protocol.HELLO_NONCE_BYTES))))
        assertEquals(ID, parsed.nodeId)
        assertEquals(Protocol.VERSION, parsed.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, parsed.capabilities)
    }

    @Test
    fun aHelloCarriesItsToldAndAddressedFormsBehindTheNonce() {
        // ADR 2026-11.xp6d: `t` is what the sender airs, `a` (a dialer's only) the form it dialed.
        val nonce = ByteArray(Protocol.HELLO_NONCE_BYTES) { (0xA0 + it).toByte() }
        val told = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x10 + it).toByte() }
        val addressed = ByteArray(Protocol.HELLO_FORM_BYTES) { (0xC0 + it).toByte() }
        val hello = Protocol.hello(ID, nonce, told, addressed)

        assertEquals(
            "${Protocol.hello(ID, nonce)}|t101112131415161718191a1b1c1d1e1f|ac0c1c2c3c4c5c6c7c8c9cacbcccdcecf",
            hello,
        )
        val parsed = checkNotNull(Protocol.parse(hello))
        assertEquals("101112131415161718191a1b1c1d1e1f", parsed.toldHex)
        assertEquals("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf", parsed.addressedHex)
        assertEquals(NodeId.fromBytes(told), parsed.told)
        assertEquals(NodeId.fromBytes(addressed), parsed.addressed)
        // An older reader still gets fields 0-3 exactly as from a HELLO without them.
        val plain = checkNotNull(Protocol.parse(Protocol.hello(ID, nonce)))
        assertEquals(plain, parsed.copy(toldHex = null, addressedHex = null))
        assertNull(plain.toldHex)
        assertNull(plain.addressedHex)
    }

    @Test
    fun aFormFieldCountsOnlyInItsExactShapeAndOnlyBehindAValidNonce() {
        val nonce = "n" + "ab".repeat(Protocol.HELLO_NONCE_BYTES)
        val form = "1a".repeat(Protocol.HELLO_FORM_BYTES) // a letter in it, so its uppercase twin differs
        for (bad in listOf("t$form" + "0", "t" + form.dropLast(1), "T$form", "t" + form.uppercase(), "x$form", "t", "")) {
            assertNull(bad, checkNotNull(Protocol.parse("$ID|1|42fff|$nonce|$bad")).toldHex)
        }
        // Behind no nonce (an advert, or an SSI's `|d<digest>`), nothing is a form, whatever it looks like.
        val advert = checkNotNull(Protocol.parse("$ID|1|42fff|t$form|a$form"))
        val ssi = checkNotNull(Protocol.parse("$ID|1|42fff|d1617346507|t$form|a$form"))
        for (noNonce in listOf(advert, ssi)) {
            assertNull(noNonce.toldHex)
            assertNull(noNonce.addressedHex)
        }
        // Either order after the nonce, and a stray future field between them, still reads.
        val parsed = checkNotNull(Protocol.parse("$ID|1|42fff|$nonce|a$form|future|t$form"))
        assertEquals(form, parsed.toldHex)
        assertEquals(form, parsed.addressedHex)
        // A letter twice in its exact shape is absent, whichever copy a port would take; the other letter still reads.
        val other = "2b".repeat(Protocol.HELLO_FORM_BYTES)
        val twice = checkNotNull(Protocol.parse("$ID|1|42fff|$nonce|t$form|a$form|t$other"))
        assertNull(twice.toldHex)
        assertEquals(form, twice.addressedHex)
        val twiceA = checkNotNull(Protocol.parse("$ID|1|42fff|$nonce|a$form|t$form|a$other"))
        assertNull(twiceA.addressedHex)
        assertEquals(form, twiceA.toldHex)
        // A malformed second copy is no copy at all.
        assertEquals(form, checkNotNull(Protocol.parse("$ID|1|42fff|$nonce|t$form|t$other" + "0")).toldHex)
    }

    @Test
    fun aHelloRefusesAFormOfTheWrongSize() {
        val nonce = ByteArray(Protocol.HELLO_NONCE_BYTES)
        for (size in listOf(0, Protocol.HELLO_FORM_BYTES - 1, Protocol.HELLO_FORM_BYTES + 1)) {
            assertTrue(runCatching { Protocol.hello(ID, nonce, told = ByteArray(size)) }.isFailure)
            assertTrue(runCatching { Protocol.hello(ID, nonce, addressed = ByteArray(size)) }.isFailure)
        }
    }

    @Test
    fun theBlindAdvertBitIsTheNextFreeOneAndShipsWithTheReader() {
        assertEquals(Protocol.CAP_LINK_PROOF shl 1, Protocol.CAP_BLIND_ADVERT)
        val local = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_BLIND_ADVERT
        assertEquals("the Bluetooth plane reads tags, so the build says so", Protocol.CAP_BLIND_ADVERT, local)
        // Above the advert's low byte: the advert itself does not move.
        assertEquals(0L, Protocol.CAP_BLIND_ADVERT and 0xFFL)
    }

    @Test
    fun theWifiAwareSsiKeepsItsDigestBehindTheUnchangedAdvert() {
        // The SSI is advertise() + "|d<digest>", found by its last "|d": the nonce never goes there.
        val ssi = Protocol.advertise(ID) + NanCueCodec.encodeSsiDigest(1_617_346_507L)
        assertEquals(1_617_346_507L, NanCueCodec.parseSsiDigest(ssi))
        assertNull(checkNotNull(Protocol.parse(ssi)).nonceHex)
    }

    private companion object {
        val ID = NodeId.derive("protocol-test")
    }
}
