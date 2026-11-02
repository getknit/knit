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
 * - **A replaced key** stays listed until a tag under its successor is heard ([lookup] retires it) or its cap passes
 *   ([lapse], on the elapsed clock): a tag carries no key generation, and an idle phone can air a tag minted under the
 *   old key for hours.
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
    }

    private class Contact(
        val id: String,
        var current: Generation,
        var previous: Generation?,
    )

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
     * The direct contacts' keys, replacing the whole set ([AdvertAliases.ContactKey]'s rules). Only a contact whose keys
     * changed is rebuilt; a key that moved from current to previous keeps its listed tags.
     */
    fun setKeys(
        keys: Map<String, AdvertAliases.ContactKey>,
        nowMs: Long,
        wallMs: Long,
    ) {
        slide(wallMs)
        contacts.keys.filter { it !in keys }.forEach { id -> contacts.remove(id)?.let(::unlist) }
        for ((id, key) in keys) update(id, key, nowMs)
        nextLapseMs = contacts.values.minOfOrNull { it.previous?.untilMs ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
    }

    /**
     * The owner of [tag] (a 26-char form), or null: unlisted, or listed by two contacts. A hit under a contact's current
     * key retires its previous key: a tag under the successor is on the air.
     */
    fun lookup(tag: String): Hit? {
        val entries = table[tag] ?: return null
        if (entries.isCollision()) {
            collisionHits++
            return null
        }
        val contact = entries.first().contact
        val current = entries.any { it.generation === contact.current }
        val previous = contact.previous
        if (current && previous != null) {
            unlistGeneration(previous)
            contact.previous = null
        }
        return Hit(contact.id, current)
    }

    /** Moves the window to [wallMs]'s: lists the slots that entered it and drops those that left it. */
    fun slide(wallMs: Long) {
        val next = BlindTag.window(wallMs)
        val held = window
        if (held == next) return
        window = next
        for (contact in contacts.values) {
            for (generation in listOfNotNull(contact.current, contact.previous)) {
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

    /** Drops every previous key whose cap has passed at [nowMs]. Cheap when none is due. */
    fun lapse(nowMs: Long) {
        if (nowMs < nextLapseMs) return
        for (contact in contacts.values) {
            val previous = contact.previous ?: continue
            if ((previous.untilMs ?: Long.MAX_VALUE) <= nowMs) {
                unlistGeneration(previous)
                contact.previous = null
            }
        }
        nextLapseMs = contacts.values.minOfOrNull { it.previous?.untilMs ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
    }

    private fun update(
        id: String,
        key: AdvertAliases.ContactKey,
        nowMs: Long,
    ) {
        val held = contacts[id]
        val current = held?.generationFor(key.current) ?: Generation(key.current.copyOf(), null)
        current.untilMs = null
        val previous =
            previousFor(held, key, current, nowMs)?.takeIf { (it.untilMs ?: Long.MAX_VALUE) > nowMs && !it.key.contentEquals(current.key) }
        val contact = held ?: Contact(id, current, null).also { contacts[id] = it }
        listOfNotNull(held?.current, held?.previous).filter { it !== current && it !== previous }.forEach(::unlistGeneration)
        contact.current = current
        contact.previous = previous
        for (generation in listOfNotNull(current, previous)) {
            if (generation.bySlot.isEmpty()) {
                builds++
                window?.forEach { listSlot(contact, generation, it) }
            }
        }
    }

    /** The previous generation [key] names, or the held current one it replaced, its cap set; null for none. */
    private fun previousFor(
        held: Contact?,
        key: AdvertAliases.ContactKey,
        current: Generation,
        nowMs: Long,
    ): Generation? {
        val explicit = key.previous
        return when {
            explicit != null -> {
                (held?.generationFor(explicit) ?: Generation(explicit.copyOf(), null)).also {
                    it.untilMs = key.previousUntilMs ?: (nowMs + previousKeyMaxMs)
                }
            }

            held == null -> {
                null
            }

            held.current !== current -> {
                held.current.also { it.untilMs = nowMs + previousKeyMaxMs }
            }

            else -> {
                held.previous
            }
        }
    }

    private fun Contact.generationFor(key: ByteArray): Generation? =
        listOfNotNull(current, previous).firstOrNull { it.key.contentEquals(key) }

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
        listOfNotNull(contact.current, contact.previous).forEach(::unlistGeneration)
    }
}
