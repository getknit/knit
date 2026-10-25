package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto

/** How one HELLO speaks of one commons room (spec §7.4, C-7.4-17). */
sealed interface CommonsAdvert {
    /** What the HELLO advertises for the room — its name, pinned bounds and `attach` — or null when it does not run it. */
    val info: SpoolCommonsInfo?

    /** The room's tag is in the HELLO's `rooms`: the spool runs it, as [info] says. Settled before any `sub`. */
    class Listed(
        override val info: SpoolCommonsInfo,
    ) : CommonsAdvert

    /**
     * A spool that predates `rooms` advertises one `commons` and does not say which room it is, so [info] may describe
     * another room; the answer to a `sub` tells (the TTL probe in `ScopeSync`).
     */
    class Sole(
        override val info: SpoolCommonsInfo,
    ) : CommonsAdvert

    /** The spool does not run the room: its tag is missing from `rooms` or listed twice, or the HELLO has no room. */
    data object Absent : CommonsAdvert {
        override val info: SpoolCommonsInfo? = null
    }
}

/**
 * The commons rooms one HELLO advertises (spec §7.4), read once per connection: the one reader of `rooms` and
 * `commons`, so no caller can take one room's name or `attach` for another's. When `rooms` is present it is the whole
 * truth and `commons` is ignored (C-7.4-17); a spool that predates it describes at most one room, in `commons`.
 * Entries are expected clamped already ([SpoolConnection] holds them to its ceilings); a tag of the wrong width can
 * match nothing and is dropped, and a tag listed twice is read as absent, never as either entry.
 */
class CommonsAdverts private constructor(
    private val listed: Map<String, SpoolCommonsInfo>?,
    private val ambiguous: Set<String>,
    private val sole: SpoolCommonsInfo?,
) {
    /** How many rooms the HELLO advertises: the length of `rooms`, or one from a spool that predates it. */
    val count: Int = listed?.size ?: if (sole != null) 1 else 0

    /** Whether the spool runs any room at all: what lets a member paste an invite to join one there. */
    val runsAny: Boolean = count > 0

    /** How the HELLO speaks of the room whose tag, in hex, is [tagHex] (`Scope.commonsTag`). */
    fun advertFor(tagHex: String): CommonsAdvert =
        when {
            listed != null -> listed[tagHex]?.takeIf { tagHex !in ambiguous }?.let { CommonsAdvert.Listed(it) } ?: CommonsAdvert.Absent
            sole != null -> CommonsAdvert.Sole(sole)
            else -> CommonsAdvert.Absent
        }

    /** [advertFor] for the room whose scope id is [scopeId] — an invite's room, before anything is joined. */
    fun advertForScope(scopeId: ByteArray): CommonsAdvert = advertFor(hex(ScopeCrypto.commonsTag(scopeId)))

    companion object {
        /** The rooms a HELLO advertises, or null when it advertises none in either field. */
        fun of(
            commons: SpoolCommonsInfo?,
            rooms: List<Pair<ByteArray, SpoolCommonsInfo>>?,
        ): CommonsAdverts? {
            if (rooms == null) return commons?.let { CommonsAdverts(listed = null, ambiguous = emptySet(), sole = it) }
            val listed = HashMap<String, SpoolCommonsInfo>()
            val ambiguous = HashSet<String>()
            for ((tag, info) in rooms) {
                if (tag.size != ScopeCrypto.COMMONS_TAG_BYTES) continue
                val tagHex = hex(tag)
                if (listed.put(tagHex, info) != null) ambiguous += tagHex
            }
            return CommonsAdverts(listed, ambiguous, sole = null)
        }
    }
}
