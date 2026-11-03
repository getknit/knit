package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.crypto.MessageContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals

/**
 * Stage 5 of `ratchet-v1.json` (companion change A9, feeding iOS's A10): the `carol` section. Carol is the iPhone —
 * `ios-emitted-v1.json`'s identity, prekey `carolSpk1` — and these are her DM sessions with the two Android parties:
 * both-initiate races she wins (against Bob, whose node id sorts after hers) and loses (against Alice, whose sorts
 * before), each in every arrival order that records something different, and a plain first contact each way with
 * Alice. Every draw is recorded per party, Carol's included, so iOS replays her side by injecting them and compares
 * its own seals byte for byte.
 *
 * The first frames after an opening DM are the delivery ticks the receiver's pipeline seals back
 * (`InboundPipeline.sealDmReceipt`: a `CTL_RECEIPT` acking the DM, v3 between these peers), recorded as
 * `sealedByPipeline` steps right after the open that causes them; Carol's ticks follow the same rule. The Android
 * receiver of each scenario is replayed through `InboundPipeline` by `InboundPipelineTest`. Seeds from 200.
 */
internal class RatchetCarolVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
    private val carol: VectorParty,
    private val aliceSpk1: VectorPrekey,
    private val bobSpk1: VectorPrekey,
    private val carolSpk1: VectorPrekey,
    private val t0: Long,
) {
    private fun transcript(
        name: String,
        note: String,
        pair: Pair<String, String>,
        plans: Map<String, List<Draw>>,
        android: String,
    ): Transcript {
        val identities = mapOf("alice" to alice, "bob" to bob, "carol" to carol)
        val prekeys = mapOf("alice" to aliceSpk1, "bob" to bobSpk1, "carol" to carolSpk1)
        val names = listOf(pair.first, pair.second)
        val t =
            Transcript(
                name,
                note,
                names.map { TranscriptParty(it, identities.getValue(it), listOf(prekeys.getValue(it)), plans[it].orEmpty()) },
                recordFloors = true,
            )
        t.pin(pair.first, pair.second, prekeys.getValue(pair.second))
        t.pin(pair.second, pair.first, prekeys.getValue(pair.first))
        t.field("pipelineReplay", JsonPrimitive(true))
        t.field(
            "pipelineReceivers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", android)
                        put("replaysOwnSeals", true)
                    },
                )
            },
        )
        t.pipelineChecked = { actor, op, hasApplies ->
            when {
                actor != android -> false
                op == "seal" -> true
                op == "pipelineSeal" -> false
                else -> hasApplies
            }
        }
        return t
    }

    suspend fun scenarios(): JsonArray =
        buildJsonArray {
            add(raceBobCarol(Order.WINNER_FIRST))
            add(raceBobCarol(Order.LOSER_FIRST))
            add(raceBobCarol(Order.LATE_LOSER))
            add(raceAliceCarol(Order.WINNER_FIRST))
            add(raceAliceCarol(Order.LOSER_FIRST))
            add(raceAliceCarol(Order.LATE_LOSER))
            add(carolInitiatesToAlice())
            add(aliceInitiatesToCarol())
        }

    /** Which opening DM reaches its receiver first; LATE_LOSER: the loser's reaches the winner only after confirmation. */
    private enum class Order { WINNER_FIRST, LOSER_FIRST, LATE_LOSER }

    /**
     * [actor] opens [frame] (a plain DM) and its pipeline seals the delivery tick back at the same `now`; the open's
     * `applies.tickStep` names the tick's step. Returns the tick.
     */
    private suspend fun Transcript.openAndTick(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        note: String? = null,
    ): TranscriptFrame {
        val tickStep = nextStep + 1
        val applies =
            buildJsonObject {
                putJsonObject("message") {
                    put("id", frame.env.id)
                    put("body", frame.body)
                }
                put("receipts", buildJsonArray { })
                put("acksBack", true)
                put("tickStep", tickStep)
            }
        open(actor, frame, at, OPENED, applies = applies, note = note)
        val tick =
            sealContent(
                actor,
                frame.sender.name,
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = frame.env.id),
                at,
                v3 = true,
                SealOptions(byPipeline = true, note = "the delivery tick InboundPipeline.sealDmReceipt seals back"),
            )
        assertEquals("the tick is the step the open named", tickStep, tick.step)
        return tick
    }

    /** [actor] opens [tick] — a `CTL_RECEIPT` — and records the receipt for the DM it acks; ctl frames are never acked. */
    private suspend fun Transcript.openTick(
        actor: String,
        tick: TranscriptFrame,
        acked: TranscriptFrame,
        at: Long,
        note: String? = null,
    ) {
        val applies =
            buildJsonObject {
                putJsonArray("receipts") {
                    add(
                        buildJsonObject {
                            put("id", acked.env.id)
                            put("acker", tick.sender.name)
                        },
                    )
                }
                put("acksBack", false)
            }
        open(actor, tick, at, OPENED, applies = applies, note = note)
    }

    // ---- bob ↔ carol: the iPhone wins -----------------------------------------------------------------------

    private suspend fun raceBobCarol(order: Order): JsonObject {
        val name =
            when (order) {
                Order.WINNER_FIRST -> "raceBobCarolWinnerFirst"
                Order.LOSER_FIRST -> "raceBobCarolLoserFirst"
                Order.LATE_LOSER -> "raceBobCarolLateLoser"
            }
        val carolPlan =
            listOf(Draw.Key(200), Draw.Key(201)) + listOfNotNull(Draw.Key(207).takeIf { order == Order.LATE_LOSER })
        val t =
            transcript(
                name,
                raceNote("Carol", "Bob", order) +
                    " Carol (the iPhone) wins: her node id sorts before Bob's. The winner is the receiver here only in " +
                    "Carol's steps, so the Android replay is Bob, the loser.",
                "carol" to "bob",
                mapOf("carol" to carolPlan, "bob" to listOf(Draw.Key(203), Draw.Key(204), Draw.Key(206))),
                android = "bob",
            )
        t.race(winner = "carol", loser = "bob", winnerBody = "Hi Bob", loserBody = "Hi Carol", order = order)
        return t.json()
    }

    // ---- alice ↔ carol: the iPhone loses --------------------------------------------------------------------

    private suspend fun raceAliceCarol(order: Order): JsonObject {
        val name =
            when (order) {
                Order.WINNER_FIRST -> "raceAliceCarolWinnerFirst"
                Order.LOSER_FIRST -> "raceAliceCarolLoserFirst"
                Order.LATE_LOSER -> "raceAliceCarolLateLoser"
            }
        val alicePlan =
            listOf(Draw.Key(210), Draw.Key(211)) + listOfNotNull(Draw.Key(217).takeIf { order == Order.LATE_LOSER })
        val t =
            transcript(
                name,
                raceNote("Alice", "Carol", order) +
                    " Carol (the iPhone) loses: Alice's node id sorts before hers, so Carol adopts Alice's root. The " +
                    "Android replay is Alice, the winner.",
                "alice" to "carol",
                mapOf("alice" to alicePlan, "carol" to listOf(Draw.Key(213), Draw.Key(214), Draw.Key(216))),
                android = "alice",
            )
        t.race(winner = "alice", loser = "carol", winnerBody = "Hi Carol", loserBody = "Hi Alice", order = order)
        return t.json()
    }

    private fun raceNote(
        winner: String,
        loser: String,
        order: Order,
    ): String {
        val shape =
            "A both-initiate race (ADR 2026-09.pz9g, FSR §7): $winner and $loser each seal an opening DM to the other's " +
                "prekey before hearing from the other. The init whose initiator has the smaller node id wins, never the " +
                "earlier stamp (RatchetEngine.resolveRace): the loser adopts the winner's root (its own drains as " +
                "prevRoot, its receive state is purged, the adoption starts its replacement floor); the winner reads the " +
                "loser's DM under the loser's root and keeps its root, anchors the loser's init by its ephemeral, and " +
                "drains the loser's root as prevRoot, without taking its epoch as a DH base. Both openers are v3, as " +
                "CryptoScheme picks between these peers (FSR §5, ADR 059): the init is bound into the AAD, and each root " +
                "candidate derives its own nonce."
        val sequence =
            when (order) {
                Order.WINNER_FIRST -> {
                    " The winner's DM reaches the loser first: the loser adopts on it and its tick back is its first frame " +
                        "under the winning root; the winner then reads the loser's DM and ticks it from its own still " +
                        "unconfirmed session; the loser's tick confirms the winner."
                }

                Order.LOSER_FIRST -> {
                    " The loser's DM reaches the winner first (anchored and drained, ticked from the winner's unconfirmed " +
                        "session), " +
                        "then the winner's reaches the loser, which adopts; the same frames as the winner-first order, " +
                        "opened the other way round."
                }

                Order.LATE_LOSER -> {
                    " #83: the loser adopts and ticks the winner's DM, the tick confirms the winner, and only then does the " +
                        "loser's own opening DM reach the winner. The remnant guard refuses its init, and the loser's root " +
                        "is offered as a trailing read-only candidate (RatchetEngine.remnantCandidates): the DM opens and, " +
                        "unlike a race read, records nothing (no anchor, no prevRoot), and is ticked from the winner's confirmed session (a fresh epoch, the healing rule)."
                }
            }
        return shape + sequence
    }

    /** The race in [order]: both opening DMs (v3, the init on each), the loser's adoption, the ticks each way. */
    private suspend fun Transcript.race(
        winner: String,
        loser: String,
        winnerBody: String,
        loserBody: String,
        order: Order,
    ) {
        // v3, as CryptoScheme picks between these peers (FSR §5, ADR 059): the init rides a v3 opener too.
        val w0 = seal(winner, loser, winnerBody, t0, v3 = true)
        val l0 = seal(loser, winner, loserBody, t0 + SECOND / 2, v3 = true)
        when (order) {
            Order.WINNER_FIRST -> {
                val lt = openAndTick(loser, w0, t0 + 2 * SECOND, note = "the loser adopts the winner's root")
                val wt =
                    openAndTick(
                        winner,
                        l0,
                        t0 + 3 * SECOND,
                        note = "the winner keeps its root, anchors the loser's init and drains its root as prevRoot",
                    )
                openTick(winner, lt, w0, t0 + 4 * SECOND, note = "the loser's tick under the winning root confirms the winner")
                openTick(loser, wt, l0, t0 + 5 * SECOND)
            }

            Order.LOSER_FIRST -> {
                val wt =
                    openAndTick(
                        winner,
                        l0,
                        t0 + 2 * SECOND,
                        note = "the winner keeps its root, anchors the loser's init and drains its root as prevRoot",
                    )
                val lt = openAndTick(loser, w0, t0 + 3 * SECOND, note = "the loser adopts the winner's root")
                openTick(loser, wt, l0, t0 + 4 * SECOND)
                openTick(winner, lt, w0, t0 + 5 * SECOND, note = "the loser's tick under the winning root confirms the winner")
            }

            Order.LATE_LOSER -> {
                val lt = openAndTick(loser, w0, t0 + 2 * SECOND, note = "the loser adopts the winner's root")
                openTick(winner, lt, w0, t0 + 3 * SECOND, note = "confirms the winner, which has never seen the loser's init")
                val wt =
                    openAndTick(
                        winner,
                        l0,
                        t0 + 10 * SECOND,
                        note = "#83: the late loser's opening DM, read under its own root, never anchored",
                    )
                openTick(loser, wt, l0, t0 + 11 * SECOND)
            }
        }
    }

    // ---- plain first contact, each way ----------------------------------------------------------------------

    private suspend fun carolInitiatesToAlice(): JsonObject =
        transcript(
            "carolInitiatesToAlice",
            "FSR §3: Carol (the iPhone) initiates to Alice's prekey outside any race — her opening DM v3 with the init. " +
                "Alice responds from it and ticks it back from her first epoch, which confirms Carol; Alice's reply " +
                "rides that epoch, and Carol's tick for it opens Carol's second epoch (the healing rule).",
            "carol" to "alice",
            mapOf("carol" to listOf(Draw.Key(220), Draw.Key(221), Draw.Key(224)), "alice" to listOf(Draw.Key(223))),
            android = "alice",
        ).apply {
            val c0 = seal("carol", "alice", "Hi Alice", t0, v3 = true)
            val at = openAndTick("alice", c0, t0 + 2 * SECOND, note = "Alice responds: a confirmed session from the first frame")
            openTick("carol", at, c0, t0 + 3 * SECOND, note = "Alice sealed against Carol's epoch: Carol confirms")
            val a1 = seal("alice", "carol", "Hi Carol", t0 + 10 * SECOND, v3 = true)
            val ct = openAndTick("carol", a1, t0 + 11 * SECOND)
            openTick("alice", ct, a1, t0 + 12 * SECOND)
        }.json()

    private suspend fun aliceInitiatesToCarol(): JsonObject =
        transcript(
            "aliceInitiatesToCarol",
            "FSR §3, the other way round: Alice initiates to Carol's prekey, so Carol (the iPhone) is the responder. Carol " +
                "ticks Alice's DM from her first epoch, which confirms Alice; Carol's reply rides that epoch, and Alice's " +
                "tick for it opens Alice's second epoch.",
            "alice" to "carol",
            mapOf("alice" to listOf(Draw.Key(230), Draw.Key(231), Draw.Key(234)), "carol" to listOf(Draw.Key(233))),
            android = "alice",
        ).apply {
            val a0 = seal("alice", "carol", "Hi Carol", t0, v3 = true)
            val ct = openAndTick("carol", a0, t0 + 2 * SECOND, note = "Carol responds: a confirmed session from the first frame")
            openTick("alice", ct, a0, t0 + 3 * SECOND, note = "Carol sealed against Alice's epoch: Alice confirms")
            val c1 = seal("carol", "alice", "Hi Alice", t0 + 10 * SECOND, v3 = true)
            val at = openAndTick("alice", c1, t0 + 11 * SECOND)
            openTick("carol", at, c1, t0 + 12 * SECOND)
        }.json()

    companion object {
        private const val SECOND = RatchetDmVectors.SECOND
        private const val OPENED = RatchetDmVectors.OPENED

        /** The section's rules, added to `transcriptFormat` under `carol` (no existing key moves). */
        fun formatJson(): JsonObject =
            buildJsonObject {
                put(
                    "note",
                    "carol is a list of transcripts in dm's shape, each a DM session between Carol (the iPhone, the " +
                        "carol identity, prekey carolSpk1) and one Android party. Every draw is listed per party, Carol's " +
                        "included: inject Carol's in order and her seal steps (header, nonce, ct, encEnvelope, wire) must " +
                        "come out byte for byte. The frame after each plain DM open is the receiver's delivery tick. " +
                        "Every opener here is v3, the form Android sends to a CAP_CRYPTO_V3 peer (the init bound into the " +
                        "AAD); dm's committed firstContact opener is the v2 inbound form, still accepted. " +
                        "InboundPipelineTest compares the Android receiver's session after each checked open (after the " +
                        "tick, for one that ticks back) with the step's state, except recvEpochs' lastUsedAt and the " +
                        "session's prevRootExpiresAt: the pipeline's opens stamp those from System.currentTimeMillis(), " +
                        "not the step's now.",
                )
                putJsonObject("fields") {
                    put(
                        "sealedByPipeline",
                        "true on a seal step the sealing party's own InboundPipeline makes in answer to the open just before " +
                            "it (sealDmReceipt's CTL_RECEIPT tick, v3 here), not one its user sends; that open's " +
                            "applies.tickStep names it. A replay checks it through the open (its draws, and a receipt acking " +
                            "the frame), never as a seal of its own: the pipeline mints the tick's frame id and clock, so its " +
                            "ct differs from the listed one while the chain state it leaves is the same",
                    )
                    put("applies.tickStep", "on an open that ticks back: the index of the sealedByPipeline step that is the tick")
                }
            }
    }
}
