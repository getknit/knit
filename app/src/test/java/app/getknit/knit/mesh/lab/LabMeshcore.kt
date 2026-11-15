package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.MeshcoreChannel
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
