package app.getknit.knit.identity

import java.security.MessageDigest

/**
 * Turns a stable [Identity] node id into a friendly alias — one PascalCase "AdverbAdjectiveNoun" token
 * such as `ReallyJoyfulFerret` — so a peer who hasn't set a profile name shows something human-readable
 * instead of a raw node id, and so two peers who *share* a name can be told apart by it (ADR 058,
 * `PeerLabels`).
 *
 * The alias is a word encoding of a digest of the node id: `SHA-256("knit-alias-v2:" + nodeId)` is 32
 * bytes, and each byte is a whole index into one of three frozen 256-entry word lists ([ALIAS_ADVERBS],
 * [ALIAS_ADJECTIVES], [ALIAS_NOUNS] in `AliasWords.kt`), three bytes to a token. No modulo bias, nothing
 * to re-roll, and an iOS port reproduces every alias from the salt and the lists alone. The mapping is a
 * pure, deterministic function of the node id, so **every device derives the same alias for the same node
 * id** with no extra exchange: nothing is broadcast or persisted, and the wire format / database stay
 * untouched.
 *
 * [aliasFor] is the first token — 24 bits, the alias everyone sees and quotes. The digest carries ten
 * ([MAX_TOKENS]); [phrase] exposes a longer prefix, which `PeerLabels` appends one token at a time
 * whenever two labels would otherwise read the same, so an alias ground to match a target's can only make
 * *both* labels longer, never equal (the argument and the numbers are in `PeerLabels`).
 *
 * Avoiding offensive output is a *pairwise* problem ("FatCow" is offensive even though neither word is),
 * and a byte-exact mapping cannot re-roll, so it is handled entirely in curation: the adverbs and
 * adjectives are positive/neutral only — no body, appearance, identity, or political terms — the nouns are
 * neutral (animals, nature, gems, objects), and `AliasTest` cross-checks every word and every adjacent
 * concatenation against the shipped profanity list.
 *
 * Pure Kotlin with no Android dependencies so it is unit-tested on the JVM (see `AliasTest`).
 */
object Alias {
    /** Tokens the digest carries: 32 SHA-256 bytes at three (adverb, adjective, noun) per token. */
    const val MAX_TOKENS = 10

    /** The friendly alias for [nodeId] — the first token of its phrase — stable across calls and devices. */
    fun aliasFor(nodeId: String): String = tokens(nodeId, 1).single()

    /** The first [count] tokens of [nodeId]'s phrase, space-separated: `phrase(id, k)` is a prefix of `phrase(id, k + 1)`. */
    fun phrase(
        nodeId: String,
        count: Int,
    ): String = tokens(nodeId, count).joinToString(" ")

    /**
     * The first [count] tokens of [nodeId]'s phrase. Over the digest `d`, token `t` is
     * `ALIAS_ADVERBS[d[3t]] + ALIAS_ADJECTIVES[d[3t + 1]] + ALIAS_NOUNS[d[3t + 2]]`.
     */
    fun tokens(
        nodeId: String,
        count: Int = MAX_TOKENS,
    ): List<String> {
        require(count in 1..MAX_TOKENS) { "count must be in 1..$MAX_TOKENS, was $count" }
        val digest = MessageDigest.getInstance("SHA-256").digest((SALT + nodeId).encodeToByteArray())
        return List(count) { t ->
            val at = t * WORDS_PER_TOKEN
            ALIAS_ADVERBS[digest.index(at)] + ALIAS_ADJECTIVES[digest.index(at + 1)] + ALIAS_NOUNS[digest.index(at + 2)]
        }
    }

    /** The derivation's version: a new salt re-aliases every peer on every device. */
    private const val SALT = "knit-alias-v2:"
    private const val WORDS_PER_TOKEN = 3

    @Suppress("MagicNumber") // the `and 0xFF` mask reads a signed byte as an unsigned list index
    private fun ByteArray.index(i: Int): Int = this[i].toInt() and 0xFF
}

/**
 * The name to show for a person: their stored profile [storedName] if they set one, otherwise the
 * friendly alias derived from [nodeId] — also for a name made only of invisible characters ([NameKey.isBlankName]).
 * Replaces the old `.ifBlank { nodeId }` fallbacks scattered across the UI and notifications. A surface that lists
 * several people should resolve through [PeerLabelIndex.labelFor] instead, which appends the alias whenever two
 * known identities render to the same name (ADR 058) — this function alone cannot tell two "Alice"s apart.
 */
fun displayNameFor(
    storedName: String?,
    nodeId: String,
): String = storedName?.takeUnless { NameKey.isBlankName(it) } ?: Alias.aliasFor(nodeId)
