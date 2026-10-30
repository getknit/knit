package app.getknit.knit.identity

import app.getknit.knit.ART_WHITESPACE_RUN
import java.text.Normalizer
import java.util.Locale

/**
 * Collision-aware display labels: the answer to "which Alice?" when two identities render to the same
 * name (ADR 058; the growth rule below is ADR 2026-09.wuqj).
 *
 * A display name is free text and the mesh has no authority that could make it unique, so the label a
 * peer is shown under is `name` alone in the common case and `name (discriminator)` whenever another
 * identity the device knows renders to the same [NameKey]. The discriminator is the peer's [Alias] — a
 * deterministic function of the node id that every device derives identically with no exchange, and the
 * same token the owner sees as their own placeholder — so two people can tell each other apart by quoting
 * it ("I'm the Alice with ReallyJoyfulFerret").
 *
 * **Labels grow instead of capping out.** Every identity in an index consumes some prefix of its alias
 * phrase ([Alias.phrase]): nothing when its name is unique, one token when another identity shares the
 * name, and one more token each round its rendered text still coincides with another's — a name *chosen*
 * to read like someone's label, or an alias ground to match one. A blank-named peer, whose rendered name
 * already *is* the first token, grows a continuation instead (`ReallyJoyfulFerret (QuietlyBoldCedar)`).
 * Distinct ids have distinct digests, so any two separate within the ten tokens the digest carries:
 * every label in a [PeerLabelIndex] is distinct by construction, and only a SHA-256 collision reaches the
 * guard that stops the loop.
 *
 * This is collision-*evident*, **not** anti-impersonation. A token is 24 bits, so a keypair whose alias
 * matches a target's is minutes of grinding — but the match can only make *both* labels grow to two tokens
 * (48 bits, weeks on a GPU) and then three (72 bits, out of reach), never coincide; the growth is the tell.
 * A plain adoption of a contact's name, with no grinding, still shows two Alices with different aliases and
 * nothing says which is real — and so does a lookalike of it: [NameKey] folds single-letter Latin, Greek and
 * Cyrillic confusables and drops invisible characters (ADR 2026-10.2kfr), so "Alice" spelled with a Cyrillic е or
 * a variation selector collides with "Alice" and both get the suffix. A multi-letter lookalike (`rn` for `m`) still
 * reads as its own name. Trust stays with `verified` and the safety number.
 *
 * **A clash fades** (ADR 2026-10.k8ge). The caller names the ids that have gone quiet — a stranger not heard
 * from in a week, never a contact or this device — and those give way: they stay labelled apart from
 * everyone, but no longer add a token to anyone else, so a stranger met once does not suffix a contact for
 * good.
 *
 * Pure Kotlin, no Android dependencies, unit-tested on the JVM (`PeerLabelsTest`, `NameKeyTest`).
 */
object PeerLabels {
    /**
     * Builds the index over the **universe** of identities the device knows — every cached peer row plus
     * [self] — keyed by node id (a seeded self row and [self] collapse to one entry; a peer never collides
     * with itself). [peers] pairs a node id with its stored, possibly blank, profile name.
     *
     * Pass 1 groups the universe by [NameKey] and gives every member of a shared name one token. The
     * rounds that follow group the rendered texts and give every member of a shared text one more token,
     * until all texts are distinct.
     *
     * **[stale] ids give way** (ADR 2026-10.k8ge): a stranger not heard from in a week. The rest of the
     * universe — the *live* ids, [self] always among them — is labelled by the passes above as if the stale ids
     * were not there, so a contact whose only namesake has gone quiet reads plain again. A stale id still reads
     * apart from everyone: it starts at the tokens it would hold against the whole universe (never fewer, so a
     * look-alike that ground a contact's alias and then went quiet cannot settle on the label the contact wore
     * while both were live) and grows past every text that still matches, live texts held fixed. Empty, it is
     * exactly the index without it.
     */
    fun index(
        peers: Iterable<Pair<String, String>>,
        self: Pair<String, String>? = null,
        stale: Set<String> = emptySet(),
    ): PeerLabelIndex {
        val stored = LinkedHashMap<String, String>()
        for ((id, name) in peers) stored[id] = name
        if (self != null) stored[self.first] = self.second
        val phrases = HashMap<String, List<String>>()
        val names = HashMap<String, String>()
        val idsByKey = HashMap<String, MutableSet<String>>()
        for ((id, storedName) in stored) {
            phrases[id] = Alias.tokens(id)
            val name = storedName.takeUnless { NameKey.isBlankName(it) } ?: phrases.getValue(id).first()
            names[id] = name
            idsByKey.getOrPut(NameKey.of(name)) { LinkedHashSet() }.add(id)
        }
        val universe = Universe(stored, names, phrases)
        val full = universe.consumedAmong(stored.keys)
        val yielding = stored.keys.filterTo(HashSet()) { it in stale && it != self?.first }
        val consumed = if (yielding.isEmpty()) full else universe.yielding(full, yielding)
        val idsByText = HashMap<String, MutableSet<String>>()
        for (id in stored.keys) idsByText.getOrPut(universe.text(id, consumed.getValue(id))) { LinkedHashSet() }.add(id)
        return PeerLabelIndex(stored, idsByKey, idsByText, consumed, phrases)
    }
}

