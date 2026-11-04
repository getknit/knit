package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

// The presence-key oracles the key scenarios share (ADR 2026-11.xp6d, "The presence key"; `PresenceKeyLabTest`): who
// stores whose current and next keys, byte for byte, and that nothing about them reached someone it must not.

/** Two DMs, one each way, landed: each side has written in the thread, so each is the other's direct contact. */
internal suspend fun MeshLab.converse(
    a: LabNode,
    b: LabNode,
) {
    assertTrue(a.sendDm(b, "hi ${b.name}"))
    await(1) { b.decrypted(b.dmWith(a)).size }
    assertTrue(b.sendDm(a, "hi ${a.name}"))
    await(2) { a.decrypted(a.dmWith(b)).size }
}

/** [owner]'s current and next generations, lowest first. */
internal suspend fun LabNode.ownPair(): List<Long> {
    val keys = PresenceKeyPolicy.keysOf(presenceKeys.ownKeys())
    return listOfNotNull(keys.current?.generation, keys.next?.generation)
}

internal suspend fun ownKey(
    owner: LabNode,
    generation: Long,
): ByteArray =
    owner.presenceKeys
        .ownKeys()
        .first { it.generation == generation }
        .key

internal suspend fun heldKey(
    holder: LabNode,
    owner: LabNode,
    generation: Long,
): ByteArray? =
    holder.presenceKeys
        .heldBy(owner.nodeId)
        .firstOrNull { it.generation == generation }
        ?.key

/** Whether [holder] stores [owner]'s current and next keys byte for byte, and its read side lists both. */
internal suspend fun holdsKeysOf(
    holder: LabNode,
    owner: LabNode,
): Boolean {
    val pair = owner.ownPair()
    if (pair.size != 2) return false
    val listed =
        holder.presenceKeys
            .presenceKeysFor(setOf(owner.nodeId))
            .first[owner.nodeId]
            .orEmpty()
    return pair.all { generation ->
        val own = ownKey(owner, generation)
        heldKey(holder, owner, generation)?.contentEquals(own) == true &&
            listed.any { it.generation == generation && it.key.contentEquals(own) }
    }
}

internal suspend fun MeshLab.awaitHoldsKeysOf(
    holder: LabNode,
    owner: LabNode,
) {
    val held = tryAwait(1) { if (holdsKeysOf(holder, owner)) 1 else 0 }
    assertTrue(
        "${holder.name} never held ${owner.name}'s current and next keys (owner ${owner.ownPair()}, held " +
            "${holder.presenceKeys.heldBy(owner.nodeId).map { it.generation }})\n${report(listOf(holder, owner))}",
        held,
    )
}

/** [holder] stores none of [owner]'s [generations]. */
internal suspend fun assertHoldsNoneOf(
    holder: LabNode,
    owner: LabNode,
    generations: List<Long>,
) {
    holder.transport.awaitInboundDrained()
    assertEquals("${owner.name} decided nothing for ${holder.name}", null, owner.manager.presenceKeysSentTo(holder.nodeId))
    val held = holder.presenceKeys.heldBy(owner.nodeId).map { it.generation }
    assertTrue("${holder.name} holds ${owner.name}'s fresh keys $held", generations.none { it in held })
}

/**
 * After a reconcile of [from]'s ran to its end (a heal), none of [to] stores a key of [from]'s or refused one: none
 * was sent. The heal returns once its basket ran, the reconcile in it included, and a send returns once the far end
 * holds the frame, so each receiver's drained inbound has handled anything that crossed.
 */
internal suspend fun assertHandedNothing(
    from: LabNode,
    to: List<LabNode>,
) {
    from.heal()
    for (node in to) {
        assertEquals("${from.name} decided keys for ${node.name}", null, from.manager.presenceKeysSentTo(node.nodeId))
        node.transport.awaitInboundDrained()
        assertTrue("${node.name} holds ${from.name}'s keys", node.presenceKeys.heldBy(from.nodeId).isEmpty())
        assertEquals("${node.name} refused a key", 0L, node.drops(DropReason.PRESENCE_KEY_REFUSED))
    }
    assertFalse(
        "${from.name} handed a key to someone it must not",
        to.any { node ->
            from.transport.sent.any {
                it.contains(" ${node.nodeId.take(NODE_ID_CHARS)} ") && it.contains("relay=false") &&
                    it.contains(" chat ")
            }
        },
    )
}

/** The point-to-point chat frames [from] handed [to] — a sealed tick, a presence key, a link-owed reset. */
internal fun pointToPointChatIds(
    from: LabNode,
    to: LabNode,
): Set<String> =
    from.transport.sent
        .filter { it.contains(" ${to.nodeId.take(NODE_ID_CHARS)} ") && it.contains(" chat ") && it.contains("relay=false") }
        .mapTo(HashSet()) { it.substringAfter(" chat ").substringBefore(' ') }

// The node-id prefix `LabTransport.sent` lines carry.
private const val NODE_ID_CHARS = 6
