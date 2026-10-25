package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchors for the client↔spool record layer: byte-exact golden vectors for every record type (the
 * fixtures mirrored in docs/SPOOL_PROTOCOL.md §13 — keep them in lockstep), round-trips, and the two
 * tolerance properties additive evolution relies on (unknown discriminators are identifiable and
 * skippable, unknown fields are ignored). To regenerate after an *intended* record change,
 * temporarily print [vectors] and paste the new hex here.
 */
class SpoolRecordsTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    /** Every record type as a fixed instance → its encoded bytes, in a stable order. */
    @Suppress("LongMethod") // a flat list of one fixture per record type — clearer as one block than split
    private fun vectors(): Map<String, ByteArray> =
        linkedMapOf(
            "helloSpool" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        min = 1,
                        limits =
                            SpoolLimits(
                                maxBlob = 65_536,
                                maxRecord = 131_072,
                                maxScopes = 64,
                                maxPull = 64,
                                maxFramesCap = 1_000,
                                maxTtlMs = 604_800_000L,
                            ),
                        powBits = 20,
                    ),
                ),
            "helloSpoolAttach" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        min = 1,
                        limits =
                            SpoolLimits(
                                maxBlob = 65_536,
                                maxRecord = 131_072,
                                maxScopes = 64,
                                maxPull = 64,
                                maxFramesCap = 1_000,
                                maxTtlMs = 604_800_000L,
                                maxAttachBytes = 16_777_216,
                                maxAChunk = 49_221,
                                maxAget = 32,
                            ),
                        powBits = 20,
                    ),
                ),
            // §7.4: a spool that runs a commons advertises its name and pinned bounds — never its id — and
            // `attach` only when true, so a room without attachments is the shorter map.
            "helloSpoolCommons" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        min = 1,
                        limits =
                            SpoolLimits(
                                maxBlob = 65_536,
                                maxRecord = 131_072,
                                maxScopes = 64,
                                maxPull = 64,
                                maxFramesCap = 1_000,
                                maxTtlMs = 604_800_000L,
                            ),
                        powBits = 20,
                        commons = SpoolCommonsInfo(name = "Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536),
                    ),
                ),
            "helloSpoolCommonsAttach" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        commons = SpoolCommonsInfo(maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536, attach = true),
                    ),
                ),
            "helloClient" to SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)),
            "sub" to
                SpoolCodec.encode(
                    SpoolSub(
                        t = SpoolRecordType.SUB,
                        q = 1L,
                        subs =
                            listOf(
                                ScopeSub(
                                    scope = bytes(32, 1),
                                    bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                                    pow = PowStamp(n = 42L, d = 20_680L),
                                ),
                            ),
                    ),
                ),
            "digest" to
                SpoolCodec.encode(
                    SpoolDigest(
                        t = SpoolRecordType.DIGEST,
                        scope = bytes(32, 1),
                        digest = bytes(8, 2),
                        count = 3,
                        full = false,
                        bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                    ),
                ),
            "listRequest" to SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = 2L, scope = bytes(32, 1))),
            "listResponse" to
                SpoolCodec.encode(
                    SpoolList(
                        t = SpoolRecordType.LIST,
                        q = 2L,
                        scope = bytes(32, 1),
                        blobIds = listOf(bytes(32, 3), bytes(32, 4)),
                        tombstones = listOf(bytes(32, 5)),
                    ),
                ),
            "pull" to
                SpoolCodec.encode(
                    SpoolPull(t = SpoolRecordType.PULL, q = 3L, scope = bytes(32, 1), blobIds = listOf(bytes(32, 3))),
                ),
            "blob" to
                SpoolCodec.encode(
                    SpoolBlob(t = SpoolRecordType.BLOB, scope = bytes(32, 1), blobId = bytes(32, 3), data = bytes(48, 6)),
                ),
            "push" to
                SpoolCodec.encode(
                    SpoolPush(
                        t = SpoolRecordType.PUSH,
                        q = 4L,
                        scope = bytes(32, 1),
                        blobId = bytes(32, 3),
                        data = bytes(48, 6),
                        pow = PowStamp(n = 42L, d = 20_680L),
                    ),
                ),
            "event" to
                SpoolCodec.encode(
                    SpoolEvent(t = SpoolRecordType.EVENT, scope = bytes(32, 1), blobId = bytes(32, 3), data = bytes(48, 6)),
                ),
            "okBare" to SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = 3L)),
            "okMissing" to SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = 3L, missing = listOf(bytes(32, 4)))),
            "errScoped" to
                SpoolCodec.encode(
                    SpoolErr(
                        t = SpoolRecordType.ERR,
                        code = SpoolErrCode.TOMBSTONED,
                        q = 4L,
                        scope = bytes(32, 1),
                    ),
                ),
            "errRate" to
                SpoolCodec.encode(
                    SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.RATE, msg = "slow down", retryMs = 30_000L),
                ),
            "ahave" to
                SpoolCodec.encode(SpoolAhave(t = SpoolRecordType.AHAVE, q = 5L, scope = bytes(32, 1), aid = bytes(32, 7))),
            "ahas" to
                SpoolCodec.encode(
                    SpoolAhas(
                        t = SpoolRecordType.AHAS,
                        q = 5L,
                        scope = bytes(32, 1),
                        aid = bytes(32, 7),
                        total = 3,
                        bits = bytes(1, 9),
                    ),
                ),
            "ahasDead" to
                SpoolCodec.encode(
                    SpoolAhas(
                        t = SpoolRecordType.AHAS,
                        q = 5L,
                        scope = bytes(32, 1),
                        aid = bytes(32, 7),
                        total = 0,
                        bits = ByteArray(0),
                        dead = true,
                    ),
                ),
            "aget" to
                SpoolCodec.encode(
                    SpoolAget(t = SpoolRecordType.AGET, q = 6L, scope = bytes(32, 1), aid = bytes(32, 7), from = 0, n = 2),
                ),
            "achunk" to
                SpoolCodec.encode(
                    SpoolAchunk(
                        t = SpoolRecordType.ACHUNK,
                        scope = bytes(32, 1),
                        aid = bytes(32, 7),
                        idx = 1,
                        total = 3,
                        cid = bytes(32, 8),
                        data = bytes(48, 6),
                    ),
                ),
            "aput" to
                SpoolCodec.encode(
                    SpoolAput(
                        t = SpoolRecordType.APUT,
                        q = 7L,
                        scope = bytes(32, 1),
                        aid = bytes(32, 7),
                        idx = 1,
                        total = 3,
                        cid = bytes(32, 8),
                        data = bytes(48, 6),
                        pow = PowStamp(n = 42L, d = 20_680L),
                    ),
                ),
            // Appended (§7.4, 2026-10-24): a spool running two rooms lists both by tag, and `commons` repeats the first.
            "helloSpoolRooms" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        commons = HOME,
                        rooms =
                            listOf(
                                SpoolCommonsRoom(
                                    tag = tagOf(bytes(32, 10)),
                                    name = "Home",
                                    maxFrames = 500,
                                    ttlMs = 86_400_000L,
                                    maxBlob = 65_536,
                                ),
                                SpoolCommonsRoom(
                                    tag = tagOf(bytes(32, 11)),
                                    maxFrames = 200,
                                    ttlMs = 86_400_000L,
                                    maxBlob = 65_536,
                                    attach = true,
                                ),
                            ),
                    ),
                ),
        )

    private fun tagOf(secret: ByteArray) = ScopeCrypto.commonsTag(ScopeCrypto.commonsScopeId(secret))

    @Test
    fun everyRecordMatchesItsPinnedDefiniteLengthCbor() {
        val actual = vectors()
        assertEquals(EXPECTED.keys.toList(), actual.keys.toList())
        for ((name, encoded) in actual) {
            assertEquals("record vector '$name' drifted — an unintended record-layer change", EXPECTED.getValue(name), encoded.toHex())
        }
    }

    @Test
    fun recordsRoundTripAndPeekTheirDiscriminator() {
        for ((name, encoded) in vectors()) {
            assertNotNull("peekType failed for '$name'", SpoolCodec.peekType(encoded))
        }

        val sub = SpoolCodec.decode<SpoolSub>(vectors().getValue("sub"))!!
        assertEquals(1L, sub.q)
        assertArrayEquals(bytes(32, 1), sub.subs.single().scope)
        assertEquals(
            400,
            sub.subs
                .single()
                .bounds.maxFrames,
        )
        assertEquals(
            42L,
            sub.subs
                .single()
                .pow
                ?.n,
        )

        val list = SpoolCodec.decode<SpoolList>(vectors().getValue("listResponse"))!!
        assertEquals(2, list.blobIds?.size)
        assertArrayEquals(bytes(32, 3), list.blobIds?.first())
        assertArrayEquals(bytes(32, 5), list.tombstones?.single())

        val err = SpoolCodec.decode<SpoolErr>(vectors().getValue("errRate"))!!
        assertEquals(SpoolErrCode.RATE, err.code)
        assertEquals(30_000L, err.retryMs)
        assertNull(err.q)
    }

    @Test
    fun attachmentSupportIsAllThreeLimitsOrNone() {
        val plain = SpoolCodec.decode<SpoolHello>(vectors().getValue("helloSpool"))!!
        val attaching = SpoolCodec.decode<SpoolHello>(vectors().getValue("helloSpoolAttach"))!!

        // A v1 spool decodes unchanged and is *not* offered attachment records — it would skip them
        // without answering, hanging the client's q until the request timeout.
        assertEquals(false, plain.limits!!.attachments)
        assertNull(plain.limits.maxAget)
        assertEquals(true, attaching.limits!!.attachments)
        assertEquals(16_777_216, attaching.limits.maxAttachBytes)
        assertEquals(49_221, attaching.limits.maxAChunk)
        assertEquals(32, attaching.limits.maxAget)
        // The v1 hello's bytes must not have moved: the three fields are nullable and defaults are omitted.
        assertEquals(EXPECTED.getValue("helloSpool"), vectors().getValue("helloSpool").toHex())
    }

    @Test
    fun aCommonsIsAdvertisedWithoutItsId() {
        val plain = SpoolCodec.decode<SpoolHello>(vectors().getValue("helloSpool"))!!
        val room = SpoolCodec.decode<SpoolHello>(vectors().getValue("helloSpoolCommons"))!!
        val attaching = SpoolCodec.decode<SpoolHello>(vectors().getValue("helloSpoolCommonsAttach"))!!

        assertNull(plain.commons)
        assertEquals("Home", room.commons!!.name)
        assertEquals(500, room.commons.maxFrames)
        assertEquals(86_400_000L, room.commons.ttlMs)
        assertEquals(false, room.commons.attach)
        assertNull(attaching.commons!!.name)
        assertEquals(true, attaching.commons.attach)
        // The field is `commons` and nothing in it is 32 bytes long: the daemon never sends the scope id.
        val bytes = vectors().getValue("helloSpoolCommons")
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("commons"))
        assertTrue(bytes.none { it == 0x58.toByte() }) // no CBOR byte string of 1-byte length anywhere in it
    }

    @Test
    fun roomsAreListedByTagWithTheirOwnBoundsAndNoId() {
        val bytes = vectors().getValue("helloSpoolRooms")
        val hello = SpoolCodec.decode<SpoolHello>(bytes)!!
        val rooms = hello.rooms!!

        assertEquals(listOf("b15333db903e9745", "a2f73b72abeabcb1"), rooms.map { hex(it.tag) })
        assertEquals(listOf("Home", null), rooms.map { it.name })
        assertEquals(listOf(500, 200), rooms.map { it.maxFrames })
        assertEquals(listOf(false, true), rooms.map { it.attach })
        assertEquals("Home", hello.commons!!.name)
        // Each tag is an 8-byte string (`48`), and no 32-byte id rides anywhere in the record.
        assertTrue(bytes.none { it == 0x58.toByte() })
    }

    @Test
    fun attachmentRecordsRoundTrip() {
        val ahas = SpoolCodec.decode<SpoolAhas>(vectors().getValue("ahas"))!!
        assertEquals(3, ahas.total)
        assertArrayEquals(bytes(32, 7), ahas.aid)
        assertEquals(false, ahas.dead)

        val dead = SpoolCodec.decode<SpoolAhas>(vectors().getValue("ahasDead"))!!
        assertEquals(0, dead.total)
        assertEquals(true, dead.dead)
        assertEquals(0, dead.bits.size)

        val aget = SpoolCodec.decode<SpoolAget>(vectors().getValue("aget"))!!
        assertEquals(0, aget.from)
        assertEquals(2, aget.n)

        val achunk = SpoolCodec.decode<SpoolAchunk>(vectors().getValue("achunk"))!!
        assertEquals(1, achunk.idx)
        assertEquals(3, achunk.total)
        assertArrayEquals(bytes(32, 8), achunk.cid)
        assertArrayEquals(bytes(48, 6), achunk.data)

        val aput = SpoolCodec.decode<SpoolAput>(vectors().getValue("aput"))!!
        assertEquals(7L, aput.q)
        assertEquals(42L, aput.pow?.n)
        assertArrayEquals(bytes(32, 7), aput.aid)
    }

    @Test
    fun unknownDiscriminatorIsIdentifiableAndUnknownFieldsAreIgnored() {
        val future = SpoolCodec.encode(FutureRecord(t = "future", q = 9L, novel = "later"))

        assertEquals("future", SpoolCodec.peekType(future))
        assertNull(SpoolCodec.decode<SpoolPull>(future))

        val extendedOk = SpoolCodec.encode(FutureRecord(t = SpoolRecordType.OK, q = 9L, novel = "later"))
        val ok = SpoolCodec.decode<SpoolOk>(extendedOk)
        assertNotNull(ok)
        assertEquals(9L, ok!!.q)
    }

    @Test
    fun malformedBytesDecodeToNullNotThrow() {
        assertNull(SpoolCodec.peekType(byteArrayOf(0x42, 0x00)))
        assertNull(SpoolCodec.decode<SpoolHello>(ByteArray(0)))
        assertNull(SpoolCodec.decode<SpoolHello>(bytes(16, 7)))
    }

    private companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        val EXPECTED =
            mapOf(
                "helloSpool" to
                    "a561746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314",
                "helloSpoolAttach" to
                    "a561746568656c6c6f617601636d696e01666c696d697473a9676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c84006e6d617841747461636842797465731a01000000696d6178414368756e6b19" +
                    "c045676d617841676574182067706f774269747314",
                "helloSpoolCommons" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f77426974731467636f6d6d6f6e73a4646e616d6564486f6d65696d61" +
                    "784672616d65731901f46574746c4d731a05265c00676d6178426c6f621a00010000",
                "helloSpoolCommonsAttach" to
                    "a361746568656c6c6f61760167636f6d6d6f6e73a4696d61784672616d65731901f4" +
                    "6574746c4d731a05265c00676d6178426c6f621a0001000066617474616368f5",
                "helloClient" to "a261746568656c6c6f617601",
                "sub" to
                    "a3617463737562617101647375627381a36573636f7065582001080f161d242b3239" +
                    "40474e555c636a71787f868d949ba2a9b0b7bec5ccd3da66626f756e6473a3696d61" +
                    "784672616d65731901906574746c4d731a0a4cb800676d6178426c6f621a00010000" +
                    "63706f77a2616e182a61641950c8",
                "digest" to
                    "a66174666469676573746573636f7065582001080f161d242b323940474e555c636a" +
                    "71787f868d949ba2a9b0b7bec5ccd3da6664696765737448020910171e252c336563" +
                    "6f756e74036466756c6cf466626f756e6473a3696d61784672616d65731901906574" +
                    "746c4d731a0a4cb800676d6178426c6f621a00010000",
                "listRequest" to
                    "a36174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da",
                "listResponse" to
                    "a56174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473825820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc5820040b12192027" +
                    "2e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd6a746f6d6273746f" +
                    "6e6573815820050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2" +
                    "c9d0d7de",
                "pull" to
                    "a461746470756c6c6171036573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473815820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc",
                "blob" to
                    "a4617464626c6f626573636f7065582001080f161d242b323940474e555c636a7178" +
                    "7f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b4249" +
                    "50575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b2229" +
                    "30373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091017" +
                    "1e252c333a41484f",
                "push" to
                    "a6617464707573686171046573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d34" +
                    "3b424950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d14" +
                    "1b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02" +
                    "0910171e252c333a41484f63706f77a2616e182a61641950c8",
                "event" to
                    "a46174656576656e746573636f7065582001080f161d242b323940474e555c636a71" +
                    "787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b42" +
                    "4950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b22" +
                    "2930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910" +
                    "171e252c333a41484f",
                "okBare" to "a26174626f6b617103",
                "okMissing" to
                    "a36174626f6b617103676d697373696e67815820040b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dd",
                "errScoped" to
                    "a461746365727264636f64656a746f6d6273746f6e65646171046573636f70655820" +
                    "01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da",
                "errRate" to "a461746365727264636f64656472617465636d736769736c6f7720646f776e6772657472794d73197530",
                "ahave" to
                    "a461746561686176656171056573636f7065582001080f161d242b323940474e555c" +
                    "636a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f46" +
                    "4d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0",
                "ahas" to
                    "a6617464616861736171056573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e065746f74616c03646269747341" +
                    "09",
                "ahasDead" to
                    "a7617464616861736171056573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e065746f74616c00646269747340" +
                    "6464656164f5",
                "aget" to
                    "a6617464616765746171066573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e06466726f6d00616e02",
                "achunk" to
                    "a7617466616368756e6b6573636f7065582001080f161d242b323940474e555c636a" +
                    "71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d54" +
                    "5b626970777e858c939aa1a8afb6bdc4cbd2d9e0636964780165746f74616c036363" +
                    "69645820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3" +
                    "dae164646174615830060d141b222930373e454c535a61686f767d848b9299a0a7ae" +
                    "b5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f",
                "aput" to
                    "a9617464617075746171076573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e0636964780165746f74616c0363" +
                    "6369645820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5cc" +
                    "d3dae164646174615830060d141b222930373e454c535a61686f767d848b9299a0a7" +
                    "aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f63706f77a2616e182a61" +
                    "641950c8",
                "helloSpoolRooms" to
                    "a461746568656c6c6f61760167636f6d6d6f6e73a4646e616d6564486f6d65696d61" +
                    "784672616d65731901f46574746c4d731a05265c00676d6178426c6f621a00010000" +
                    "65726f6f6d7382a56374616748b15333db903e9745646e616d6564486f6d65696d61" +
                    "784672616d65731901f46574746c4d731a05265c00676d6178426c6f621a00010000" +
                    "a56374616748a2f73b72abeabcb1696d61784672616d657318c86574746c4d731a05" +
                    "265c00676d6178426c6f621a0001000066617474616368f5",
            )

        private val HOME = SpoolCommonsInfo(name = "Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536)
    }
}

/** A "later protocol version" record: a known/unknown `t` plus a field today's types lack. */
@Serializable
private class FutureRecord(
    val t: String,
    val q: Long,
    val novel: String,
)
