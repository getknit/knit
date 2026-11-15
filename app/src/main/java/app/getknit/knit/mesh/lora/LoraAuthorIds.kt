package app.getknit.knit.mesh.lora

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.IdElision
import kotlinx.coroutines.flow.Flow

/**
 * The node ids an elided (`0x06`) room post's sender prefix may stand for on a plane that elides ([BoardTraits.elidesRoom],
 * MeshCore; ADR 2026-11.gsqu §4 and its Stage 5c-1 amendment): the authors whose key this phone holds, so the post can
 * be verified at all. Two tiers, asked in order (the security design pass's `IdElision` item): [Tier.ACCEPTED], our
 * contacts, and only when none of them yields a post that verifies, [Tier.PINNED], every other pinned peer. So four
 * identities ground to share a contact's 4-byte prefix — more than [IdElision.MAX_CANDIDATES] matches, which `rebuild`
 * refuses — cannot censor that contact's posts unless they are contacts too. A stranger's prefix resolves to nothing,
 * and their post, unverifiable anyway, is dropped.
 *
 * A snapshot, immutable: the plane collects the [LoraAuthorDirectory] flow and reads the last one on its packet
 * collector. Holds node ids only, never a key or a name.
 */
internal class LoraAuthorIds(
    accepted: Collection<String>,
    pinned: Collection<String>,
) {
    /** Which ids a resolution reads. */
    enum class Tier { ACCEPTED, PINNED }

    private val acceptedIds: Map<Int, List<ByteArray>> = index(accepted.toSet())
    private val pinnedIds: Map<Int, List<ByteArray>> = index(pinned.toSet() - accepted.toSet())

    /** The full ids of [tier] that start with [prefix] (an [IdElision.PREFIX_BYTES]-byte prefix); empty for any other size. */
    fun resolve(
        prefix: ByteArray,
        tier: Tier,
    ): List<ByteArray> {
        if (prefix.size != IdElision.PREFIX_BYTES) return emptyList()
        val ids = if (tier == Tier.ACCEPTED) acceptedIds else pinnedIds
        return ids[keyOf(prefix)].orEmpty().filter { it.copyOf(prefix.size).contentEquals(prefix) }
    }

    companion object {
        /** No ids at all: every elided post is unresolved. */
        val NONE = LoraAuthorIds(emptySet(), emptySet())

        private fun index(ids: Set<String>): Map<Int, List<ByteArray>> =
            ids
                .mapNotNull(NodeId::toBytesOrNull)
                .filter { it.size >= IdElision.PREFIX_BYTES }
                .groupBy(::keyOf)

        private fun keyOf(bytes: ByteArray): Int =
            (0 until IdElision.PREFIX_BYTES).fold(0) { acc, i -> (acc shl Byte.SIZE_BITS) or (bytes[i].toInt() and BYTE_MASK) }

        private const val BYTE_MASK = 0xFF
    }
}

/** The [LoraAuthorIds] as they change; null in a transport built with no source, where every elided post is unresolved. */
internal typealias LoraAuthorDirectory = Flow<LoraAuthorIds>
