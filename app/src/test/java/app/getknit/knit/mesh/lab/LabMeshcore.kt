package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.lora.meshcore.FakeForeignClient
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreBoard
import app.getknit.knit.mesh.lora.meshcore.MeshcoreChannel
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.quickMeshcoreAir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

// The mesh lab's MeshCore verbs (ADR 2026-11.gsqu's Stage 5c-2b amendment), beside `MeshLab` rather than in it: the node
// itself is `MeshLab.node(meshcore = …)` and `LabNode.meshcoreOn`.

/**
 * A MeshCore air for nodes built with `meshcore = air`, on the lab's own serial scope and its calendar ([LabClock],
 * unskewed: a board's uptime runs on it, so the duty law's `wall − uptime` stays put across a jump). Without [channel] it is
 * [quickMeshcoreAir], the Stage 3b air ÷ 10; with one, the firmware's channel at [MeshLab.LAB_SF] / [MeshLab.LAB_BW_HZ] —
 * fast on the wall clock, inside one 10 % sub-band — whose repeater draws mix in the chaos seed. Either way every board
 * reports that preset in `SELF_INFO`, so the plane prices what the fake airs. Edit its topology with [meshcoreEdit], never
 * from the scenario's thread.
 */
internal fun MeshLab.meshcoreAir(channel: Boolean = false): FakeMeshcoreAir =
    if (channel) {
        FakeMeshcoreAir(
            meshcoreScope,
            MeshcoreChannel(spreadFactor = MeshLab.LAB_SF, bandwidthHz = MeshLab.LAB_BW_HZ, runSeed = chaos?.seed ?: 1L),
            serial = meshcoreSerial,
            now = clock::now,
        )
    } else {
        quickMeshcoreAir(meshcoreScope, clock::now, serial = meshcoreSerial)
    }.also { meshcoreAirs += it }

/**
 * Every MeshCore pair among [nodes] the plane can teach — each the other's direct contact, the board map's rule — taught both
 * ways ([awaitTaught]): what [MeshLab.awaitAcquainted] ends with. A pair that is not is no holder of the other's board, by
 * design: a link alone makes no contact.
 */
internal suspend fun MeshLab.awaitMeshcorePairsTaught(nodes: List<LabNode>) {
    val boarded = nodes.filter { it.meshcoreBoard != null }
    for (a in boarded) {
        for (b in boarded) {
            if (a === b) continue
            awaitAuthor(a, b)
            if (a.isDirectContact(b) && b.isDirectContact(a)) awaitTaught(a, b)
        }
    }
}

/**
 * Returns once [a]'s MeshCore plane resolves [b] as an author (`LoraMeshTransport.resolvesAuthor`): what verifying an elided
 * post of [b]'s reads. The plane collects its author ids off the peer table on a coroutine of its own, so a post sent the
 * moment the pair met could find them stale and be dropped unverified (`lora elided unverified: candidates=0`, the 5c-2b
 * sweep's find).
 */
internal suspend fun MeshLab.awaitAuthor(
    a: LabNode,
    b: LabNode,
) {
    val plane = checkNotNull(a.meshcore) { "${a.name} has no MeshCore board" }
    val ok = tryAwait(1) { if (plane.resolvesAuthor(b.nodeId)) 1 else 0 }
    assertTrue("${a.name}'s plane never resolved ${b.name} as an author\n${report(listOf(a, b))}", ok)
}

/**
 * Returns once [b]'s MeshCore board key is a contact on [a]'s board and [a]'s session holds it taught and pinned zero-hop on
 * this connection (`MeshcoreSession.taughtAndPinned`), and [a]'s plane holds [b] direct with [b] in its pair seal's index
 * ([LabNode.meshcoreReadyFor]): what a compact DM from [a] to [b] waits for. The teach is asynchronous
 * after everything a meeting does — the sealed profile that carries the key, the board map, the hooks' taught set, the
 * session's write — so a scenario that sends right after [MeshLab.awaitAcquainted] would otherwise find `NO_RECORD`, or a
 * board that drops [b]'s DM for want of the contact.
 */
internal suspend fun MeshLab.awaitTaught(
    a: LabNode,
    b: LabNode,
) {
    val key = checkNotNull(b.meshcoreBoard) { "${b.name} has no MeshCore board" }.key
    val ok = tryAwait(1) { if (a.meshcoreTaught(key) && a.meshcoreReadyFor(b.nodeId)) 1 else 0 }
    if (ok) return
    val state = a.meshcoreTeachState(b)
    assertTrue("${a.name}'s board never held ${b.name}'s key taught and pinned: $state\n${report(listOf(a, b))}", ok)
}

/**
 * Makes [nodes] MeshCore contacts and parts them: every pair links, writes each other in turn over the link (so each is the
 * other's direct contact and one session confirms between them), and accepts; every node heals, so the claim minted for the
 * new contact set reaches each confirmed session; then every pair is taught ([MeshLab.awaitAcquainted] → [awaitTaught]),
 * the threads converge and the links go. Only the air is left.
 */
internal suspend fun MeshLab.meetOverMeshcore(vararg nodes: LabNode) {
    val pairs = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
    linkAll(*pairs.toTypedArray())
    awaitAcquainted(*nodes)
    for ((a, b) in pairs) {
        val before = b.decrypted(b.dmWith(a)).size
        assertTrue(a.sendDm(b, "hi ${b.name}"))
        await(before + 1) { b.decrypted(b.dmWith(a)).size }
        val back = a.decrypted(a.dmWith(b)).size
        assertTrue(b.sendDm(a, "hi ${a.name}"))
        await(back + 1) { a.decrypted(a.dmWith(b)).size }
        a.accept(a.dmWith(b))
        b.accept(b.dmWith(a))
    }
    nodes.forEach { it.heal() }
    awaitAcquainted(*nodes)
    for ((a, b) in pairs) assertConverged(listOf(a, b), atLeast = 2) { it.dmWith(if (it === a) b else a) }
    pairs.forEach { (a, b) -> unlink(a, b) }
}

