package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.frameIdOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The member-side frame rules of `docs/SPOOL_PROTOCOL.md` §4.3/§4.4/§9.2. The frame-set rule is the
 * security boundary of the plane — it is what stops a scope from becoming a general-purpose upload
 * channel — so it is tested as a full accept/reject matrix, in both directions.
 */
class ScopeFramesTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbe"
    private val carol = "ccccccccccccccccccccccccce"
    private val mallory = "mmmmmmmmmmmmmmmmmmmmmmmmmm"
    private val root = ByteArray(32) { it.toByte() }

    private val groupId = "g-00112233445566778899aabb"
    private val founding = setOf(alice, bob, carol)

    private fun scope(
        peer: String = bob,
        secret: ByteArray = root,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(secret, alice, peer),
        keys = ScopeCrypto.dmSealKeys(secret, alice, peer),
        bounds = ScopeRegistry.DEFAULT_BOUNDS,
        peerId = peer,
    )

    private fun groupScope(
        gid: String = groupId,
        roster: Set<String> = founding,
        secret: ByteArray = root,
        version: Int = 1,
    ) = Scope(
        id = ScopeCrypto.groupScopeId(secret, gid, version),
        keys = ScopeCrypto.groupSealKeys(secret, gid, version),
        bounds = ScopeRegistry.DEFAULT_BOUNDS,
        groupId = gid,
        roster = roster,
    )

    @Test
    fun `accepts a v2-sealed DM between the scope's pair, either direction`() {
        val outbound = dmFrame("m1", from = alice, to = bob).envelope
        val inbound = dmFrame("m2", from = bob, to = alice).envelope
        assertTrue(ScopeFrames.eligibleForDm(outbound, alice, bob))
        assertTrue(ScopeFrames.eligibleForDm(inbound, alice, bob))
    }

    @Test
    fun `refuses a DM involving a third party`() {
        val fromStranger = dmFrame("m3", from = mallory, to = alice).envelope
        val toStranger = dmFrame("m4", from = alice, to = mallory).envelope
        assertFalse(ScopeFrames.eligibleForDm(fromStranger, alice, bob))
        assertFalse(ScopeFrames.eligibleForDm(toStranger, alice, bob))
    }

    @Test
    fun `refuses non-chat types, group forms, the broadcast room, and v1 or unsealed payloads`() {
        val receipt = envelope(FrameType.RECEIPT, alice, bob, ChatContent(enc = ratchetEnc()))
        val group =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "g1",
                senderId = alice,
                recipientId = bob,
                group = GroupInfo(id = "g-01", members = listOf(alice, bob), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc())),
            )
        val broadcast = RelayEnvelope(FrameType.CHAT, "b1", alice, payload = WireCodec.encodePayload(ChatContent(body = "hi")))
        val cleartext = envelope(FrameType.CHAT, alice, bob, ChatContent(body = "hi"))
        val v1 = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV1()))
        val groupRatchet = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyWithoutDmHeader()))

        listOf(receipt, group, broadcast, cleartext, v1, groupRatchet).forEach {
            assertFalse("${it.type}/${it.id} must not ride a DM scope", ScopeFrames.eligibleForDm(it, alice, bob))
        }
    }

    @Test
    fun `sealing is deterministic, so every member seals a frame to the same blob id`() {
        val frame = dmFrame("m1", from = alice, to = bob)
        val mine = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val theirs = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        assertArrayEquals(mine.blobId, theirs.blobId)
        assertArrayEquals(mine.blob, theirs.blob)
        assertEquals(ScopeCrypto.SEAL_VERSION, mine.blob[0])
    }

    @Test
    fun `opens a well-formed blob back into its verbatim custody unit`() {
        val frame = dmFrame(frameIdOf("m1"), from = bob, to = alice)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertArrayEquals(frame.sig, opened!!.wire.sig)
        assertArrayEquals(frame.signed, opened.wire.signed)
        assertEquals(frameIdOf("m1"), opened.env.id)
        // §9.4's re-serve shape: a fresh wrapper with a full hop budget, not the sender's counters.
        assertEquals(0, opened.wire.hops)
        assertTrue(opened.wire.ttl > 0)
    }

    @Test
    fun `quarantines a mismatched content address, a tampered blob, and a foreign scope`() {
        val frame = dmFrame(frameIdOf("m1"), from = bob, to = alice)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)

        val wrongId = ScopeCrypto.blobId(byteArrayOf(9, 9, 9))
        assertNull("content address must be re-verified", ScopeFrames.open(scope(), alice, wrongId, sealed.blob))

        val tampered = sealed.blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull("AEAD must reject a flipped byte", ScopeFrames.open(scope(), alice, ScopeCrypto.blobId(tampered), tampered))

        val other = scope(secret = ByteArray(32) { (it + 100).toByte() })
        assertNull("the aad binds the scope id", ScopeFrames.open(other, alice, sealed.blobId, sealed.blob))

        assertNull(
            "a truncated blob is structurally invalid",
            ScopeFrames.open(scope(), alice, ScopeCrypto.blobId(ByteArray(4)), ByteArray(4)),
        )
    }

    @Test
    fun `a blob whose frame fails the frame-set rule is refused even though it opens`() {
        // A blob sealed correctly into the scope but carrying a frame addressed to someone else: the
        // uploader is a scope member, so only the frame-set rule catches this.
        val foreign = dmFrame(frameIdOf("m9"), from = alice, to = mallory)
        val sealed = ScopeFrames.seal(scope(), foreign.sig, foreign.signed)
        assertNull(ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob))
    }

    @Test
    fun `a blob whose frame id is not canonical is refused even though it opens and its frame is eligible`() {
        // #178: the router refuses the same frame, but only refusing it here quarantines the blob. Android's database
        // drops the mark, so custody would hold its unmarked twin and §9.6 would never account it: re-pulled forever.
        val real = frameIdOf("m1")
        for (crafted in listOf("\uFEFF$real", "m1")) {
            val frame = dmFrame(crafted, from = bob, to = alice)
            assertTrue(ScopeFrames.eligibleFor(frame.envelope, alice, scope()))
            val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)
            assertNull(crafted, ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob))
        }
        // The commons door's only gate: a post never meets the router.
        val scope = commonsScope()
        val post = commonsFrame("\uFEFF$real", alice, commonsId)
        val blob = ScopeCrypto.seal(scope.keys, scope.id, post.sig, post.signed)
        assertNull(ScopeFrames.open(scope, alice, ScopeCrypto.blobId(blob), blob))
    }

    @Test
    fun `dead-on-arrival guard trips exactly at the scope ttl, not the mesh custody ttl`() {
        val env = dmFrame("m1", from = alice, to = bob, sentAt = 1_000L).envelope
        val ttl = ScopeRegistry.DEFAULT_TTL_MS
        assertFalse(ScopeFrames.deadOnArrival(env, ttl, now = 1_000L + ttl - 1))
        assertTrue(ScopeFrames.deadOnArrival(env, ttl, now = 1_000L + ttl))
    }

    // --- the group form (spec §4.4's second half) ---

    @Test
    fun `accepts the three group types a group scope carries`() {
        val chat = groupChatFrame("gc1", from = bob, groupId = groupId, members = founding.toList()).envelope
        val update = groupUpdateFrame("gu1", from = bob, groupId = groupId, members = founding.toList()).envelope
        val leave = groupLeaveFrame("gl1", from = bob, groupId = groupId).envelope

        listOf(chat, update, leave).forEach {
            assertTrue("${it.type} must ride its group's scope", ScopeFrames.eligibleFor(it, alice, groupScope()))
        }
    }

    @Test
    fun `a groupleave is matched on its PAYLOAD group id, since the envelope carries none`() {
        val leave = groupLeaveFrame("gl1", from = bob, groupId = groupId).envelope
        // The trap this pins: reading only RelayEnvelope.group would drop every departure, which is the
        // frame that drives the remaining members' leave-rekey and the scope rotation itself.
        assertNull("MeshManager.sendGroupLeave sets no envelope group", leave.group)
        assertTrue(ScopeFrames.eligibleFor(leave, alice, groupScope()))
        assertFalse(ScopeFrames.eligibleFor(leave, alice, groupScope(gid = "g-ffffffffffffffffffffffff")))
    }

    @Test
    fun `a departed member's own leave still rides, because the roster is the FOUNDING one`() {
        // carol has departed, so the effective roster no longer holds her — but her signed leave (and her
        // pre-departure frames) must still reach the others. Safe: the re-mint rotates the scope id.
        val leave = groupLeaveFrame("gl2", from = carol, groupId = groupId).envelope
        assertTrue(ScopeFrames.eligibleFor(leave, alice, groupScope(roster = founding)))
        assertFalse(
            "a sender outside the founding roster is refused",
            ScopeFrames.eligibleFor(leave, alice, groupScope(roster = setOf(alice, bob))),
        )
    }

    @Test
    fun `refuses another group, a non-member sender, a v1 group chat, and a DM`() {
        val otherGroup = groupChatFrame("x1", bob, "g-ffffffffffffffffffffffff", founding.toList()).envelope
        val stranger = groupChatFrame("x2", mallory, groupId, founding.toList()).envelope
        val dm = dmFrame("x3", from = alice, to = bob).envelope
        val v1Group =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "x4",
                senderId = bob,
                group = GroupInfo(id = groupId, members = founding.toList(), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc().copyAsV1())),
            )
        // The DM ratchet header in a group frame is the other half of the form split (§4.4): a scope-bound
        // group frame must carry `g`, never `r`.
        val dmHeaderInGroup =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "x5",
                senderId = bob,
                group = GroupInfo(id = groupId, members = founding.toList(), createdBy = alice),
                payload = WireCodec.encodePayload(ChatContent(enc = ratchetEnc())),
            )

        listOf(otherGroup, stranger, dm, v1Group, dmHeaderInGroup).forEach {
            assertFalse("${it.id} must not ride a group scope", ScopeFrames.eligibleFor(it, alice, groupScope()))
        }
    }

    @Test
    fun `a group frame does not ride a DM scope, and a DM does not ride a group scope`() {
        val groupChat = groupChatFrame("gc2", bob, groupId, founding.toList()).envelope
        assertFalse(ScopeFrames.eligibleFor(groupChat, alice, scope()))
        assertFalse(ScopeFrames.eligibleFor(dmFrame("m1", alice, bob).envelope, alice, groupScope()))
    }

    @Test
    fun `both members' profiles ride a DM scope, matched on sender since a profile addresses nobody`() {
        val mine = profileFrame("p1", from = alice).envelope
        val theirs = profileFrame("p2", from = bob).envelope
        assertNull("fixture must address nobody, or it is not exercising the sender-only match", mine.recipientId)
        assertTrue(ScopeFrames.eligibleForDm(mine, alice, bob))
        assertTrue(ScopeFrames.eligibleForDm(theirs, alice, bob))
    }

    @Test
    fun `a third party's profile rides neither form`() {
        val stranger = profileFrame("p3", from = mallory).envelope
        assertFalse(ScopeFrames.eligibleForDm(stranger, alice, bob))
        assertFalse(ScopeFrames.eligibleForGroup(stranger, groupId, founding))
    }

    @Test
    fun `a founding-roster member's profile rides a group scope, and the roster is the whole rule`() {
        val member = profileFrame("p4", from = carol).envelope
        assertTrue(ScopeFrames.eligibleForGroup(member, groupId, founding))
        // It names no group, so nothing else can gate it — a roster that excludes the sender is the only
        // thing standing between a co-member's prekey and this scope.
        assertFalse(ScopeFrames.eligibleForGroup(member, groupId, setOf(alice, bob)))
    }

    @Test
    fun `a profile blob round-trips through the seal and the frame-set rule`() {
        val frame = profileFrame(FrameId.profileId(bob, 1_000L), from = bob, sentAt = 1_000L)
        val sealed = ScopeFrames.seal(scope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(scope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertEquals(FrameId.profileId(bob, 1_000L), opened!!.env.id)
        assertArrayEquals(frame.signed, opened.wire.signed)
    }

    @Test
    fun `a scope with neither form carries nothing`() {
        val formless = Scope(id = ByteArray(32), keys = ScopeCrypto.dmSealKeys(root, alice, bob), bounds = ScopeRegistry.DEFAULT_BOUNDS)
        assertFalse(ScopeFrames.eligibleFor(dmFrame("m1", alice, bob).envelope, alice, formless))
    }

    @Test
    fun `a group blob round-trips through the seal and the frame-set rule`() {
        val frame = groupChatFrame(frameIdOf("gc3"), from = bob, groupId = groupId, members = founding.toList())
        val sealed = ScopeFrames.seal(groupScope(), frame.sig, frame.signed)
        val opened = ScopeFrames.open(groupScope(), alice, sealed.blobId, sealed.blob)
        assertNotNull(opened)
        assertArrayEquals(frame.signed, opened!!.wire.signed)
        assertEquals(frameIdOf("gc3"), opened.env.id)
        // A rotated scope (fresh root, bumped version) cannot open the old lineage's blob — the aad binds
        // the scope id, and §3.3 says old blobs are never migrated.
        assertNull(ScopeFrames.open(groupScope(secret = ByteArray(32) { 99 }, version = 2), alice, sealed.blobId, sealed.blob))
    }

    private fun envelope(
        type: String,
        from: String,
        to: String,
        content: ChatContent,
    ) = RelayEnvelope(type = type, id = "x", senderId = from, recipientId = to, payload = WireCodec.encodePayload(content))

    private fun ratchetEnc() = dmFrame("seed", alice, bob).let { WireCodec.decodePayload<ChatContent>(it.envelope.payload)!!.enc!! }

    private fun EncEnvelope.copyAsV1() = EncEnvelope(v = 1, nonce = nonce, ct = ct, keys = keys, r = r)

    /** The v3 DM form (ADR 059): the same header, an empty (derived) nonce, one version higher. */
    private fun EncEnvelope.copyAsV3() = EncEnvelope(v = EncEnvelope.VERSION_DM_V3, nonce = ByteArray(0), ct = ct, keys = keys, r = r)

    @Test
    fun `a v3 DM rides the scope and a v3 group form does not`() {
        val v3 = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV3()))
        assertTrue("v3 is the DM form's compact sibling and must stay spool-eligible", ScopeFrames.eligibleForDm(v3, alice, bob))
        val v3GroupShaped = envelope(FrameType.CHAT, alice, bob, ChatContent(enc = ratchetEnc().copyAsV3().copyWithoutDmHeader()))
        assertFalse(ScopeFrames.eligibleForDm(v3GroupShaped, alice, bob))
    }

    private fun EncEnvelope.copyWithoutDmHeader() = EncEnvelope(v = v, nonce = nonce, ct = ct, keys = keys, r = null)

    // C-3.1-5 (ADR 2026-10.j67x): the one refill a retiring DM scope takes — our own unanswered reset.
    private val resetEph = ByteArray(32) { 5 }

    private fun ratchetHeader(
        eph: ByteArray? = resetEph,
        flags: Int = RatchetHeader.FLAG_RESET,
    ) = RatchetHeader(
        se = 1,
        ek =
            ByteArray(32) {
                7
            },
        pe = 0,
        n = 0,
        init = eph?.let { RatchetInit(eph = it, pkid = 1, at = 9L) },
        flags = flags,
    )

    private fun retiring(resetInit: ByteArray?) =
        Scope(
            id = ScopeCrypto.dmScopeId(root, alice, bob),
            keys = ScopeCrypto.dmSealKeys(root, alice, bob),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            retiring = true,
            peerId = bob,
            resetInit = resetInit,
        )

    @Test
    fun `a retiring scope our unanswered reset replaced takes that reset and nothing else`() {
        val carrier = retiring(resetInit = resetEph)
        val reset = dmFrame("r1", from = alice, to = bob, header = ratchetHeader()).envelope
        assertTrue("the reset rides the scope its peer is still on", ScopeFrames.mayRefill(reset, alice, carrier))

        // #90: every frame of the unanswered session carries the same init, but only the flagged one is adopted
        // under the reset floor — and the peer needs nothing else from this scope.
        val unflagged = dmFrame("m1", from = alice, to = bob, header = ratchetHeader(flags = 0)).envelope
        val olderReset = dmFrame("r0", from = alice, to = bob, header = ratchetHeader(eph = ByteArray(32) { 6 })).envelope
        val peersReset = dmFrame("r2", from = bob, to = alice, header = ratchetHeader()).envelope
        val noInit = dmFrame("m2", from = alice, to = bob, header = ratchetHeader(eph = null)).envelope
        val notToThePeer = dmFrame("r3", from = alice, to = carol, header = ratchetHeader()).envelope
        listOf(unflagged, olderReset, peersReset, noInit, notToThePeer).forEach {
            assertFalse("${it.id} must not refill a retiring scope", ScopeFrames.mayRefill(it, alice, carrier))
        }
        // The pull side is the frame-set rule, unchanged: the peer's own frames still drain out of the scope.
        assertTrue(ScopeFrames.eligibleFor(peersReset, alice, carrier))
    }

    @Test
    fun `a plain retiring scope is never refilled, and a live one takes every eligible frame but a reset we left`() {
        val reset = dmFrame("r1", from = alice, to = bob, header = ratchetHeader()).envelope
        assertFalse("C-3.1-3: no pending reset, no refill", ScopeFrames.mayRefill(reset, alice, retiring(resetInit = null)))
        val plain = dmFrame("m1", from = alice, to = bob).envelope
        val unflagged = dmFrame("m2", from = alice, to = bob, header = ratchetHeader(flags = 0)).envelope
        val peersReset = dmFrame("r2", from = bob, to = alice, header = ratchetHeader()).envelope
        listOf(
            plain,
            unflagged,
            peersReset,
        ).forEach { assertTrue("${it.id} rides a live scope", ScopeFrames.mayRefill(it, alice, scope())) }
        val pending = scope().let { Scope(it.id, it.keys, it.bounds, peerId = bob, resetInit = resetEph) }
        assertTrue("the pending reset rides the new scope too", ScopeFrames.mayRefill(reset, alice, pending))
    }

    /**
     * A reset of ours that is no longer the pending one — the race we lost on it, so we adopted the peer's root —
     * is never pushed again, live scope or not: a peer that pulls it after confirming us adopts it as a
     * replacement and the pair ends on two roots.
     */
    @Test
    fun `a reset of ours that is no longer pending is pushed into no DM scope`() {
        val stale = dmFrame("r1", from = alice, to = bob, header = ratchetHeader()).envelope
        assertFalse("a confirmed session's live scope", ScopeFrames.mayRefill(stale, alice, scope()))
        val superseded = scope().let { Scope(it.id, it.keys, it.bounds, peerId = bob, resetInit = ByteArray(32) { 6 }) }
        assertFalse("a later reset is the pending one", ScopeFrames.mayRefill(stale, alice, superseded))
        val pairScope = scope().let { Scope(it.id, it.keys, it.bounds, peerId = bob, pair = true) }
        assertTrue("a pair scope is not the ratchet's and keeps the plain rule", ScopeFrames.mayRefill(stale, alice, pairScope))
    }

    // The commons form (§7.4): a room keyed by an invite secret, no roster.
    private val commonsSecret = ByteArray(32) { (it + 40).toByte() }
    private val commonsId = ScopeCrypto.commonsScopeId(commonsSecret)

    private fun commonsScope() =
        Scope(
            id = commonsId,
            keys = ScopeCrypto.commonsSealKeys(commonsSecret),
            bounds = ScopeRegistry.COMMONS_DEFAULT_BOUNDS,
            commonsId = "c-" + hex(commonsId),
            spoolUrl = "wss://home.test/spool/v1",
        )

    private fun commonsEnv(
        id: String,
        chat: ChatContent? = null,
        reaction: ReactionContent? = null,
    ) = RelayEnvelope(
        type = FrameType.COMMONS,
        id = id,
        senderId = alice,
        payload = WireCodec.encodePayload(CommonsPost(scope = commonsId, chat = chat, reaction = reaction)),
    )

    @Test
    fun `a commons takes an attachment that names its type and its key, and a reaction to a post`() {
        val scope = commonsScope()
        val photo = ChatContent(body = "pic", attachmentHash = "ab".repeat(32), attachmentMime = "image/webp", attachmentKey = "a2V5")
        assertTrue(ScopeFrames.eligibleFor(commonsEnv("i1", chat = photo), alice, scope))
        val file =
            ChatContent(
                attachmentHash = "cd".repeat(32),
                attachmentMime = "application/pdf",
                attachmentName = "menu.pdf",
                attachmentKey = "a2V5",
            )
        assertTrue(ScopeFrames.eligibleFor(commonsEnv("f1", chat = file), alice, scope))
        assertTrue(ScopeFrames.eligibleFor(commonsEnv("r1", reaction = ReactionContent(FrameId.new(), "👍")), alice, scope))
        // A retraction is a reaction with no emoji.
        assertTrue(ScopeFrames.eligibleFor(commonsEnv("r2", reaction = ReactionContent(FrameId.new(), null)), alice, scope))
        assertTrue(ScopeFrames.pushableToCommons(commonsEnv("r3", reaction = ReactionContent(FrameId.new(), "👍")), alice, commonsId))
    }

    @Test
    fun `a commons post carries exactly one content, and a reaction names a canonical post with an emoji the wire takes`() {
        val scope = commonsScope()
        val target = FrameId.new()
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("n1"), alice, scope))
        assertFalse(
            ScopeFrames.eligibleFor(
                commonsEnv("b1", chat = ChatContent(body = "hi"), reaction = ReactionContent(target, "👍")),
                alice,
                scope,
            ),
        )
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("r1", reaction = ReactionContent("not-a-frame-id", "👍")), alice, scope))
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("r2", reaction = ReactionContent(target, "👍".repeat(17))), alice, scope))
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("r3", reaction = ReactionContent(target, " ")), alice, scope))
    }

    @Test
    fun `a commons takes a post naming it and a profile from anyone`() {
        val scope = commonsScope()
        assertTrue(ScopeFrames.eligibleFor(commonsFrame("p1", mallory, commonsId).envelope, alice, scope))
        assertTrue(ScopeFrames.eligibleFor(profileFrame("pr", mallory).envelope, alice, scope))
        assertTrue(ScopeFrames.eligibleFor(profileFrame("me", alice).envelope, alice, scope))
    }

    @Test
    fun `a commons refuses a post naming another room, a sealed post, a malformed attachment, and every other type`() {
        val scope = commonsScope()
        val otherRoom = ScopeCrypto.commonsScopeId(ByteArray(32) { (it + 41).toByte() })
        assertFalse(ScopeFrames.eligibleFor(commonsFrame("x1", alice, otherRoom).envelope, alice, scope))
        val sealed =
            RelayEnvelope(
                type = FrameType.COMMONS,
                id = "s1",
                senderId = alice,
                payload =
                    WireCodec.encodePayload(
                        CommonsPost(
                            scope = commonsId,
                            chat = ChatContent(enc = EncEnvelope(v = 1, nonce = ByteArray(12), ct = ByteArray(4), keys = emptyList())),
                        ),
                    ),
            )
        assertFalse(ScopeFrames.eligibleFor(sealed, alice, scope))
        val withImage =
            RelayEnvelope(
                type = FrameType.COMMONS,
                id = "i1",
                senderId = alice,
                payload = WireCodec.encodePayload(CommonsPost(scope = commonsId, chat = ChatContent(body = "pic", attachmentHash = "ab"))),
            )
        assertFalse(ScopeFrames.eligibleFor(withImage, alice, scope))
        // A well-formed address with no type, or no key, beside it: a commons attachment has no sealed content to carry
        // either, and a key-less one's address would be its plaintext's.
        val noType = ChatContent(body = "pic", attachmentHash = "ab".repeat(32), attachmentKey = "a2V5")
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("i2", noType), alice, scope))
        val noKey = ChatContent(body = "pic", attachmentHash = "ab".repeat(32), attachmentMime = "image/webp")
        assertFalse(ScopeFrames.eligibleFor(commonsEnv("i3", noKey), alice, scope))
        // Addressed to someone, or to a group: not a room post, whatever the payload says.
        val addressed = commonsFrame("a1", alice, commonsId).envelope
        assertFalse(
            ScopeFrames.eligibleFor(
                RelayEnvelope(addressed.type, addressed.id, alice, recipientId = bob, payload = addressed.payload),
                alice,
                scope,
            ),
        )
        assertFalse(ScopeFrames.eligibleFor(dmFrame("d1", from = alice, to = bob).envelope, alice, scope))
        assertFalse(
            ScopeFrames.eligibleFor(
                groupChatFrame("g1", from = alice, groupId = groupId, members = founding.toList()).envelope,
                alice,
                scope,
            ),
        )
        assertFalse(ScopeFrames.eligibleFor(RelayEnvelope(FrameType.REACTION, "r1", alice, payload = ByteArray(0)), alice, scope))
    }

    @Test
    fun `only our own frames are pushable into a commons`() {
        val scope = commonsScope()
        assertTrue(ScopeFrames.pushableToCommons(profileFrame("me", alice).envelope, alice, commonsId))
        assertTrue(ScopeFrames.pushableToCommons(commonsFrame("p1", alice, commonsId).envelope, alice, commonsId))
        assertFalse(ScopeFrames.pushableToCommons(profileFrame("pr", bob).envelope, alice, commonsId))
        assertFalse(ScopeFrames.pushableToCommons(commonsFrame("p2", bob, commonsId).envelope, alice, commonsId))
    }

    @Test
    fun `a commons scope belongs at its relay and nowhere else`() {
        val scope = commonsScope()
        assertTrue(scope.belongsAt("wss://home.test/spool/v1"))
        assertFalse(scope.belongsAt("wss://other.test/spool/v1"))
        assertTrue(scope().belongsAt("wss://other.test/spool/v1"))
        assertEquals("c-" + hex(commonsId), scope.label)
    }

    @Test
    fun `open refuses a post re-sealed from another room even under the right key`() {
        val scope = commonsScope()
        val otherRoom = ScopeCrypto.commonsScopeId(ByteArray(32) { (it + 41).toByte() })
        val foreign = commonsFrame(frameIdOf("x1"), alice, otherRoom)
        val blob = ScopeCrypto.seal(scope.keys, scope.id, foreign.sig, foreign.signed)
        assertNull(ScopeFrames.open(scope, alice, ScopeCrypto.blobId(blob), blob))
        val own = commonsFrame(frameIdOf("p1"), alice, commonsId)
        val good = ScopeCrypto.seal(scope.keys, scope.id, own.sig, own.signed)
        assertNotNull(ScopeFrames.open(scope, alice, ScopeCrypto.blobId(good), good))
    }

    @Test
    fun `hex is the lowercase display form, byte for byte`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(all.joinToString("") { "%02x".format(it) }, hex(all))
        assertEquals("", hex(ByteArray(0)))
        assertEquals("00ff7f80", hex(byteArrayOf(0, -1, 127, -128)))
    }

    // The cast set (docs/VOICE_CALLS.md §2.7): a walkie's `talk` frame, on a pair's own DM scope, never stored.
    private fun talk(
        from: String,
        to: String,
        id: String = "t1",
    ) = RelayEnvelope(type = FrameType.TALK, id = id, senderId = from, recipientId = to, payload = ByteArray(24) { it.toByte() })

    private fun pairScope() =
        Scope(
            id =
                ByteArray(32) {
                    7
                },
            keys = scope().keys,
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            peerId = bob,
            pair = true,
        )

    @Test
    fun `the cast set takes a talk frame between the pair, in the direction named`() {
        assertTrue(ScopeFrames.castEligible(talk(from = bob, to = alice), alice, scope(), inbound = true))
        assertTrue(ScopeFrames.castEligible(talk(from = alice, to = bob), alice, scope(), inbound = false))
        // The wrong way round for the direction, our own frame coming back, a third party either way.
        assertFalse(ScopeFrames.castEligible(talk(from = alice, to = bob), alice, scope(), inbound = true))
        assertFalse(ScopeFrames.castEligible(talk(from = bob, to = alice), alice, scope(), inbound = false))
        assertFalse(ScopeFrames.castEligible(talk(from = mallory, to = alice), alice, scope(), inbound = true))
        assertFalse(ScopeFrames.castEligible(talk(from = bob, to = carol), alice, scope(), inbound = true))
    }

    @Test
    fun `the cast set refuses every other type, a group form, and every scope but a pair's own DM one`() {
        val chat = dmFrame("c1", from = bob, to = alice).envelope
        assertFalse(
            "a chat frame is custodial and rides push, never a cast",
            ScopeFrames.castEligible(chat, alice, scope(), inbound = true),
        )
        assertFalse(ScopeFrames.castEligible(profileFrame("p1", bob).envelope, alice, scope(), inbound = true))
        val grouped =
            RelayEnvelope(
                type = FrameType.TALK,
                id = "g",
                senderId = bob,
                recipientId = alice,
                group = GroupInfo(id = groupId, members = listOf(alice, bob), createdBy = alice),
                payload = ByteArray(24),
            )
        assertFalse(ScopeFrames.castEligible(grouped, alice, scope(), inbound = true))
        assertFalse(ScopeFrames.castEligible(talk(from = bob, to = alice), alice, pairScope(), inbound = true))
        assertFalse(ScopeFrames.castEligible(talk(from = bob, to = alice), alice, groupScope(), inbound = true))
        assertFalse(ScopeFrames.castEligible(talk(from = bob, to = alice), alice, commonsScope(), inbound = true))
    }

    @Test
    fun `an opened cast is a delivery to this phone alone, its signature kept`() {
        val sig = ByteArray(ScopeCrypto.SIG_BYTES) { 3 }
        val signed = WireCodec.encodeEnvelope(talk(from = bob, to = alice))
        val opened = ScopeFrames.openCast(scope(), alice, ScopeFrames.sealCast(scope(), sig, signed))!!
        assertEquals(0, opened.wire.ttl)
        assertEquals(0, opened.wire.hops)
        assertFalse(opened.wire.relay)
        assertArrayEquals(sig, opened.wire.sig)
        assertArrayEquals(signed, opened.wire.signed)
        assertEquals(FrameType.TALK, opened.env.type)
    }

    @Test
    fun `a cast that fails its seal or its set opens to nothing`() {
        val sig = ByteArray(ScopeCrypto.SIG_BYTES) { 3 }
        val inbound = WireCodec.encodeEnvelope(talk(from = bob, to = alice))
        // Another scope's key, our own frame coming back, a chat frame cast anyway, and a frame blob passed as a cast.
        assertNull(ScopeFrames.openCast(scope(), alice, ScopeFrames.sealCast(scope(secret = ByteArray(32) { 9 }), sig, inbound)))
        assertNull(
            ScopeFrames.openCast(
                scope(),
                alice,
                ScopeFrames.sealCast(scope(), sig, WireCodec.encodeEnvelope(talk(from = alice, to = bob))),
            ),
        )
        assertNull(
            ScopeFrames.openCast(
                scope(),
                alice,
                ScopeFrames.sealCast(scope(), sig, WireCodec.encodeEnvelope(dmFrame("c2", bob, alice).envelope)),
            ),
        )
        assertNull(ScopeFrames.openCast(scope(), alice, ScopeFrames.seal(scope(), sig, inbound).blob))
    }
}
