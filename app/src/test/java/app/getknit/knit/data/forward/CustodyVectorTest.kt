package app.getknit.knit.data.forward

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.VectorFiles
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The custody contract every port runs (`vectors/custody-v1.json`, ADR 2026-10.7mqm): small limits, a sequence of frames
 * stored one at a time, and after each the store's answer, the live frames it evicted and the live set it holds. Another
 * port replays the steps through its own store and must reproduce every answer, eviction and set, or two nodes holding
 * the same frames hold different custody and their digests never agree (ADR 006). The steps cover each bucket's trim
 * (sender, group, room: newest-N), the global cap's largest-bucket-first with both tie-breaks, a frame evicted by its own
 * write, expired residue that counts toward nothing, the dead-on-arrival and future-window refusals, the group gate, and
 * the byte cap (#227): a frame of exactly `maxSignedBytes` kept, one byte over refused, and an oversize frame that also
 * fails the group gate refused; and the profile cap after it (#234): a `profile` of exactly `maxProfileSignedBytes`
 * kept, one byte over refused, and a chat frame of that same size kept. Each step records the length of the `signed`
 * bytes the store is handed (`signedBytes`), which a port reproduces before it replays the step, so a padded frame that
 * encodes differently fails as that and not as a custody disagreement.
 *
 * Written by the real [ForwardRepository] over Room only with `KNIT_WRITE_VECTORS=1`. Checked two ways: the file's steps
 * replayed through the store, as another port reads them, and the file compared byte for byte with a regeneration, so
 * the scenario below and the file cannot drift apart.
 */
class CustodyVectorTest : RoomDbTest() {
    @Test
    fun `the store reproduces every step of the custody vector`() =
        runTest {
            assumeFalse("KNIT_WRITE_VECTORS=1 rewrites the file instead", VectorFiles.writing)
            val file = VectorFiles.read(FILE)
            val store = store(file.getValue("limits").jsonObject)
            file.getValue("steps").jsonArray.forEachIndexed { n, element ->
                val step = element.jsonObject
                val now = step.getValue("now").jsonPrimitive.long
                val frame = frameOf(step.getValue("frame").jsonObject)
                val label = "step ${n + 1} (${step.getValue("note").jsonPrimitive.content})"
                assertEquals("$label: signedBytes", step.getValue("signedBytes").jsonPrimitive.int, WireCodec.encodeEnvelope(frame).size)
                val outcome = apply(store, frame, now)
                assertEquals("$label: kept", step.getValue("kept").jsonPrimitive.boolean, outcome.kept)
                assertEquals("$label: evicted", step.getValue("evicted").strings(), outcome.evicted)
                assertEquals("$label: held", step.getValue("held").strings(), outcome.held)
            }
        }

    @Test
    fun `the vector file is what the scenario regenerates, byte for byte`() =
        runTest {
            assumeFalse("KNIT_WRITE_VECTORS=1 rewrites the file instead", VectorFiles.writing)
            assertEquals(VectorFiles.text(FILE), VectorFiles.render(build()))
        }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the custody vector from the scenario`() =
        runTest {
            assumeTrue(VectorFiles.writing)
            VectorFiles.write(FILE, build())
        }

    private class Outcome(
        val kept: Boolean,
        val evicted: List<String>,
        val held: List<String>,
    )

    private class Step(
        val now: Long,
        val frame: RelayEnvelope,
        val note: String,
    )

    private suspend fun apply(
        store: ForwardRepository,
        frame: RelayEnvelope,
        now: Long,
    ): Outcome {
        val before = store.liveIds(now).toSet()
        val kept =
            store.store(
                CarriedFrame(frame, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(frame)),
                ForwardStore.ORIGIN_RELAY,
                now,
            )
        val after = store.liveIds(now).toSet()
        return Outcome(kept, (before - after).sorted(), after.sorted())
    }

    private fun store(limits: JsonObject): ForwardRepository =
        ForwardRepository(
            db.forwardDao(),
            StoreDigest(),
            db,
            ttlMs = limits.getValue("ttlMs").jsonPrimitive.long,
            broadcastTtlMs = limits.getValue("broadcastTtlMs").jsonPrimitive.long,
            maxRows = limits.getValue("maxRows").jsonPrimitive.int,
            maxSignedBytes = limits.getValue("maxSignedBytes").jsonPrimitive.int,
            maxProfileSignedBytes = limits.getValue("maxProfileSignedBytes").jsonPrimitive.int,
            maxPerSender = limits.getValue("maxPerSender").jsonPrimitive.int,
            maxPerGroup = limits.getValue("maxPerGroup").jsonPrimitive.int,
            maxBroadcast = limits.getValue("maxBroadcast").jsonPrimitive.int,
        )

    /** The vector, built by running [scenario] through a store with [LIMITS]. */
    private suspend fun build(): JsonObject {
        val limits = limitsJson()
        val store = store(limits)
        return buildJsonObject {
            put("about", ABOUT)
            put("limits", limits)
            putJsonArray("steps") {
                scenario().forEach { step ->
                    val outcome = apply(store, step.frame, step.now)
                    addJsonObject {
                        put("note", step.note)
                        put("now", step.now)
                        put("frame", frameJson(step.frame))
                        put("signedBytes", WireCodec.encodeEnvelope(step.frame).size)
                        put("isRoomChat", isRoomChat(step.frame))
                        put("expiresAt", step.frame.sentAt + if (isRoomChat(step.frame)) BROADCAST_TTL_MS else TTL_MS)
                        put("kept", outcome.kept)
                        putJsonArray("evicted") { outcome.evicted.forEach { add(it) } }
                        putJsonArray("held") { outcome.held.forEach { add(it) } }
                    }
                }
            }
        }
    }

    private fun limitsJson(): JsonObject =
        buildJsonObject {
            put("maxRows", 8)
            put("maxSignedBytes", MAX_SIGNED_BYTES)
            put("maxProfileSignedBytes", MAX_PROFILE_SIGNED_BYTES)
            put("maxPerSender", 4)
            put("maxPerGroup", 3)
            put("maxBroadcast", 3)
            put("ttlMs", TTL_MS)
            put("broadcastTtlMs", BROADCAST_TTL_MS)
            put("maxFutureSkewMs", Protocol.MAX_FUTURE_SKEW_MS)
        }

    private fun isRoomChat(frame: RelayEnvelope): Boolean = frame.type == FrameType.CHAT && frame.recipientId == null && frame.group == null

    private fun frameJson(frame: RelayEnvelope): JsonObject =
        buildJsonObject {
            put("id", frame.id)
            put("type", frame.type)
            put("senderId", frame.senderId)
            frame.recipientId?.let { put("recipientId", it) }
            frame.group?.let { group ->
                putJsonObject("group") {
                    put("id", group.id)
                    putJsonArray("members") { group.members.forEach { add(it) } }
                    group.departed?.let { departed -> putJsonArray("departed") { departed.forEach { add(it) } } }
                }
            }
            put("sentAt", frame.sentAt)
            val bodyLength = WireCodec.decodePayload<ChatContent>(frame.payload)?.body?.length ?: 0
            if (bodyLength > 0) put("bodyLength", bodyLength)
        }

    private fun frameOf(json: JsonObject): RelayEnvelope =
        RelayEnvelope(
            type = json.getValue("type").jsonPrimitive.content,
            id = json.getValue("id").jsonPrimitive.content,
            senderId = json.getValue("senderId").jsonPrimitive.content,
            sentAt = json.getValue("sentAt").jsonPrimitive.long,
            recipientId = json["recipientId"]?.jsonPrimitive?.content,
            group =
                json["group"]?.jsonObject?.let { group ->
                    GroupInfo(
                        id = group.getValue("id").jsonPrimitive.content,
                        members = group.getValue("members").strings(),
                        createdBy = group.getValue("members").strings().first(),
                        departed = group["departed"]?.strings(),
                    )
                },
            payload = body(json["bodyLength"]?.jsonPrimitive?.int ?: 0),
        )

    /** A chat payload whose body is [length] "x"s: how the byte-cap steps pad a frame to an exact `signed` size. */
    private fun body(length: Int): ByteArray = WireCodec.encodePayload(ChatContent(body = "x".repeat(length)))

    /**
     * The frame [frame] builds around a payload, padded to exactly [signedBytes] of `signed`: the body's length is
     * searched upward, since CBOR's length prefixes grow at 24, 256 and 65,536 and no fixed offset holds.
     */
    private fun sized(
        signedBytes: Int,
        frame: (ByteArray) -> RelayEnvelope,
    ): RelayEnvelope =
        (0..signedBytes)
            .asSequence()
            .map { frame(body(it)) }
            .first { WireCodec.encodeEnvelope(it).size >= signedBytes }
            .also { check(WireCodec.encodeEnvelope(it).size == signedBytes) { "no body length makes $signedBytes signed bytes" } }

    private fun kotlinx.serialization.json.JsonElement.strings(): List<String> = (this as JsonArray).map { it.jsonPrimitive.content }

    private fun dm(
        id: String,
        sender: String,
        sentAt: Long,
        payload: ByteArray = EMPTY,
    ) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = sender, sentAt = sentAt, recipientId = "bob", payload = payload)

    private fun room(
        id: String,
        sender: String,
        sentAt: Long,
    ) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = sender, sentAt = sentAt, payload = EMPTY)

    /** A `profile` frame: custody never decodes its payload, so the padded chat payload stands in for one. */
    private fun profile(
        id: String,
        sender: String,
        sentAt: Long,
        payload: ByteArray,
    ) = RelayEnvelope(type = FrameType.PROFILE, id = id, senderId = sender, sentAt = sentAt, payload = payload)

    private fun group(
        id: String,
        sender: String,
        sentAt: Long,
        roster: GroupInfo = founding,
        payload: ByteArray = EMPTY,
    ) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = sender, sentAt = sentAt, group = roster, payload = payload)

    /**
     * The steps, each with the reason it is here. The outcomes are the store's, never written by hand; a note that no
     * longer matches its step's outcome is a note to fix, and the regeneration test shows the step that moved.
     */
    @Suppress("LongMethod") // one flat list of steps reads better than any split of it
    private fun scenario(): List<Step> {
        val t = T0
        val later = T0 + 45_000L
        return listOf(
            Step(t, dm("a1", "alice", t - 90_000), "alice's DMs fill her sender bucket"),
            Step(t, dm("a2", "alice", t - 80_000), "alice"),
            Step(t, dm("a3", "alice", t - 70_000), "alice"),
            Step(t, dm("a4", "alice", t - 60_000), "alice: her bucket is full at 4"),
            Step(t, dm("a5", "alice", t - 50_000), "sender quota: alice keeps her newest 4, a1 goes"),
            Step(t, dm("a0", "alice", t - 95_000), "older than alice's newest 4: evicted by its own write, kept=false"),
            Step(t, room("r1", "bob", t - 40_000), "room posts"),
            Step(t, room("r2", "carol", t - 39_000), "room"),
            Step(t, room("r3", "bob", t - 38_000), "room: full at 3"),
            Step(t, room("r4", "dave", t - 37_000), "room quota keeps the newest 3 whoever wrote them: r1 goes though bob holds two"),
            Step(t, group("g1", "alice", t - 30_000), "a group frame counts in alice's sender bucket too: she holds 5, a2 goes"),
            Step(
                t,
                group("g2", "carol", t - 29_000, departedRoster),
                "a frame naming bob as departed derives and is carried; the store is at its cap of 8",
            ),
            Step(t, group("g3", "bob", t - 28_000), "over the cap: alice holds the most (4) and her oldest, a3, goes"),
            Step(t, group("g4", "carol", t - 27_000), "group quota keeps the group's newest 3: g1 goes"),
            Step(t, group("x1", "mallory", t - 26_000), "group gate: a sender outside the roster, refused"),
            Step(t, group("x2", "mallory", t - 26_000, smuggled), "group gate: a roster that does not derive to the id, refused"),
            Step(t, group("x3", "alice", t - 26_000, truncated), "group gate: a pre-2.3.0 roster after a departure, refused"),
            Step(t, dm("e2", "erin", t - 20_000), "over the cap: carol holds the most (a room post, two group frames), r2 goes"),
            Step(t, dm("e1", "erin", t - 20_000), "alice, bob, carol and erin hold two each: alice's oldest frame is the oldest, a4 goes"),
            Step(t, dm("f1", "frank", t - 20_000), "bob, carol and erin hold two each: bob's r3 is the oldest of their frames"),
            Step(t, dm("z1", "zed", t - TTL_MS - 1), "dead on arrival: past its frame-global expiry, refused"),
            Step(t, dm("z2", "zed", t + Protocol.MAX_FUTURE_SKEW_MS + 1), "past the future window, refused"),
            Step(
                t,
                dm("z3", "zed", t + Protocol.MAX_FUTURE_SKEW_MS),
                "at the window's edge, stored; carol's oldest is older than erin's, g2 goes",
            ),
            Step(later, dm("h1", "heidi", later - 1_000), "r4 has expired: 7 live rows, so h1 fits under the cap and nothing goes"),
            Step(
                later,
                dm("s1", "sybil", later + Protocol.MAX_FUTURE_SKEW_MS - 3),
                "a flood at the window's edge: erin holds the most, two at one sentAt, the lower id goes",
            ),
            Step(
                later,
                dm("s2", "sybil", later + Protocol.MAX_FUTURE_SKEW_MS - 2),
                "the flood's bucket is now the largest and pays for the cap: s1 goes",
            ),
            Step(later, dm("s3", "sybil", later + Protocol.MAX_FUTURE_SKEW_MS - 1), "and again: s2 goes, the honest frames stay"),
            Step(
                later,
                sized(MAX_SIGNED_BYTES) { dm("w1", "walt", later - 500, it) },
                "byte cap: signed of exactly maxSignedBytes is kept; the full store evicts for it as for any frame, " +
                    "everyone holds one and alice's a5 is oldest",
            ),
            Step(
                later,
                sized(MAX_SIGNED_BYTES + 1) { dm("w2", "walt", later - 400, it) },
                "byte cap: one byte over is refused, and nothing is evicted to make room",
            ),
            Step(
                later,
                sized(MAX_SIGNED_BYTES + 1) { group("w3", "mallory", later - 300, smuggled, it) },
                "byte cap: oversize and a roster that does not derive, refused, nothing evicted",
            ),
            Step(
                later,
                sized(MAX_PROFILE_SIGNED_BYTES) { profile("p1", "pat", later - 200, it) },
                "profile cap: a profile of exactly maxProfileSignedBytes is kept; the full store evicts for it as for any frame",
            ),
            Step(
                later,
                sized(MAX_PROFILE_SIGNED_BYTES + 1) { profile("p2", "pat", later - 100, it) },
                "profile cap: a profile one byte over is refused, and nothing is evicted to make room",
            ),
            Step(
                later,
                sized(MAX_PROFILE_SIGNED_BYTES + 1) { dm("w4", "walt", later - 50, it) },
                "profile cap: a chat frame of that same size is kept, since the cap is the profile type's alone",
            ),
        )
    }

    private val founding =
        GroupInfo(
            id = Conversations.groupIdFor(listOf("alice", "bob", "carol")),
            members = listOf("alice", "bob", "carol"),
            createdBy = "alice",
        )

    // A frame from a member who recorded bob's leave: bob moves to departed, the id still derives.
    private val departedRoster = founding.copy(members = listOf("alice", "carol"), departed = listOf("bob"))

    private val smuggled = founding.copy(members = listOf("alice", "bob", "carol", "mallory"))

    private val truncated = founding.copy(members = listOf("alice", "carol"))

    private companion object {
        const val FILE = "custody-v1.json"
        const val T0 = 1_800_000_000_000L
        const val TTL_MS = 100_000L
        const val BROADCAST_TTL_MS = 60_000L
        const val MAX_SIGNED_BYTES = 2_048

        // Below the byte cap, so a profile between the two shows the profile cap's own refusal (#234).
        const val MAX_PROFILE_SIGNED_BYTES = 1_024
        val EMPTY: ByteArray = WireCodec.encodePayload(ChatContent(body = ""))
        const val ABOUT =
            "Knit's custody rule (ADR 006, ADR 2026-10.7mqm). Replay `steps` in order through one store with `limits`, " +
                "each frame stored at its step's `now`. A sender's, group's or room's bucket over its quota keeps its newest " +
                "by (sentAt, id). Then, while the live rows exceed maxRows, evict one: the oldest (sentAt, id) frame of the " +
                "sender holding the most live rows, a tie for most going to the sender whose oldest frame is oldest. Counts " +
                "and evictions read live rows only (expiresAt >= now; expiresAt = sentAt + broadcastTtlMs for room chat, " +
                "ttlMs otherwise). Refuse a frame with expiresAt < now, with sentAt > now + maxFutureSkewMs, or naming a " +
                "group whose members + departed do not derive to its id or whose sender is not a member. Before all of those, " +
                "refuse a frame whose signed bytes are longer than maxSignedBytes (the signature is not counted), and right after it a " +
                "frame of type `profile` whose signed bytes are longer than maxProfileSignedBytes; a frame over both counts as " +
                "the byte cap's. A frame's `bodyLength` (absent means 0) pads its chat body with that many \"x\"; a `profile` " +
                "frame's payload is that same chat payload, since custody never decodes a profile's payload; " +
                "`signedBytes` is the length of the " +
                "encoded envelope the store is handed, which a port must reproduce before replaying the step. `kept` is whether " +
                "the store holds the frame after its own write; `evicted` the live ids that write removed; `held` the live set " +
                "after it. Ids and sender ids compare bytewise."
    }
}