/** The token counts of one universe's labels, every member's recorded — what [PeerLabelIndex.labelFor] reads. */
private class Universe(
    private val stored: Map<String, String>,
    private val names: Map<String, String>,
    private val phrases: Map<String, List<String>>,
) {
    fun text(
        id: String,
        consumed: Int,
    ): String = PeerLabel.text(names.getValue(id), discriminatorOf(phrases.getValue(id), stored[id], consumed))

    /** The rule over [ids] alone: a token for each member of a shared name, then rounds until no text repeats. */
    fun consumedAmong(ids: Collection<String>): MutableMap<String, Int> {
        val shared = ids.groupingBy { NameKey.of(names.getValue(it)) }.eachCount()
        val consumed = HashMap<String, Int>()
        for (id in ids) {
            val namesake = shared.getValue(NameKey.of(names.getValue(id))) > 1
            consumed[id] = tokensInName(stored[id]) + if (namesake) 1 else 0
        }
        growUntilDistinct(consumed, frozen = emptySet())
        return consumed
    }

    /** The live ids labelled among themselves; each of [stale] from where [full] put it, grown past every text. */
    fun yielding(
        full: Map<String, Int>,
        stale: Set<String>,
    ): MutableMap<String, Int> {
        val live = stored.keys - stale
        val consumed = consumedAmong(live)
        for (id in stale) consumed[id] = full.getValue(id)
        growUntilDistinct(consumed, frozen = live)
        return consumed
    }

    /**
     * Each round, every id whose text another id shares consumes one more token — except a [frozen] one,
     * whose text the round reads but never moves. Bounded by the digest.
     */
    private fun growUntilDistinct(
        consumed: MutableMap<String, Int>,
        frozen: Set<String>,
    ) {
        val texts = HashMap<String, String>()
        for ((id, k) in consumed) texts[id] = text(id, k)
        for (round in 0 until Alias.MAX_TOKENS) {
            val colliding =
                texts.entries
                    .groupBy({ it.value }, { it.key })
                    .values
                    .filter { it.size > 1 }
                    .flatten()
                    .filter { it !in frozen }
            if (colliding.isEmpty()) return
            var grew = false
            for (id in colliding) {
                val k = consumed.getValue(id)
                if (k < Alias.MAX_TOKENS) {
                    consumed[id] = k + 1
                    texts[id] = text(id, k + 1)
                    grew = true
                }
            }
            // Nothing left to grow: two ids share every token, i.e. a SHA-256 collision.
            if (!grew) return
        }
    }
}

/**
 * The name to show for a person plus, when another known identity renders to the same name, the
 * [discriminator] that tells them apart. [name] is exactly [displayNameFor]'s answer; [alias] is what the
 * person would quote — their [Alias], grown by the tokens the index needed — and is shown outright on
 * precision surfaces such as the mention picker and a profile.
 */
data class PeerLabel(
    val nodeId: String,
    val name: String,
    val alias: String,
    val discriminator: String?,
) {
    /** The rendered label: `name`, or `name (discriminator)` when one is needed. */
    val text: String get() = text(name, discriminator)

    companion object {
        /** The single format every surface — and the mention token — uses; deliberately not localized. */
        fun text(
            name: String,
            discriminator: String?,
        ): String = if (discriminator == null) name else "$name ($discriminator)"
    }
}

/**
 * A snapshot of the universe's name collisions (see [PeerLabels.index]). [labelFor] is O(1) for a member
 * and also answers for a node id *outside* the universe (a sender whose profile has not been pinned yet):
 * it grows exactly as far as it must to read apart from every member, and never changes a member.
 * [consumed] holds every member's token count, live and stale alike: a member that fell through to [resolve]
 * would be judged against the stale ids the index deliberately kept out of its live labels.
 */
