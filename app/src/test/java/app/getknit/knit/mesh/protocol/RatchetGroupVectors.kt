package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetCause
import app.getknit.knit.mesh.spool.GroupRootPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Stage 4 of `ratchet-v1.json` (companion change A9): the `group` section, the sender-key group form
 * (docs/GROUP_FORWARD_SECRECY.md, GFS) end to end. One group of alice, bob and carol — its id
 * `Conversations.groupIdFor` of the three, its roster in `ContactsViewModel.createGroup`'s order (the picked contacts,
 * then the creator) — and in each scenario the parties' real `GroupRatchetSessions` and `RatchetSessions`, the seed
 * ctl DMs establishing each pair's DM session as production's first `sealDm` does. Every scenario is replayed as Bob
 * through `InboundPipeline` by `InboundPipelineTest`. Seeds from 100 up; two roots are stated fixtures, because
 * `GroupRootPolicy.newRoot` has no seam.
 */
internal class RatchetGroupVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
    private val carol: VectorParty,
    private val aliceSpk1: VectorPrekey,
    private val bobSpk1: VectorPrekey,
    private val carolSpk1: VectorPrekey,
    private val t0: Long,
) {
    private fun world(
        name: String,
        note: String,
        plans: Map<String, List<Draw>>,
        holdsAtStart: Set<String>,
        // Who InboundPipelineTest replays as, and whether it also re-seals that party's own DMs (true only where the
        // party's pipeline seals nothing of its own toward a peer before its last listed DM seal).
        receivers: Map<String, Boolean> = mapOf("bob" to false),
    ): GroupWorld {
        val prekeys = mapOf("alice" to aliceSpk1, "bob" to bobSpk1, "carol" to carolSpk1)
        val identities = mapOf("alice" to alice, "bob" to bob, "carol" to carol)
        val t =
            Transcript(
                name,
                note,
                listOf(
                    "alice",
                    "bob",
                    "carol",
                ).map { TranscriptParty(it, identities.getValue(it), listOf(prekeys.getValue(it)), plans[it].orEmpty()) },
                recordFloors = true,
            )
        for (a in prekeys.keys) for (b in prekeys.keys) if (a != b) t.pin(a, b, prekeys.getValue(b))
        t.field("pipelineReplay", JsonPrimitive(true))
        t.field(
            "pipelineReceivers",
            buildJsonArray {
                for ((receiver, ownSeals) in receivers) {
                    add(
                        buildJsonObject {
                            put("name", receiver)
                            put("replaysOwnSeals", ownSeals)
                        },
                    )
                }
            },
        )
        return GroupWorld(t, FOUNDERS, "alice", holdsAtStart).also {
            it.receivers = receivers
            t.field("groupId", JsonPrimitive(it.groupId))
            t.field(
                "founders",
                buildJsonArray { FOUNDERS.forEach { founder -> add(founder) } },
            )
            t.field("holdsAtStart", buildJsonArray { holdsAtStart.sorted().forEach { holder -> add(holder) } })
        }
    }

    suspend fun scenarios(): JsonArray =
        buildJsonArray {
            add(groupSeed())
            add(groupFrames())
            add(groupKeyRequest())
            add(groupRoot())
            add(groupRestoreRemint())
            add(groupRetiredChain())
            add(groupSkippedPerMint())
        }

    private val firstSendAlice =
        listOf(Draw.GroupSeed(100), Draw.Iv(101), Draw.Key(102), Draw.Key(103), Draw.Iv(104), Draw.Key(105), Draw.Key(106), Draw.Iv(107))

    /** Alice's first group message and its seed to bob and carol, which Bob opens and adopts. */
    private suspend fun GroupWorld.aliceFounds(): GroupSend {
        val send = groupSend("alice", MessageContent(body = "Hello group"), t0, note = "the first send mints epoch 1")
        openCtl("bob", send.seedDms.getValue("bob"), t0 + 5 * SECOND, note = "first contact and the seed: Bob pins the roster from it")
        groupOpen("bob", send.frame, t0 + 6 * SECOND, OPENED, message(send.frame))
        return send
    }

    private suspend fun groupSeed(): JsonObject =
        world(
            "groupSeed",
            "GFS §3 and ADR 017, with the roster on the seed (work item #47, ADR 2026-09.mjaj): Alice's first group message " +
                "mints her epoch 1, and MeshManager.distributeGroupSeed seals one CTL_GROUP_KEY DM per other member (v2, the " +
                "named plaintext, never v3) before the group frame floods. The DM's GroupKeyPayload carries the seed and the " +
                "founding roster (gk.group), so Bob, holding no row, pins the group from it, adopts the chain and acks the " +
                "epoch back (CTL_GROUP_KEY_ACK, InboundPipeline.ackGroupSeed); Alice's outbox records each ack " +
                "(GroupRatchetSessions.onKeyAck). Carol does the same.",
            mapOf("alice" to firstSendAlice, "bob" to listOf(Draw.Key(108), Draw.Iv(109)), "carol" to listOf(Draw.Key(110), Draw.Iv(111))),
            holdsAtStart = setOf("alice"),
            receivers = mapOf("bob" to false, "alice" to true),
        ).apply {
            val send = aliceFounds()
            openCtl("carol", send.seedDms.getValue("carol"), t0 + 7 * SECOND)
            groupOpen("carol", send.frame, t0 + 8 * SECOND, OPENED, null)
            val bobAck = ackSeed("bob", "alice", 1, t0 + 9 * SECOND)
            openCtl("alice", bobAck, t0 + 10 * SECOND, note = "the ack stamps Alice's outbox: ackedEpoch 1 for bob")
            val carolAck = ackSeed("carol", "alice", 1, t0 + 11 * SECOND)
            openCtl("alice", carolAck, t0 + 12 * SECOND)
        }.t
            .json()

    private suspend fun groupFrames(): JsonObject =
        world(
            "groupFrames",
            "GFS §5: after groupSeed's opening, Alice's next four group frames ride her epoch 1 (no mint, one IV each), " +
                "and a group-form CTL_REACTION (MeshManager.sealGroupReaction: the same group seal, never persisted as a " +
                "message, applied atomically with the chain advance). Bob opens n 3 first (n 1 and n 2 banked as skipped " +
                "keys), then n 1, n 2 and n 4; a re-serve of n 2 past SeenSet.DEFAULT_TTL_MS is DUPLICATE at the group " +
                "engine (on a whole node the row it wrote makes the exists-gate skip it first).",
            mapOf("alice" to firstSendAlice + (112..116).map { Draw.Iv(it) }),
            holdsAtStart = setOf("alice"),
        ).apply {
            val founded = aliceFounds()
            val frames = (1..4).map { groupSend("alice", MessageContent(body = "group $it"), t0 + (10 + it) * SECOND).frame }
            groupOpen("bob", frames[2], t0 + 20 * SECOND, OPENED, message(frames[2]), note = "n 3 before n 1 and n 2: two skipped keys")
            groupOpen("bob", frames[0], t0 + 21 * SECOND, OPENED, message(frames[0]))
            groupOpen("bob", frames[1], t0 + 22 * SECOND, OPENED, message(frames[1]))
            groupOpen("bob", frames[3], t0 + 23 * SECOND, OPENED, message(frames[3]))
            groupOpen(
                "bob",
                frames[1],
                t0 + 22 * SECOND + SeenSet.DEFAULT_TTL_MS + SECOND,
                DUPLICATE,
                null,
                note = "engine layer only: the router absorbs it inside the window, and the exists-gate after it",
            )
            val rp = ReactionPayload(founded.frame.env.id, THUMBS_UP)
            val reaction = groupSend("alice", MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = rp), t0 + 30 * SECOND).frame
            groupOpen("bob", reaction, t0 + 31 * SECOND, OPENED, reactionApplies(rp, reaction.env.sentAt))
        }.t
            .json()

    private suspend fun groupKeyRequest(): JsonObject =
        world(
            "groupKeyRequest",
            "GFS §7 and ADR 017: Bob sends to the group and his seed DM to Carol is lost. Each of his frames is NO_KEY at " +
                "Carol, and the third distinct one fires the key-request heuristic (REQUEST_DISTINCT_FRAMES, under " +
                "KEY_REQUEST_MIN_INTERVAL_MS and the REQUEST_MAX_FRAME_AGE_MS gate: keyRequestDue). Carol seals a " +
                "CTL_GROUP_KEY_REQ; Bob's pipeline hands it to MeshManager.redistributeGroupKey, which re-seals his current " +
                "seeds to her (no new epoch; the per-member SEED_RESEND_FLOOR_MS floor is open, nothing went to her since). " +
                "Carol adopts, acks, and her custody replay opens the three frames. Carol and Bob already share a DM " +
                "session (her \"Hi Bob\"), so no race is in play.",
            mapOf(
                "carol" to listOf(Draw.Key(117), Draw.Key(118), Draw.Iv(119), Draw.Iv(120)),
                "bob" to
                    listOf(
                        Draw.GroupSeed(121),
                        Draw.Iv(122),
                        Draw.Key(123),
                        Draw.Iv(124),
                        Draw.Key(125),
                        Draw.Key(126),
                        Draw.Iv(127),
                        Draw.Iv(128),
                        Draw.Iv(129),
                        Draw.Iv(130),
                    ),
            ),
            holdsAtStart = setOf("alice", "bob", "carol"),
            receivers = mapOf("bob" to false, "carol" to true),
        ).apply {
            val hi = t.seal("carol", "bob", "Hi Bob", t0, v3 = false)
            t.open("bob", hi, t0 + SECOND, OPENED, applies = dmMessage(hi))
            val first = groupSend("bob", MessageContent(body = "From Bob"), t0 + 10 * SECOND, note = "the seed DM to carol is lost")
            val frames =
                listOf(first.frame) + (1..2).map { groupSend("bob", MessageContent(body = "Bob $it"), t0 + (10 + it) * SECOND).frame }
            frames.forEachIndexed { i, f -> groupOpen("carol", f, t0 + (20 + i) * SECOND, NO_KEY, buildJsonObject { base() }) }
            val request = keyRequest("carol", "bob", t0 + 23 * SECOND)
            openCtl("bob", request, t0 + 24 * SECOND, note = "the request goes to MeshManager.redistributeGroupKey after the commit")
            val answer = redistribute("bob", "carol", t0 + 25 * SECOND)
            openCtl("carol", answer, t0 + 26 * SECOND)
            frames.forEachIndexed { i, f ->
                groupOpen(
                    "carol",
                    f,
                    t0 + (27 + i) * SECOND,
                    OPENED,
                    message(f),
                    note = "replayed from Carol's own custody after the adoption",
                )
            }
        }.t
            .json()

    private val rootA = fixtureBytes(GroupRootPolicy.ROOT_BYTES, 131)
    private val rootC = fixtureBytes(GroupRootPolicy.ROOT_BYTES, 132)

    private suspend fun groupRoot(): JsonObject =
        world(
            "groupRoot",
            "docs/SPOOL_PROTOCOL.md §3.2 (MeshManager.gossipGroupRoot / adoptGroupRoot, GroupRootPolicy): Alice holds root " +
                "v1 and, never having sealed a group frame, gossips it root-only — a CTL_GROUP_KEY with empty keys. Bob pins " +
                "the roster from it and adopts the root (no seed, so no ack). Carol, partitioned past MINT_GRACE_MS, minted " +
                "her own v1; the (version, minter) order prefers hers (her node id sorts after Alice's), so Bob adopts it " +
                "too. Each adoption goes onward (MeshManager.onGroupRootCtl → gossipGroupRoot(skip = sender)): Alice's root " +
                "to Carol, then Carol's to Alice — Bob's own root-only DMs, listed. Alice's first seed then carries her v1 " +
                "again: the seed adopts and the root is refused as older, and onGroupRootCtl would answer Alice with Bob's " +
                "root, but seedSendFloorOpen(g, alice) is closed — Bob sent Carol's root to Alice 10 s before, under the " +
                "same pairwise root, inside SEED_RESEND_FLOOR_MS — so nothing goes back (applies.rootGossip). The roots " +
                "are stated fixtures (fixtures.roots), not draws.",
            mapOf(
                "alice" to
                    listOf(
                        Draw.Key(133),
                        Draw.Key(134),
                        Draw.Iv(135),
                        Draw.Key(136),
                        Draw.Key(137),
                        Draw.Iv(138),
                        Draw.GroupSeed(139),
                        Draw.Iv(140),
                        Draw.Iv(141),
                        Draw.Iv(142),
                    ),
                "carol" to listOf(Draw.Key(143), Draw.Key(144), Draw.Iv(145), Draw.Key(146), Draw.Key(147), Draw.Iv(148)),
                "bob" to listOf(Draw.Key(179), Draw.Key(180), Draw.Iv(181), Draw.Key(182), Draw.Iv(183)),
            ),
            holdsAtStart = setOf("alice", "carol"),
            receivers = mapOf("bob" to true),
        ).apply {
            t.field("fixtures", rootFixtures())
            holdRoot("alice", rootA, 1, "alice", t0)
            holdRoot("carol", rootC, 1, "carol", t0)
            val fromAlice = gossipRoot("alice", t0 + SECOND)
            openCtl("bob", fromAlice.getValue("bob"), t0 + 5 * SECOND, note = "root-only: pinned and adopted, nothing to ack")
            val fromCarol = gossipRoot("carol", t0 + 10 * SECOND)
            openCtl(
                "bob",
                fromCarol.getValue("bob"),
                t0 + 15 * SECOND,
                note =
                    "the same version from a minter that sorts later: newer. Bob's onward gossip to Carol had opened a " +
                        "session from his side, so her init is a both-initiate race, which her smaller node id wins",
            )
            val send = groupSend("alice", MessageContent(body = "Hello group"), t0 + 20 * SECOND)
            openCtl(
                "bob",
                send.seedDms.getValue("bob"),
                t0 + 25 * SECOND,
                note = "the seed adopts; its root is older than the one held, and the floor keeps Bob's answer back",
            )
            groupOpen("bob", send.frame, t0 + 26 * SECOND, OPENED, message(send.frame))
        }.t
            .json()

    private fun rootFixtures() =
        buildJsonObject {
            putJsonArray("roots") {
                for ((holder, seed, root) in listOf(Triple("alice", 131, rootA), Triple("carol", 132, rootC))) {
                    add(
                        buildJsonObject {
                            put("holder", holder)
                            put("version", 1)
                            put("minter", holder)
                            put("seed", seed)
                            put("root", root.toHex())
                            put("heldAt", t0)
                        },
                    )
                }
            }
        }

    private val restoreAlice: List<Draw> =
        (listOf(149, 150).map { Draw.Key(it) } + Draw.Iv(151) + Draw.GroupSeed(152) + (153..154).map { Draw.Iv(it) }) +
            listOf(Draw.Key(155), Draw.Key(156), Draw.Iv(157), Draw.Key(158), Draw.Key(159), Draw.Iv(160)) +
            listOf(Draw.Key(161), Draw.Key(162), Draw.Iv(163), Draw.GroupSeed(164)) + (165..167).map { Draw.Iv(it) }

    private suspend fun groupRestoreRemint(): JsonObject =
        world(
            "groupRestoreRemint",
            "Wipe recovery is mint-stamped (GFS §4 rule 5, GroupRatchetEngine.adoptSeed): Alice seals her first group " +
                "message, then restores a backup before Bob hears it (ADR 2026-09.6mj7: the ratchets are never carried) and " +
                "MeshManager.finishRestore resets each DM peer and co-member (AFTER_WIPE; with no session left each is a " +
                "re-root). Her next group message mints epoch 1 again, under a newer mintedAt. Bob adopts the reset (and his " +
                "pipeline re-seals his unacked DMs and force-flushes his seeds to her), then the new seed. The old era's " +
                "seed, drained late under prevRoot, is STALE: the same epoch under an older mint, refused, not acked. Its " +
                "frame is lost — DUPLICATE at the group engine, because the new mint's chain has already passed n 0 (it would " +
                "be AEAD_FAIL had it landed first): what GFS §4 rule 5 says a receiver that meets a wipe's re-mint first " +
                "loses, and §6 says how it drops.",
            mapOf("alice" to restoreAlice),
            holdsAtStart = setOf("alice"),
        ).apply {
            val hi = t.seal("alice", "bob", "Hi Bob", t0, v3 = false)
            t.open("bob", hi, t0 + SECOND, OPENED, applies = dmMessage(hi))
            val before = groupSend("alice", MessageContent(body = "Before the restore"), t0 + 10 * SECOND, note = "held: Bob hears it late")
            restore("alice", t0 + 60 * SECOND)
            val resets =
                listOf("bob", "carol").associateWith {
                    checkNotNull(
                        t.reset(
                            "alice",
                            it,
                            t0 + 61 * SECOND,
                            ResetCause.AFTER_WIPE,
                            "REROOT",
                            decidedBy = "state.session is null: the restore emptied it",
                        ),
                    )
                }
            val after =
                groupSend("alice", MessageContent(body = "After the restore"), t0 + 70 * SECOND, note = "epoch 1 again, a newer mintedAt")
            t.open("bob", resets.getValue("bob"), t0 + 80 * SECOND, OPENED, applies = resetApplies())
            openCtl("bob", after.seedDms.getValue("bob"), t0 + 81 * SECOND, note = "the new mint: adopted")
            groupOpen("bob", after.frame, t0 + 82 * SECOND, OPENED, message(after.frame))
            openCtl(
                "bob",
                before.seedDms.getValue("bob"),
                t0 + 83 * SECOND,
                note = "the old era's seed under prevRoot: an older mint of a held epoch",
            )
            groupOpen("bob", before.frame, t0 + 84 * SECOND, DUPLICATE, buildJsonObject { base() }, note = "lost with its stale seed")
        }.t
            .json()

    private suspend fun groupRetiredChain(): JsonObject =
        world(
            "groupRetiredChain",
            "#167, ADR 2026-10.xgwj (commit 0f519d71): Alice sends, Carol leaves, Alice sends again. The departure " +
                "(GroupRepository.recordDeparture) retires Alice's send chain — every key gone, the number kept at count " +
                "MAX_EPOCH_MESSAGES — so her next send mints epoch 2, never epoch 1 again, and seeds only the remaining " +
                "members. Bob meets the epoch-2 seed first, then the epoch-1 one: different epochs, both adopt, both " +
                "frames open. Before the fix the second seed was epoch 1 under a newer mint, and this order left the first " +
                "STALE and its frame lost. Bob pins the group from the epoch-2 seed with Carol still a member: a seed's " +
                "departed list is never a tombstone (InboundPipeline.vetRoster — tombstones are a receiver's own), so " +
                "Bob learns of the leave only from Carol's own groupleave frame, not listed here.",
            mapOf(
                "alice" to
                    listOf(
                        Draw.GroupSeed(168),
                        Draw.Iv(169),
                        Draw.Key(170),
                        Draw.Key(171),
                        Draw.Iv(172),
                        Draw.Key(173),
                        Draw.Key(174),
                        Draw.Iv(175),
                        Draw.GroupSeed(176),
                        Draw.Iv(177),
                        Draw.Iv(178),
                    ),
            ),
            holdsAtStart = setOf("alice"),
        ).apply {
            val founded = groupSend("alice", MessageContent(body = "Founded"), t0)
            depart("alice", "carol", t0 + 20 * SECOND)
            val after =
                groupSend("alice", MessageContent(body = "After carol left"), t0 + 30 * SECOND, note = "mints past the retired epoch: 2")
            openCtl(
                "bob",
                after.seedDms.getValue("bob"),
                t0 + 40 * SECOND,
                note = "the later seed first: pins the founding set, Carol included",
            )
            openCtl("bob", founded.seedDms.getValue("bob"), t0 + 41 * SECOND, note = "then the earlier one: another epoch, adopted")
            groupOpen("bob", after.frame, t0 + 42 * SECOND, OPENED, message(after.frame))
            groupOpen("bob", founded.frame, t0 + 43 * SECOND, OPENED, message(founded.frame))
        }.t
            .json()

    private val perMintAlice: List<Draw> =
        listOf(Draw.Key(235), Draw.Key(236), Draw.Iv(237)) +
            listOf(Draw.GroupSeed(238), Draw.Iv(239), Draw.Iv(240), Draw.Key(241), Draw.Key(242), Draw.Iv(243)) +
            (244..246).map { Draw.Iv(it) } +
            listOf(Draw.Key(247), Draw.Key(248), Draw.Iv(249), Draw.Key(250), Draw.Key(251), Draw.Iv(252)) +
            listOf(Draw.GroupSeed(253), Draw.Iv(254), Draw.Iv(255), Draw.Iv(256)) +
            (257..259).map { Draw.Iv(it) }

    /**
     * [groupSkippedPerMint]'s two eras: Bob adopts Alice's first mint of epoch 1, she sends four frames, restores, resets
     * him and re-mints with four more, and he adopts the re-mint too. Returns each era's frames, n 0 to 3.
     */
    private suspend fun GroupWorld.twoMints(): Pair<List<TranscriptFrame>, List<TranscriptFrame>> {
        val hi = t.seal("alice", "bob", "Hi Bob", t0, v3 = false)
        t.open("bob", hi, t0 + SECOND, OPENED, applies = dmMessage(hi))
        val firstOld = groupSend("alice", MessageContent(body = "Old 0"), t0 + 10 * SECOND, note = "mints epoch 1")
        val old =
            listOf(firstOld.frame) +
                (1..3).map {
                    groupSend(
                        "alice",
                        MessageContent(body = "Old $it"),
                        t0 + (10 + it) * SECOND,
                        note = "held: Bob hears it late",
                    ).frame
                }
        openCtl("bob", firstOld.seedDms.getValue("bob"), t0 + 15 * SECOND, note = "the first mint: adopted")
        restore("alice", t0 + 60 * SECOND)
        val resets =
            listOf("bob", "carol").associateWith {
                checkNotNull(
                    t.reset(
                        "alice",
                        it,
                        t0 + 61 * SECOND,
                        ResetCause.AFTER_WIPE,
                        "REROOT",
                        decidedBy = "state.session is null: the restore emptied it",
                    ),
                )
            }
        val firstNew =
            groupSend("alice", MessageContent(body = "New 0"), t0 + 70 * SECOND, note = "epoch 1 again, a newer mintedAt")
        val new =
            listOf(firstNew.frame) +
                (1..3).map { groupSend("alice", MessageContent(body = "New $it"), t0 + (70 + it) * SECOND).frame }
        t.open("bob", resets.getValue("bob"), t0 + 80 * SECOND, OPENED, applies = resetApplies())
        openCtl(
            "bob",
            firstNew.seedDms.getValue("bob"),
            t0 + 81 * SECOND,
            note = "the re-mint: adopted beside the first, whose rows drain for 48 h",
        )
        return old to new
    }

    private suspend fun groupSkippedPerMint(): JsonObject =
        world(
            "groupSkippedPerMint",
            "#177 (commit 458c2eee, GroupRatchetEngine.OpenDelta.consumedSkippedMintedAt): a skipped key is spent by its " +
                "mint, never by its index alone. Bob adopts Alice's epoch 1 and she sends four frames he does not hear yet; " +
                "she restores a backup (ADR 2026-09.6mj7), resets him, and mints epoch 1 again under a newer mintedAt, with " +
                "four more frames. Bob adopts the re-mint too, so he holds both eras' chains for the epoch (GFS §4 rule 5: " +
                "receivers key rows by (group, sender, epoch, mintedAt)). Each era's n 3 opens on its own chain and banks n " +
                "0 to 2 as skipped keys under its own mint: two keys at each index. n 1 opens the new era's first, then the " +
                "old era's; n 2 the other way round. Every open spends only its own mint's key: before #177 spending one " +
                "deleted both, and the other era's frame at that index read as DUPLICATE for good, since both chains are " +
                "past it and a re-sent seed never rewinds a chain. A re-serve of either n 1 past SeenSet.DEFAULT_TTL_MS is " +
                "then DUPLICATE at the group engine.",
            mapOf("alice" to perMintAlice),
            holdsAtStart = setOf("alice"),
        ).apply {
            val (old, new) = twoMints()
            groupOpen("bob", old[3], t0 + 90 * SECOND, OPENED, message(old[3]), note = "the old era's n 3: its n 0 to 2 banked")
            groupOpen("bob", new[3], t0 + 91 * SECOND, OPENED, message(new[3]), note = "the new era's n 3: a second key at each index")
            groupOpen("bob", new[1], t0 + 92 * SECOND, OPENED, message(new[1]), note = "spends the new mint's key at n 1, only")
            groupOpen("bob", old[1], t0 + 93 * SECOND, OPENED, message(old[1]), note = "the old mint's key at n 1 is still there")
            groupOpen("bob", old[2], t0 + 94 * SECOND, OPENED, message(old[2]), note = "the other order at n 2")
            groupOpen("bob", new[2], t0 + 95 * SECOND, OPENED, message(new[2]))
            for ((i, frame) in listOf(old[1], new[1]).withIndex()) {
                groupOpen(
                    "bob",
                    frame,
                    t0 + 95 * SECOND + SeenSet.DEFAULT_TTL_MS + (i + 1) * SECOND,
                    DUPLICATE,
                    null,
                    note = "engine layer only: each spent key went with its own frame",
                )
            }
        }.t
            .json()

    // ---- what the pipeline applies ------------------------------------------------------------------------

    private fun message(frame: TranscriptFrame) =
        buildJsonObject {
            putJsonObject("message") {
                put("id", frame.env.id)
                put("body", frame.body)
            }
            base()
        }

    private fun dmMessage(frame: TranscriptFrame) =
        buildJsonObject {
            putJsonObject("message") {
                put("id", frame.env.id)
                put("body", frame.body)
            }
            put("receipts", buildJsonArray { })
            put("acksBack", true)
        }

    private fun reactionApplies(
        rp: ReactionPayload,
        at: Long,
    ) = buildJsonObject {
        putJsonObject("reaction") {
            put("messageId", rp.messageId)
            put("reactor", "alice")
            put("emoji", rp.emoji)
            put("at", at)
        }
        base()
    }

    private fun resetApplies() =
        buildJsonObject {
            putJsonObject("resetAnswer") {
                put("resealUnacked", true)
                put("flushGroupKeysForce", true)
            }
            base()
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.base() {
        put("receipts", buildJsonArray { })
        put("acksBack", false)
    }

    companion object {
        private const val SECOND = RatchetDmVectors.SECOND
        private const val OPENED = RatchetDmVectors.OPENED
        private const val DUPLICATE = RatchetDmVectors.DUPLICATE
        private const val NO_KEY = "NO_KEY"
        private const val THUMBS_UP = "👍"

        /** The founding roster in `ContactsViewModel.createGroup`'s order: the picked contacts, then the creator. */
        val FOUNDERS = listOf("bob", "carol", "alice")

        /** The group section's rules, added to `transcriptFormat` under `group` (no existing key moves). */
        fun formatJson(): JsonObject =
            buildJsonObject {
                put("note", GROUP_NOTE)
                putJsonArray("ops") {
                    add(
                        "groupSeal: actor seals contentBody to the group through GroupRatchetSessions.sealGroup; minted is " +
                            "the seed a mint drew (else null); the seed DMs a mint sends follow as seal steps",
                    )
                    add(
                        "groupOpen: actor opens a group frame through GroupRatchetSessions (peek, commit); expect is " +
                            "GroupRatchetEngine.OpenOutcome's name, keyRequestDue whether a NO_KEY or AEAD_FAIL fired the " +
                            "key-request heuristic",
                    )
                    add(
                        "depart: GroupRepository.recordDeparture on actor: roster shrinks, send chain retired, the leaver's " +
                            "outbox row dropped",
                    )
                    add("restore: a backup restore onto actor: every ratchet table empty, group rows and identity kept")
                }
                put("fields", groupFieldsJson())
            }

        private fun groupFieldsJson(): JsonObject =
            buildJsonObject {
                put(
                    "groupState",
                    "on every group-section step: the actor's roster (names), shared root, send chains (retired = " +
                        "key-less), receive chains, skipped keys and seed outbox",
                )
                put("groupHeader", "a group frame's GroupRatchetHeader {se, n}; its AAD's thread is the group id")
                put("groupSeed", "a draw kind: a group epoch seed, fixtureBytes(32, seed)")
                put(
                    "applies.groupPinned",
                    "the roster the receiver pinned from the seed (InboundPipeline.pinRosterFromSeed) when it held no row",
                )
                put("applies.seeds", "per seed carried: ADOPT, ALREADY_KNOWN or STALE (GroupRatchetEngine.adoptSeed's verdict)")
                put("applies.groupKeyAckBack", "the epoch the receiver acks back in a CTL_GROUP_KEY_ACK, or null")
                put(
                    "applies.custodyReplay",
                    "whether the receiver replays its own custody's frames from that sender after the adoption",
                )
                put(
                    "applies.groupRoot",
                    "the root a CTL_GROUP_KEY offered and whether the receiver adopted it (GroupRootPolicy.adoptable)",
                )
                put("applies.groupKeyAcked", "the epoch an inbound ack stamped in the outbox")
                put("applies.redistribute", "the request went to MeshManager.redistributeGroupKey")
                put("applies.resetAnswer", "a CTL_SESSION_RESET's post-commit answer: re-seal unacked DMs, force-flush seeds")
                put(
                    "pipelineReceivers",
                    "the parties InboundPipelineTest replays the scenario as, each through its own pipeline; replaysOwnSeals: it " +
                        "also re-seals that party's own DM seal steps through RatchetSessions.sealDm from the step's draws and " +
                        "asserts the bytes",
                )
                put(
                    "pipelineChecked",
                    "on every group-section step: true when InboundPipelineTest checks it (an open's applies and drop, or an " +
                        "own seal's bytes); false when only the transcript's mirror of MeshManager/InboundPipeline states it",
                )
                put(
                    "keyRequestDue",
                    "checked against the mirror only: InboundPipeline.decryptAndDeliverGroup takes the 48 h age gate's " +
                        "now from System.currentTimeMillis(), so a replay of these dated frames never reaches the heuristic",
                )
                put(
                    "applies.rootGossip",
                    "MeshManager.onGroupRootCtl's post-commit answer: onward (members sent the adopted root) and floored " +
                        "(members skipped by seedSendFloorOpen), or back (whether ours went back to the sender) with why " +
                        "not; the sends are listed as the receiver's own seal steps",
                )
                put("fixtures.roots", "a shared root a party holds at the start: a stated fixtureBytes(32, seed), not a draw")
                put(
                    "pipelineClock",
                    "a pipeline-replayed open (this section's and dm's pipelineReplay ones) runs at the wall clock, not " +
                        "the step's now: InboundPipeline.decryptAndDeliverV2 and decryptAndDeliverGroup read " +
                        "System.currentTimeMillis(); only seals, re-seals and the pipeline's clock() paths take the step's now",
                )
                put("constants", groupConstantsJson())
            }

        private fun groupConstantsJson(): JsonObject =
            buildJsonObject {
                put("MAX_ADOPTIONS_PER_DAY", GroupRatchetSessions.MAX_ADOPTIONS_PER_DAY)
                put("REQUEST_DISTINCT_FRAMES", GroupRatchetSessions.REQUEST_DISTINCT_FRAMES)
                put("KEY_REQUEST_MIN_INTERVAL_MS", GroupRatchetSessions.KEY_REQUEST_MIN_INTERVAL_MS)
                put("REQUEST_MAX_FRAME_AGE_MS", GroupRatchetSessions.REQUEST_MAX_FRAME_AGE_MS)
                put("SEED_RESEND_FLOOR_MS", GroupWorld.SEED_RESEND_FLOOR_MS)
            }

        private const val GROUP_NOTE =
            "group is a list of transcripts in dm's shape over one group (groupId, founders, holdsAtStart). Group frames " +
                "are chat RelayEnvelopes with group = the sender's roster and no recipientId, ChatContent.enc a v2 group-form " +
                "EncEnvelope {nonce = the drawn IV, ct, keys = [], g}; aad = id|sender|sentAt|groupId; plaintext is " +
                "MessageContent.encode(). Seed, ack and request DMs are v2 ctl DMs (MessageContent with ctl 2, 4, 3 and gk), " +
                "sealed by the sender's RatchetSessions like any DM. A seed DM's own seal step comes after the groupSeal that " +
                "minted it, its outbox stamp already in its groupState."
    }
}
