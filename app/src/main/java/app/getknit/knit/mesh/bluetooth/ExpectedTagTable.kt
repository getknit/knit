package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag

/**
 * Every direct contact's expected tags over the contact window, so [AdvertAliases] resolves a heard tag with one hash
 * lookup (ADR 2026-11.xp6d, "The tag" and "Clocks"). A tag is a function of (key, slot, restart) ([BlindTag.tag]), so
 * the table lists each contact key's tags for every slot of [BlindTag.window] and all [BlindTag.RESTARTS] restarts,
 * keyed by the tag's 26-char form. An unknown tag, a flood of them included, costs a miss and nothing else.
 *
 * - **Incremental.** [slide] at a slot change lists only the slot that entered the window and drops the one that
 *   left it (a wall clock that jumps lists or drops as many as moved); [setKeys] rebuilds only the contacts whose keys
 *   changed. A key's tags are built once per slot, each slot `RESTARTS` HMACs.
 * - **Every key the feed lists** ([AdvertAliases.ContactKey], newest first, up to the store's three): a tag carries no
 *   key generation, so the contact may air any of them (its next key goes ahead of its weekly rotation). A tag heard
 *   under one caps every older one at [previousKeyMaxMs] from then ([lookup]; one tag is no proof the owner rotated:
 *   another contact holds its next key too), and a lapsed key stays unlisted while the feed still names it. A
 *   **replaced newest key** stays listed until a tag under its successor is heard or its cap passes ([lapse], on the
 *   elapsed clock): an idle phone can air a tag minted under the old key for hours.
 * - **Collisions resolve to nobody.** A form that two contacts' keys both list (a contact who sent another's presence
 *   key as her own, or a 2⁻¹⁰⁸ accident) answers no one, however the two were listed or the window moved: last listed
 *   must not win, or the copier would take over the other's tags, contradict his claims and turn his proofs into
 *   replays. Each form keeps every generation that lists it, so unlisting one leaves the other's slots mapped.
 *   [collisionHits] counts the lookups refused this way (never the tag).
 * - **Clocks.** Slots read the wall clock (the sender's slot against this phone's window); the previous key's cap reads
 *   the elapsed clock.
 *
 * Pure, single-threaded with its owner: a [BlindTag.Expected] holds a `Mac`, and a build is up to a window's 140 HMACs
 * per key, so it runs on the transport's confined worker, never the main thread.
 */