/**
 * A stock MeshCore node on [air] (ADR 2026-11.gsqu's Stage 6 amendment): a board with no Knit behind it — only a stock
 * client writing `SEND_CHANNEL_TXT_MSG` to it — that posts plain `name: text` on Public, slot 0, under whatever name it is
 * given, as any radio on the public key can. Its topology is the air's ([meshcoreEdit]: `link("stock", "bob")`). A post is
 * written on the air's serial context; what a receiver's plane makes of it is read at the receiver, never off the air.
 */
internal class StockMeshcoreNode private constructor(
    private val lab: MeshLab,
    val air: FakeMeshcoreAir,
    val name: String,
) {
    private lateinit var board: FakeMeshcoreBoard
    private lateinit var client: FakeForeignClient

    /**
     * Posts [text] on Public under [asName] — the board's advert name set first, as the stock app's rename would — stamped
     * [stampSecs] (the board's own clock by default). The firmware puts `asName: ` in front; a name past 31 B is cut there
     * as `SET_ADVERT_NAME` cuts it. Waits for the board to have a free packet first, as a stock app's next send waits on
     * the last one's answer, and fails unless the board answered OK: a burst past its pool would otherwise be refused
     * (`ERR_NOT_FOUND`) and never air, with nothing to say so.
     */
    suspend fun post(
        text: String,
        asName: String,
        stampSecs: Long? = null,
    ) {
        val free = lab.tryAwait(1) { if (air.snapshot { board.hasFreePacket() }) 1 else 0 }
        check(free) { "$name's board never had a free packet" }
        val answer =
            air.meshcoreEdit {
                board.advertName = advertNameOf(asName).encodeToByteArray()
                val frame =
                    MeshcoreProto.sendChannelText(PUBLIC_SLOT, stampSecs ?: board.clockSecs, text.encodeToByteArray(), board.advertName)
                val before = client.received.size
                client.send(checkNotNull(frame) { "a stock post the firmware would trim: ${text.length} chars under $asName" })
                client.received.drop(before).firstOrNull { it[0].toInt() == OK || it[0].toInt() == ERR }
            }
        check(answer?.get(0)?.toInt() == OK) { "$name's board refused a post: ${answer?.toList()}" }
    }

    companion object {
        /** Public, as every stock board keeps it. */
        const val PUBLIC_SLOT = 0

        /** `NodePrefs.node_name`'s 32 B less its NUL: what `SET_ADVERT_NAME` keeps. */
        const val MAX_NAME_BYTES = 31

        /**
         * The name a board advertises when told [asName]: cut at [MAX_NAME_BYTES] bytes, as `SET_ADVERT_NAME` cuts it — what
         * every receiver reads in front of the post. ASCII names only (a cut through a multi-byte character is the judge's
         * lenient decode, `MeshcorePublicJudgeTest`'s).
         */
        fun advertNameOf(asName: String): String =
            asName.encodeToByteArray().let { it.copyOf(minOf(it.size, MAX_NAME_BYTES)) }.decodeToString()

        /** `RESP_CODE_OK` and `RESP_CODE_ERR`: a command's answer. */
        private const val OK = 0
        private const val ERR = 1

        /** A stock node called [name] on [lab]'s [air]: its board made there, Public on slot 0 as the firmware ships it. */
        suspend fun on(
            lab: MeshLab,
            air: FakeMeshcoreAir,
            name: String,
        ): StockMeshcoreNode =
            StockMeshcoreNode(lab, air, name).apply {
                air.meshcoreEdit {
                    board = board(name)
                    client = FakeForeignClient(board)
                }
            }
    }
}

/**
 * Every MeshCore air's repeater draws and its last airings, and each node's fragment ids, for [MeshLab.report]: a failing
 * seed then names the delays and ids it ran under. Empty with no MeshCore air.
 */
internal fun meshcoreReport(
    airs: List<FakeMeshcoreAir>,
    nodes: List<LabNode>,
): String {
    if (airs.isEmpty()) return ""
    // Each air read on its own serial context: an airing's receivers are written there.
    val drawn =
        airs.joinToString("\n") { air ->
            runBlocking {
                air.snapshot {
                    "  meshcore draws: " +
                        air.draws.joinToString { d ->
                            "${d.repeater}<-${d.origin}#${d.seq}${if (d.direct) "d" else ""}=${d.ms}/${d.maxMs}"
                        } +
                        "\n  meshcore airings: " +
                        air.airings.takeLast(MeshLab.WINDOW_LORA * 2).joinToString {
                            "$it@${it.atMs % AIRING_CLOCK_MOD} heard=${it.heardBy} lost=${it.lostAt}"
                        }
                }
            }
        }
    val ids = nodes.filter { it.fragIdsDrawn.isNotEmpty() }.joinToString("\n") { "  ${it.name} frag ids: ${it.fragIdsDrawn}" }
    return drawn + "\n" + ids.ifEmpty { "  no fragment ids drawn" } + "\n"
}

/** An airing's start, shown modulo this: the last digits of the calendar are what orders a failing run's airings. */
private const val AIRING_CLOCK_MOD = 100_000L