data class PeerLabelIndex(
    private val storedNames: Map<String, String>,
    private val idsByKey: Map<String, Set<String>>,
    private val idsByText: Map<String, Set<String>>,
    private val consumed: Map<String, Int>,
    private val phrases: Map<String, List<String>>,
) {
    /** The label for [nodeId] given its stored profile name (defaults to the universe's own record of it). */
    fun labelFor(
        nodeId: String,
        storedName: String? = storedNames[nodeId],
    ): PeerLabel {
        val name = displayNameFor(storedName, nodeId)
        val words = phrases[nodeId] ?: Alias.tokens(nodeId)
        val k =
            consumed[nodeId]?.takeIf { storedName == storedNames[nodeId] }
                ?: resolve(nodeId, name, words, storedName)
        return PeerLabel(
            nodeId = nodeId,
            name = name,
            alias = words.take(maxOf(k, 1)).joinToString(" "),
            discriminator = discriminatorOf(words, storedName, k),
        )
    }

    /**
     * The tokens an id needs against the snapshot — an outside id, or a member asked about under a name
     * other than its stored one (the contact-card preview): one when a member shares the [NameKey], then
     * one more while a member's final text still matches.
     */
    private fun resolve(
        nodeId: String,
        name: String,
        words: List<String>,
        storedName: String?,
    ): Int {
        var k = tokensInName(storedName)
        if ((idsByKey[NameKey.of(name)].orEmpty() - nodeId).isNotEmpty()) k++
        while (k < Alias.MAX_TOKENS && collides(nodeId, PeerLabel.text(name, discriminatorOf(words, storedName, k)))) k++
        return k
    }

    private fun collides(
        nodeId: String,
        text: String,
    ): Boolean = (idsByText[text].orEmpty() - nodeId).isNotEmpty()

    companion object {
        /** An index over nothing: every label is undiscriminated. */
        val EMPTY = PeerLabelIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/** Tokens the rendered name already spends: one when it *is* the alias (a blank or invisible name), else none. */
private fun tokensInName(storedName: String?): Int = if (NameKey.isBlankName(storedName)) 1 else 0

/** The tokens after those in the name, up to [consumed]; null when the label spends none beyond the name. */
private fun discriminatorOf(
    words: List<String>,
    storedName: String?,
    consumed: Int,
): String? {
    val from = tokensInName(storedName)
    return if (consumed <= from) null else words.subList(from, consumed).joinToString(" ")
}

/**
 * The collision key of a display name (ADR 058, amended by ADR 2026-10.2kfr), in this order:
 * 1. NFKC, so "Ａlice" and the "ﬁ" ligature fold to their plain forms;
 * 2. every single-letter Latin, Greek and Cyrillic lookalike in UTS #39 15.1.0 `confusables.txt` ([FOLD]) folded to
 *    its ASCII letter, on the case-preserved string — UTS #39 is case-sensitive (Cyrillic `В` U+0412 looks like
 *    `B`, `в` U+0432 does not look like `b`), and the table includes its own Latin capital I → l, so "AIice" is
 *    "Alice";
 * 3. lower-cased in the root locale, then ς folded to σ (Java applies Final_Sigma, Swift may not), then l folded to i:
 *    step 2's I → l alone would stop "ALICE" keying with "Alice", so every I, i, l and their lookalikes share one
 *    letter, at the cost of a suffix between names that differ only by i and l ("Eli" and "Ell");
 * 4. format (Cf), nonspacing (Mn) and enclosing (Me) marks, the literal Default_Ignorable ranges ([DROP]) and the
 *    Braille blank U+2800 dropped — after lowercasing, so the U+0307 that `İ` lowercases to goes too. NFKC already
 *    composed every mark with a precomposed form, so "Nguyễn" keeps its `ễ`;
 * 5. regex `\s` runs (ART's set) collapsed to one space and the ends trimmed.
 *
 * So "Alice", "ALICE", "Alice" with a zero-width space, "Alice" with a Cyrillic е and "AIice" are one name
 * (`aiice`), and a name of only invisible characters is empty ([isBlankName]). The fold is generous on purpose: a
 * collision costs only a suffix, so two real names that fold together (a Hindi name with and without a vowel sign)
 * both show their alias words — nothing is merged, hidden or refused. Multi-character confusables (`rn` → `m`) and
 * cross-script skeletons beyond [FOLD] are out of scope. The key never leaves the device; knit-ios runs the same
 * five steps (`NameKey.of`), and `vectors/name-key-v1.json` pins both. Categories come from the platform's Unicode
 * tables (ART's ICU on a phone), so a code point assigned after the older side's Unicode version may differ: an
 * accepted residue.
 */
object NameKey {
    /** The key of [name]; empty when [name] is only whitespace and invisible characters. */
    fun of(name: String): String {
        val composed = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val folded = StringBuilder(composed.length)
        composed.forEachCodePoint { cp ->
            val letter = FOLD[cp]
            if (letter != null) folded.append(letter) else folded.appendCodePoint(cp)
        }
        val lower =
            folded
                .toString()
                .lowercase(Locale.ROOT)
                .replace(FINAL_SIGMA, SIGMA)
                .replace(SMALL_L, SMALL_I)
        val kept = StringBuilder(lower.length)
        lower.forEachCodePoint { cp -> if (!dropped(cp)) kept.appendCodePoint(cp) }
        return kept.toString().replace(ART_WHITESPACE_RUN, " ").trim()
    }

    /**
     * Whether a person or group with stored name [name] shows its alias (or member-derived title) instead: null, or
     * a [of] key that is empty. Display only — what a frame carries and every composer gate still use `isBlank`, so
     * a group named only U+3164 is still a name on the wire.
     */
    fun isBlankName(name: String?): Boolean = name == null || of(name).isEmpty()

    private const val FINAL_SIGMA = '\u03C2'
    private const val SIGMA = '\u03C3'
    private const val SMALL_L = 'l'
    private const val SMALL_I = 'i'
    private const val BRAILLE_BLANK = 0x2800

    private fun dropped(cp: Int): Boolean {
        when (Character.getType(cp).toByte()) {
            Character.FORMAT, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> return true
        }
        return cp == BRAILLE_BLANK || DROP.any { cp in it }
    }

    private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            action(cp)
            i += Character.charCount(cp)
        }
    }

    /**
     * Every UTS #39 15.1.0 `confusables.txt` entry (sha256 8289f833…69fd5ed) whose source is one code point in Latin
     * U+0041–U+007A, Greek U+0370–U+03FF or Cyrillic U+0400–U+052F and whose prototype is one ASCII letter: 73
     * sources, each keyed to its letter. A literal, never derived at run time.
     */
    @Suppress("MagicNumber") // the code points are the table
    internal val FOLD: Map<Int, Char> =
        listOf(
            'A' to listOf(0x0391, 0x0410),
            'a' to listOf(0x03B1, 0x0430),
            'B' to listOf(0x0392, 0x0412),
            'b' to listOf(0x042C),
            'C' to listOf(0x03F9, 0x0421),
            'c' to listOf(0x03F2, 0x0441),
            'd' to listOf(0x0501),
            'E' to listOf(0x0395, 0x0415),
            'e' to listOf(0x0435, 0x04BD),
            'F' to listOf(0x03DC),
            'G' to listOf(0x050C),
            'H' to listOf(0x0397, 0x041D),
            'h' to listOf(0x04BB),
            'i' to listOf(0x037A, 0x03B9, 0x0456, 0x04CF),
            'J' to listOf(0x037F, 0x0408),
            'j' to listOf(0x03F3, 0x0458),
            'K' to listOf(0x039A, 0x041A),
            'l' to listOf(0x0049, 0x0399, 0x0406, 0x04C0),
            'M' to listOf(0x039C, 0x03FA, 0x041C),
            'N' to listOf(0x039D),
            'O' to listOf(0x039F, 0x041E),
            'o' to listOf(0x03BF, 0x03C3, 0x043E),
            'P' to listOf(0x03A1, 0x0420),
            'p' to listOf(0x03C1, 0x03F1, 0x0440),
            'q' to listOf(0x051B),
            'r' to listOf(0x0433),
            'S' to listOf(0x0405),
            's' to listOf(0x0455),
            'T' to listOf(0x03A4, 0x0422),
            'u' to listOf(0x03C5),
            'V' to listOf(0x0474),
            'v' to listOf(0x03BD, 0x0475),
            'W' to listOf(0x051C),
            'w' to listOf(0x0461, 0x051D),
            'X' to listOf(0x03A7, 0x0425),
            'x' to listOf(0x0445),
            'Y' to listOf(0x03A5, 0x03D2, 0x0423, 0x04AE),
            'y' to listOf(0x03B3, 0x0443, 0x04AF),
            'Z' to listOf(0x0396),
        ).flatMap { (letter, sources) -> sources.map { it to letter } }
            .toMap()

    /**
     * The literal Default_Ignorable_Code_Point ranges of Unicode 15.1 (`DerivedCoreProperties.txt`), dropped whatever
     * the platform's general category says — U+034F, the Hangul fillers and the variation selectors are not Cf.
     */
    @Suppress("MagicNumber") // the code points are the table
    internal val DROP: List<IntRange> =
        listOf(
            0x00AD..0x00AD,
            0x034F..0x034F,
            0x061C..0x061C,
            0x115F..0x1160,
            0x17B4..0x17B5,
            0x180B..0x180F,
            0x200B..0x200F,
            0x202A..0x202E,
            0x2060..0x206F,
            0x3164..0x3164,
            0xFE00..0xFE0F,
            0xFEFF..0xFEFF,
            0xFFA0..0xFFA0,
            0xFFF0..0xFFF8,
            0x1BCA0..0x1BCA3,
            0x1D173..0x1D17A,
            0xE0000..0xE0FFF,
        )
}