internal class ExpectedTagTable(
    private val previousKeyMaxMs: Long,
) {
    /** A heard tag's owner, and whether it was minted under that contact's current key. */
    data class Hit(
        val nodeId: String,
        val current: Boolean,
    )

    private class Generation(
        val key: ByteArray,
        var untilMs: Long?,
    ) {
        val expected = BlindTag.Expected(key)
        val bySlot = HashMap<Long, List<String>>()

        /** Kept past the feed: the newest key a newer one replaced, listed until its cap or a tag under a newer key. */
        var lingering = false
    }

    private class Contact(
        val id: String,
        /** Listed generations, newest first; a lingering replaced key after the feed's. */
        var generations: List<Generation>,
    ) {
        /** Keys the feed still lists that a tag under a newer key retired: never listed again while the feed has them. */
        val retired = ArrayList<ByteArray>()
    }

    private class Entry(
        val contact: Contact,
        val generation: Generation,
    )

    private val contacts = HashMap<String, Contact>()

    /** Every generation listing a form; more than one contact among them is a collision. */
    private val table = HashMap<String, MutableList<Entry>>()
    private var window: LongRange? = null
    private var nextLapseMs = Long.MAX_VALUE

    /** Expected tags listed now. */
    val size: Int get() = table.size

    /** Forms two or more contacts list now: they resolve to nobody. A walk of the table, for diagnostics only. */
    val collided: Int get() = table.values.count { it.isCollision() }

    /** Lookups refused for a collision, since the start. */
    var collisionHits = 0L
        private set

    /** Key generations listed whole over the window, since the start. */
    var builds = 0L
        private set

    /** Slots listed for a key as the window moved, since the start. */
    var slotSteps = 0L
        private set

    /** Tags computed since the start: one HMAC each. */
    var tagsComputed = 0L
        private set

    /**
     * The direct contacts' keys, replacing the whole set ([AdvertAliases.ContactKey]'s rules). Only a key new to its
     * contact is listed; a key the contact already listed keeps its tags whatever its place in the list.
     */
    fun setKeys(
        keys: Map<String, AdvertAliases.ContactKey>,
        nowMs: Long,
        wallMs: Long,
    ) {
        slide(wallMs)
        contacts.keys.filter { it !in keys }.forEach { id -> contacts.remove(id)?.let(::unlist) }
        for ((id, key) in keys) update(id, key, nowMs)
        nextLapseMs = nextLapse()
    }

    /**
     * The owner of [tag] (a 26-char form), or null: unlisted, or listed by two contacts. A hit at [nowMs] under one of a
     * contact's keys caps every older key it still lists, and retires at once one the feed replaced ([retireOlder]).
     */
    fun lookup(
        tag: String,
        nowMs: Long,
    ): Hit? {
        val entries = table[tag] ?: return null
        if (entries.isCollision()) {
            collisionHits++
            return null
        }
        val contact = entries.first().contact
        val heard = contact.generations.indexOfFirst { g -> entries.any { it.generation === g } }
        if (heard >= 0) retireOlder(contact, heard, nowMs)
        return Hit(contact.id, current = heard == 0)
    }

    /** Moves the window to [wallMs]'s: lists the slots that entered it and drops those that left it. */
    fun slide(wallMs: Long) {
        val next = BlindTag.window(wallMs)
        val held = window
        if (held == next) return
        window = next
        for (contact in contacts.values) {
            for (generation in contact.generations) {
                generation.bySlot.keys
                    .filter { it !in next }
                    .forEach { dropSlot(generation, it) }
                next.filter { held == null || it !in held }.forEach {
                    slotSteps++
                    listSlot(contact, generation, it)
                }
            }
        }
    }

    /** Drops every older key whose cap has passed at [nowMs]. Cheap when none is due. */
    fun lapse(nowMs: Long) {
        if (nowMs < nextLapseMs) return
        for (contact in contacts.values) {
            val (due, kept) = contact.generations.partition { (it.untilMs ?: Long.MAX_VALUE) <= nowMs }
            if (due.isEmpty()) continue
            due.forEach {
                unlistGeneration(it)
                // A key the feed still names stays unlisted once it lapsed.
                if (!it.lingering) contact.retired += it.key
            }
            contact.generations = kept
        }
        nextLapseMs = nextLapse()
    }

    private fun nextLapse(): Long =
        contacts.values.flatMap { it.generations }.minOfOrNull { it.untilMs ?: Long.MAX_VALUE } ?: Long.MAX_VALUE

    /**
     * A tag under [contact]'s generation at [index] was heard at [nowMs]. A lingering older key (one the feed replaced
     * with this one as its newest) goes at once: the feed named the successor. A key the feed still lists only lapses,
     * [previousKeyMaxMs] after the first such hit: any other contact of the owner holds the owner's next key and could
     * air one tag under it, and a retirement on that alone would stop us recognising the owner until it rotates (the
     * security review of step 5a).
     */
    private fun retireOlder(
        contact: Contact,
        index: Int,
        nowMs: Long,
    ) {
        val older = contact.generations.drop(index + 1)
        if (older.isEmpty()) return
        val (gone, capped) = older.partition { it.lingering }
        gone.forEach(::unlistGeneration)
        contact.generations = contact.generations - gone.toSet()
        val until = nowMs + previousKeyMaxMs
        capped.forEach { it.untilMs = minOf(it.untilMs ?: Long.MAX_VALUE, until) }
        nextLapseMs = minOf(nextLapseMs, until)
    }

    private fun update(
        id: String,
        key: AdvertAliases.ContactKey,
        nowMs: Long,
    ) {
        val held = contacts[id]
        held?.retired?.removeAll { r -> key.keys.none { it.contentEquals(r) } }
        val wanted = key.keys.filter { k -> held?.retired?.none { it.contentEquals(k) } ?: true }
        val listed =
            wanted.mapIndexed { i, k ->
                (held?.generationFor(k) ?: Generation(k.copyOf(), null)).also {
                    it.lingering = false
                    // A store-listed older key keeps the cap a hit under a newer one set; the two-key form's is reset.
                    val cap = it.untilMs
                    it.untilMs = if (i == 0) null else key.previousUntilMs ?: if (key.capsOlder) nowMs + previousKeyMaxMs else cap
                }
            }
        val kept = listed + lingering(held, key, nowMs).filter { g -> listed.none { it === g } }
        val contact = held ?: Contact(id, kept).also { contacts[id] = it }
        held?.generations?.filter { g -> kept.none { it === g } }?.forEach(::unlistGeneration)
        contact.generations = kept
        for (generation in kept) {
            if (generation.bySlot.isEmpty()) {
                builds++
                window?.forEach { listSlot(contact, generation, it) }
            }
        }
    }

    /**
     * The held generations that outlive this feed: the held newest when the feed replaced it with a new newest (it lingers,
     * capped [previousKeyMaxMs] from now, until a tag under its successor is heard), and one already lingering, within
     * its cap. A key the feed drops otherwise is unlisted at once.
     */
    private fun lingering(
        held: Contact?,
        key: AdvertAliases.ContactKey,
        nowMs: Long,
    ): List<Generation> {
        val gens = held?.generations ?: return emptyList()
        val absent: (Generation) -> Boolean = { g -> key.keys.none { it.contentEquals(g.key) } }
        val replaced =
            gens.firstOrNull()?.takeIf { !it.lingering && absent(it) }?.also {
                it.lingering = true
                it.untilMs = nowMs + previousKeyMaxMs
            }
        return listOfNotNull(replaced) +
            gens.filter { it !== replaced && it.lingering && absent(it) && (it.untilMs ?: Long.MAX_VALUE) > nowMs }
    }

    private fun Contact.generationFor(key: ByteArray): Generation? = generations.firstOrNull { it.key.contentEquals(key) }

    private fun listSlot(
        contact: Contact,
        generation: Generation,
        slot: Long,
    ) {
        val forms =
            generation.expected
                .tags(slot..slot)
                .map { NodeId.fromBytes(it.tag) }
                .toList()
        tagsComputed += forms.size
        if (forms.isEmpty()) return
        generation.bySlot[slot] = forms
        val entry = Entry(contact, generation)
        forms.forEach { table.getOrPut(it) { ArrayList(1) }.add(entry) }
    }

    private fun dropSlot(
        generation: Generation,
        slot: Long,
    ) {
        generation.bySlot.remove(slot)?.forEach { form ->
            val entries = table[form] ?: return@forEach
            entries.removeAll { it.generation === generation }
            if (entries.isEmpty()) table.remove(form)
        }
    }

    private fun unlistGeneration(generation: Generation) {
        generation.bySlot.keys
            .toList()
            .forEach { dropSlot(generation, it) }
    }

    private fun List<Entry>.isCollision(): Boolean = any { it.contact !== first().contact }

    private fun unlist(contact: Contact) {
        contact.generations.forEach(::unlistGeneration)
    }
}
