package app.getknit.knit.mesh

import app.getknit.knit.data.presencekey.PresenceKeyRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.PresenceKeyFrame
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * This phone's presence keys and their delivery to linked direct contacts (ADR 2026-11.xp6d, "The presence key";
 * knit-next #285): the engine around [PresenceKeyPolicy].
 *
 * **One lock.** [lock] serialises every [PresenceKeyPolicy.due] evaluation, mint, rotation, grow and seal. It is always
 * taken **outside** any Room transaction and never inside one: under it the engine calls the stores (each opens its own
 * transaction) and the ratchet facade (transaction outer, ratchet mutex inner, as always), so the order is engine lock →
 * transaction → ratchet mutex, everywhere. The inbound arm (`InboundPipeline`'s `CTL_PRESENCE_KEY`, inside a ratchet
 * commit) and the data layer never take it; a path that did would invert that order and wedge two suspended coroutines
 * no thread dump shows (the 051284eb deadlock). The lock is never held across a transport send: [reconcile] seals under
 * it and hands the sealed frames to the links after releasing it.
 *
 * **Keys.** At start and in every pass, [settle] asks [PresenceKeyPolicy.due] and applies the answer until nothing is
 * due, each time against a fresh read of the direct contacts ([direct], never a cached value). A mint writes the last
 * generation ([Store.setLastGeneration]) before the row that uses it. A clock-or-mark advance is allowed once per
 * [PresenceKeyPolicy.DAY_MS] of [elapsed] (a spoofed wall clock cannot burn generations every heal); a process restart
 * permits one.
 *
 * **Delivery: a reconcile, never a queue.** What each live-linked peer is owed ([PresenceKeyPolicy.owed]: a peer with a
 * live proven link, in the fresh direct-contact read, holding `CAP_BLIND_ADVERT` in its link HELLO or its pinned
 * profile, with a confirmed session) is compared with what it was last sent ([sent]: the generations and the session
 * root the seal itself committed under); a difference in either is sealed (`confirmedOnly`, so the engine never opens a
 * session) and sent, signed `relay = false`, over the peer's live link — never flooded, custodied or cast, so no relay
 * learns who this phone's direct contacts are. One record per peer, not per link: a peer on two planes gets one copy.
 * A pass deals only with links the neighbour pass registered ([onLinkUp]); a capability or root trigger that lands before
 * that registration is served by the delayed link-up pass and every heal.
 * The record outlives a link (a relink with nothing changed sends nothing, so no key is sealed under a root the peer
 * left while apart) and lives in memory: a restart sends again. Cost: a receiver that dies between the open and its
 * commit, or a frame lost in a dying link, leaves the peer without the key until the next generation or root change, or
 * our restart — a week at most. Runs on a link
 * proving itself (after a random [LINK_UP_DELAY_MIN_MS]–[LINK_UP_DELAY_MAX_MS] wait, the wiring's), a link dropping, a
 * direct-contact change, a session root change, a capability change, and every heal as the backstop.
 *
 * **A restore's link-owed resets.** The direct contacts a restore's flooded resets do not cover ([onRestore]) get theirs
 * here, point to point: sealed once ([sealReset], `AFTER_WIPE`) at the first live link, kept with the root its seal
 * committed ([Store.recordLinkOwedReset]), and re-sent verbatim at each link-up until the session confirms, then dropped
 * — unless another reset of ours replaced it, or the peer's prekey moved on ([PresenceKeyPolicy.linkReset]). Nothing about
 * them reaches a relay.
 *
 * **A build that sends no keys of its own** ([sendsOwnKeys] off: `BuildConfig.PRESENCE_KEYS`, on in every build as
 * shipped; ADR 2026-11.xp6d's staging) mints, rotates and seals none, so none exists to leak: while links are read in the
 * clear (#286) a ctl 11 at link-up tells a listener the two phones are direct contacts, and the frame can be re-wrapped
 * `relay = true` and flooded (`docs/NEXT_WIRE_BREAK.md` item 14). What it still does is not about keys of its own: it
 * deletes leavers' stored keys and sends a restore's link-owed resets, and the inbound arm still receives, stores and
 * resolves contacts' keys. Own keys an earlier build with the flag on minted are left as they are, never sent again; a
 * later build with it on rotates them by the ordinary rules.
 *
 * **Keys of people who stop being direct contacts** are deleted on the pass that sees them go; the first pass of a
 * session deletes every stored key whose peer is not a direct contact (a removal made while the mesh was stopped).
 *
 * Never logs a key, a tag or a root tag beside a peer id: generations and counts only. Pure of Android: every seam is
 * injected, so `PresenceKeyEngineTest` drives it on the JVM.
 */
class PresenceKeyEngine(
    private val store: Store,
    /** Whether this phone mints, rotates and sends its own keys (the class's "A build that sends no keys of its own"). */
    private val sendsOwnKeys: Boolean = true,
    /** A fresh read of the direct contacts (`contacts/DirectContacts.read`). */
    private val direct: suspend () -> Set<String>,
    /** The peers with a live proven link now, one entry per peer whatever the planes (`MeshTransport.neighbors`). */
    private val linked: () -> Collection<Peer>,
    /** The capabilities of [nodeId]'s pinned profile, or null with none. */
    private val pinnedCapabilities: suspend (nodeId: String) -> Long?,
    /** The `RatchetCrypto.rootTag` of the session held with the peer now, or null with none. */
    private val rootTagFor: suspend (nodeId: String) -> Long?,
    /** Whether the session held with the peer is confirmed. */
    private val sessionConfirmed: suspend (nodeId: String) -> Boolean,
    /** Seals [content] to the peer under a confirmed session only, signed `relay = false`; null when it cannot. */
    private val sealKeys: suspend (nodeId: String, content: MessageContent) -> Sealed?,
    /**
     * Seals a restore's reset to the peer, signed `relay = false`, with the root the seal committed: `AFTER_WIPE`'s plan,
     * or with `remark` the marker under our own pending reset init alone. Null when declined or impossible.
     */
    private val sealReset: suspend (nodeId: String, remark: Boolean) -> Sealed?,
    /** Our side of the session with the peer, for a link-owed reset's step ([PresenceKeyPolicy.linkReset]). */
    private val pendingReset: suspend (nodeId: String) -> PendingReset,
    /** Hands a sealed frame to the peer's live link. */
    private val send: suspend (peer: Peer, frame: Sealed) -> Unit,
    /** The wall clock: generations, activation stamps, the weekly turnover. */
    private val wall: () -> Long,
    /** A monotonic clock (`SystemClock.elapsedRealtime`): the once-a-day floor on clock-or-mark advances. */
    private val elapsed: () -> Long,
    private val metrics: MeshMetrics = MeshMetrics(),
    private val log: (String) -> Unit = {},
    /** Per-decision diagnostics (a link-owed reset's step and link epochs); ids and counts only, never bytes or a tag. */
    private val debug: (String) -> Unit = {},
    private val newKey: () -> ByteArray = { BlindTag.newKey() },
) {
    /** The engine's persisted state: its own key rows, its last generation, the peers' stored keys, the link-owed resets. */
    interface Store {
        suspend fun ownKeys(): List<PresenceKeyPolicy.Own>

        suspend fun applyOwn(
            inserted: List<PresenceKeyPolicy.Own>,
            updated: List<PresenceKeyPolicy.Own>,
            keep: Set<Long>,
        )

        suspend fun lastGeneration(): Long

        suspend fun setLastGeneration(generation: Long)

        suspend fun peersWithKeys(): List<String>

        suspend fun deletePeerKeys(nodeIds: Collection<String>)

        /** Peer → its kept reset, or null until sealed. */
        suspend fun linkOwedResets(): Map<String, KeptReset?>

        suspend fun setLinkOwedResets(peers: Set<String>)

        suspend fun recordLinkOwedReset(
            peer: String,
            kept: KeptReset,
        )

        suspend fun dropLinkOwedReset(peer: String)
    }

    /** A sealed frame: the signed wire, its frame id (the router's seen-set key) and the session root it was sealed under. */
    class Sealed(
        val wire: WireEnvelope,
        val id: String,
        val rootTag: Long,
    )

    /** A link-owed reset as first sealed: its signed wire and the root its seal committed. */
    class KeptReset(
        val wire: ByteArray,
        val rootTag: Long,
    )

    /**
     * Our side of a session a link-owed reset is for: whether it is our own unanswered reset ([ownPendingReset]), and
     * whether its init is against the prekey the peer's profile pins now ([initOnPinnedPrekey]).
     */
    class PendingReset(
        val ownPendingReset: Boolean,
        val initOnPinnedPrekey: Boolean,
    )

    /** What was last sealed to a peer: the generations handed over and the root they were sealed under. */
    private class Sent(
        val generations: List<Long>,
        val rootTag: Long,
    )

    private val lock = Mutex()

    // Guarded by [lock].
    private val sent = HashMap<String, Sent>()

    // The link-owed resets sent, by peer, with the link epoch ([presentLinks]) they were sent on (guarded by [lock]): a mark
    // from another epoch is a reset not sent on the link held now.
    private val resetSentThisLink = HashMap<String, Long>()

    // The links the neighbour pass registered, peer → the epoch of the link held now ([onLinkUp] / [onLinkDepart]),
    // lock-free and synchronous with the pass: a pass only deals with a peer whose link the neighbour pass has seen, and
    // "this link" is the epoch here. [linkEpochs] keeps each peer's last epoch across departures, so a new link counts on.
    private val presentLinks = ConcurrentHashMap<String, Long>()
    private val linkEpochs = ConcurrentHashMap<String, Long>()

    // When the last clock-or-mark advance ran, on [elapsed]; null since the process started, which permits one.
    private var lastClockAdvanceAt: Long? = null

    // The direct contacts the last pass saw, to tell who left; null before the session's first pass.
    private var lastDirect: Set<String>? = null

    @Volatile
    private var started = false

    // Written under [lock] by [pass], cleared lock-free by [beginSession]; see [ownKey] and [departed].
    private val ownKeyState = MutableStateFlow<OwnPresenceKey?>(null)
    private val departedState = MutableStateFlow<Set<String>?>(null)

    // Moved by [beginSession]: a pass that took the lock under an earlier session publishes nothing.
    private val sessionEpoch = AtomicLong()

    // Makes [publish]'s epoch check and its writes one step against [beginSession]'s. Never held across a suspension.
    private val published = Any()

    /**
     * The current own key, published at the end of each pass's settle under [lock] (step 5b, A1): the Bluetooth plane's
     * one source for the key its tags are derived from, through `MeshManager`. Null from [beginSession] until the
     * session's first settle, so the plane never airs a tag under a key the settle is about to replace: a removal or a
     * block made while the mesh was stopped is a fresh pair before the first value, and the person who left never sees a
     * tag they can resolve. Never read from Room: `transport.start()` runs before the first settle. Changes only when the
     * current generation does ([OwnPresenceKey]'s equality): a mark write, a grow and a peer's key change no row of the
     * current key's that it compares, so they emit nothing, and a weekly, clock or mark advance or a fresh pair emits
     * once. With [sendsOwnKeys] off it stays null.
     *
     * A settle that cannot reach nothing-due (a fresh pair or a first mint with no generation left) publishes null. One
     * that throws publishes nothing new; the advertiser's mark write is conditional on the generation still being the
     * current one (`PresenceKeyRepository.markStore`), so a key a half-applied rotation demoted airs nothing. Read the
     * value synchronously where a tag is about to air (`MeshTransport.onOwnPresenceKeySource`): the feed's collector is a
     * dispatch away from it.
     */
    val ownKey: StateFlow<OwnPresenceKey?> = ownKeyState.asStateFlow()

    /**
     * The ids that left an own key's audience while a kept generation was live ([PresenceKeyPolicy.departed]), published
     * with [ownKey] at the end of each pass, against that pass's fresh direct-contact read: half of the `TAG_NEXT`
     * recipient predicate (`MeshManager.hearsTagNext`). Null from [beginSession] until the session's first pass. Published
     * whatever [sendsOwnKeys] says.
     */
    val departed: StateFlow<Set<String>?> = departedState.asStateFlow()

    /**
     * A mesh session is starting (`MeshManager.start`, before the transport): [ownKey] and [departed] go back to null
     * until this session's first pass, so a key the last session published is never handed to an advertiser starting
     * under keys a removal or block made while stopped has since replaced. Lock-free and synchronous, so it lands before
     * the session's collectors subscribe; a pass of an earlier session still holding the lock publishes nothing after it.
     */
    fun beginSession() {
        synchronized(published) {
            sessionEpoch.incrementAndGet()
            ownKeyState.value = null
            departedState.value = null
        }
    }

    /**
     * Publishes [rows]' departures, then their current key, unless [beginSession] ran since the pass read [epoch]. The
     * departures go first, so a `TAG_NEXT` the advertiser sends right after a fresh key reaches it never reads the set
     * from before the rotation. A settle that did not reach [PresenceKeyPolicy.Due.None] ([settled] false: a fresh pair
     * or a first mint whose generation could not be minted) publishes no key at all (the advertiser airs random tags)
     * and keeps the departures it had: the rows still hold the key a leaver holds as the current one.
     */
    private fun publish(
        epoch: Long,
        rows: List<PresenceKeyPolicy.Own>,
        contacts: Set<String>,
        settled: Boolean,
    ) {
        synchronized(published) {
            if (sessionEpoch.get() != epoch) return
            if (settled) departedState.value = PresenceKeyPolicy.departed(rows, contacts)
            if (sendsOwnKeys) {
                ownKeyState.value =
                    PresenceKeyPolicy
                        .keysOf(rows)
                        .current
                        ?.takeIf { settled }
                        ?.let { OwnPresenceKey(it.generation, it.key) }
            }
        }
    }

    /**
     * The first start after a restore (`MeshManager.finishRestore`), before [start]: clamps a restored last generation
     * more than a year past the clock ([PresenceKeyPolicy.clampRestored]) and records [linkOwed] — the direct contacts
     * the restore's flooded resets do not cover — as owed a reset over a link.
     */
    suspend fun onRestore(linkOwed: Set<String>) =
        lock.withLock {
            val last = store.lastGeneration()
            PresenceKeyPolicy.clampRestored(last, wall())?.let {
                store.setLastGeneration(it)
                metrics.onPresenceKeyGenerationClamped()
                log("restored presence-key generation clamped to the clock")
            }
            store.setLinkOwedResets(linkOwed)
            log("restore: ${linkOwed.size} direct contacts owed a reset over a link")
        }

    /**
     * Arms the engine for a mesh session and runs its first pass; triggers before this do nothing. The sent records stay
     * (a process restart is what clears them); a session starts with no link-owed reset sent and no direct-contact set
     * seen, so its first pass also deletes the keys of anyone who stopped being a direct contact while the mesh was stopped.
     */
    suspend fun start() {
        lock.withLock {
            resetSentThisLink.clear()
            lastDirect = null
        }
        started = true
        reconcile("start")
    }

    /**
     * [ids]' last live link dropped. What they were sent is kept: a relink with the same root and generations owes them
     * nothing, so no ctl 11 goes under a root the peer may have left while apart (its restore) — sealed there, it would
     * fail to open and count toward the peer's reset heuristic. Whether a link-owed reset went on the link held now is the
     * epoch compare in [resetFor] ([onLinkDepart] / [onLinkUp] move the epoch synchronously), so this only forgets marks
     * of links no longer held — memory hygiene. It runs async, maybe after a relink's pass marked the new link: a mark of
     * the epoch held now is never touched, or a later pass on that same link would resend the reset.
     */
    suspend fun onLinksDown(ids: Collection<String>) {
        if (ids.isEmpty()) return
        lock.withLock {
            for (id in ids) {
                if (resetSentThisLink[id] != presentLinks[id]) resetSentThisLink.remove(id)
            }
        }
    }

    /** The generations last sealed to [nodeId], or null when none was (a diagnostic: what the engine decided, not what landed). */
    internal suspend fun sentTo(nodeId: String): List<Long>? = lock.withLock { sent[nodeId]?.generations }

    /**
     * [ids] linked (the neighbour pass, synchronously, before their link-up pass is scheduled): each starts a new link
     * epoch, so a link-owed reset marked sent on an earlier link goes again on this one. Lock-free on purpose.
     */
    fun onLinkUp(ids: Collection<String>) {
        for (id in ids) {
            if (presentLinks.containsKey(id)) continue // listed again (a retried or displaced pass) with no departure: the same link
            presentLinks[id] = linkEpochs.merge(id, 1L, Long::plus) ?: 1L
        }
    }

    /**
     * [ids]' last link went (the neighbour pass, synchronously, lock-free): no pass deals with them until a new link, and
     * the next arrival is a new epoch. What the departure clears under the lock is [onLinksDown]'s, async.
     */
    fun onLinkDepart(ids: Collection<String>) {
        ids.forEach { presentLinks.remove(it) }
    }

    /** [ids]' frames were sealed and never left (a send that threw): forgotten, so the next pass seals them again. */
    private suspend fun onSendFailed(ids: Collection<String>) {
        lock.withLock {
            ids.forEach {
                sent.remove(it)
                resetSentThisLink.remove(it)
            }
        }
    }

    /**
     * Whether a profile carrying [capabilities] asks for a [reconcile]: it carries `CAP_BLIND_ADVERT`, so a linked peer
     * may be owed its keys now. Nothing is remembered per id (a Sybil profile flood grows no set): the reconcile is
     * idempotent through what was sent, so a second profile with the bit costs a pass and seals nothing. Never with
     * [sendsOwnKeys] off: a capability owes only keys, and a link-owed reset is owed whatever the peer reads.
     */
    fun asksForKeys(capabilities: Long?): Boolean = sendsOwnKeys && capabilities != null && capabilities and Protocol.CAP_BLIND_ADVERT != 0L

    /**
     * One pass: under [lock], settle the own keys, delete the keys of people who left the direct contacts, seal every
     * link-owed reset and every owed presence key that differs from what was sent; then, released, send them. [cause]
     * is for the log line alone.
     */
    suspend fun reconcile(cause: String) {
        if (!started) return
        val outgoing = lock.withLock { if (started) pass(cause) else emptyList() }
        // One peer's failed send never loses the others', and forgets what that peer was sent, so the next pass resends.
        val failed = outgoing.filter { (peer, frame) -> runCatchingUnlessCancelled { send(peer, frame) }.isFailure }.map { it.first.nodeId }
        if (failed.isNotEmpty()) onSendFailed(failed)
        if (outgoing.isNotEmpty()) {
            log(
                "presence keys ($cause): ${outgoing.size - failed.size} frames to linked contacts, ${failed.size} failed",
            )
        }
    }

    private suspend fun pass(cause: String): List<Pair<Peer, Sealed>> {
        val epoch = sessionEpoch.get()
        val contacts = direct()
        dropLeavers(contacts)
        // With no keys of our own to send, nothing is minted or rotated, and the keys below are none.
        val settled = !sendsOwnKeys || settle(contacts)
        val rows = store.ownKeys()
        publish(epoch, rows, contacts, settled)
        // Counts only: which trigger ran a pass, on how many direct contacts, and whether it settled (`mesh/lab` reports it).
        debug("presence-key pass ($cause): ${contacts.size} direct contacts, ${if (settled) "settled" else "unsettled"}")
        val keys = if (sendsOwnKeys) PresenceKeyPolicy.keysOf(rows) else null
        // Only links the neighbour pass registered: a pass that ran before the pass saw a link would mark a reset sent on an
        // epoch the link-up then moves past, and send it twice on one link. A capability or root trigger that lands before
        // the neighbour pass registers the link is served by the delayed link-up pass and every heal.
        val peers = linked().filter { it.nodeId in contacts && presentLinks.containsKey(it.nodeId) }.distinctBy { it.nodeId }
        val out = mutableListOf<Pair<Peer, Sealed>>()
        val owedResets = store.linkOwedResets()
        // The link-owed resets first: a peer that just took one holds no confirmed session yet, so no key follows it.
        for (peer in peers.filter { it.nodeId in owedResets }) resetFor(peer, owedResets[peer.nodeId])?.let { out += peer to it }
        if (keys != null) for (peer in peers) sealOwed(peer, keys)?.let { out += peer to it }
        return out
    }

    /**
     * The keys [peer] is owed, sealed, when they differ from what it was last sent — in generations or in the session
     * root, the seal's own tag — and the record of them; null when nothing is owed or the seal declined. Each sealed frame
     * gets one [debug] line naming its frame id: `mesh/lab`'s idle-link oracle reads the id with `frame (\S+),`, so keep
     * `frame <id>,` as it is. Ids and counts only, never a key, a tag or a root tag.
     */
    private suspend fun sealOwed(
        peer: Peer,
        keys: PresenceKeyPolicy.Keys,
    ): Sealed? {
        val owed = PresenceKeyPolicy.owed(peer.nodeId, keys).takeIf { it.isNotEmpty() && capable(peer) } ?: return null
        val generations = owed.map { it.generation }
        val tag = rootTagFor(peer.nodeId) ?: return null
        val was = sent[peer.nodeId]
        if (was != null && was.generations == generations && was.rootTag == tag) return null
        // A link that went since the pass listed the peer ([onLinkDepart] is lock-free) is no link to send on: sealed and
        // recorded, the frame would be dropped there and the record would keep the next link from resending it.
        if (!presentLinks.containsKey(peer.nodeId)) return null
        return sealKeys(peer.nodeId, PresenceKeyFrame.content(owed.map { it.generation to it.key }))?.also {
            sent[peer.nodeId] = Sent(generations, it.rootTag)
            metrics.onPresenceKeySent()
            debug("presence keys sealed to ${peer.nodeId}: frame ${it.id}, ${generations.size} generations")
        }
    }

    /** Whether [peer] reads tags: its link HELLO or its pinned profile carries `CAP_BLIND_ADVERT`. */
    private suspend fun capable(peer: Peer): Boolean =
        peer.capabilities and Protocol.CAP_BLIND_ADVERT != 0L ||
            (pinnedCapabilities(peer.nodeId) ?: 0L) and Protocol.CAP_BLIND_ADVERT != 0L

    /**
     * A restore's link-owed reset to the linked [peer] ([kept]: as first sealed, or null before that): dropped once the
     * session confirms; otherwise one frame per link, chosen by [PresenceKeyPolicy.linkReset] — the kept bytes verbatim
     * (a seal consumes a chain key, and a second `AFTER_WIPE` would be declined anyway), a fresh seal kept with its root,
     * the marker under our own pending reset when a sealed frame was lost before it was kept, or nothing when another
     * reset of ours replaced the kept one. A seal the facade declines leaves nothing to send, counted apart.
     */
    private suspend fun resetFor(
        peer: Peer,
        kept: KeptReset?,
    ): Sealed? {
        val id = peer.nodeId
        if (sessionConfirmed(id)) {
            store.dropLinkOwedReset(id)
            log("restore reset to $id answered")
            return null
        }
        // One read of the epoch: [onLinkDepart] is lock-free, so a link that went since the pass listed the peer leaves
        // nothing to send on, and a re-read after the departure would mark the reset sent on no link at all.
        val epoch = presentLinks[id] ?: return null
        if (resetSentThisLink[id] == epoch) return null
        val ours = pendingReset(id)
        val step = PresenceKeyPolicy.linkReset(kept?.rootTag, rootTagFor(id), ours.ownPendingReset, ours.initOnPinnedPrekey)
        debug("link-owed reset to $id: $step on link epoch $epoch, last sent on ${resetSentThisLink[id] ?: "none"}")
        val frame =
            when (step) {
                PresenceKeyPolicy.LinkReset.RESEND -> kept?.let { unwrap(it.wire) }
                PresenceKeyPolicy.LinkReset.SEAL -> sealReset(id, false)
                PresenceKeyPolicy.LinkReset.REMARK -> sealReset(id, true)
                PresenceKeyPolicy.LinkReset.SUPERSEDED -> null
            }
        if (frame == null) {
            store.dropLinkOwedReset(id)
            val superseded = step == PresenceKeyPolicy.LinkReset.SUPERSEDED
            if (superseded) metrics.onLinkOwedResetSuperseded() else metrics.onLinkOwedResetDeclined()
            log("restore reset to $id dropped: ${if (superseded) "another reset replaced it" else "declined"}")
            return null
        }
        if (step != PresenceKeyPolicy.LinkReset.RESEND) store.recordLinkOwedReset(id, KeptReset(encode(frame.wire), frame.rootTag))
        resetSentThisLink[id] = epoch
        metrics.onLinkOwedResetSent()
        return frame
    }

    /** Deletes the stored keys of everyone who left the direct contacts, and drops their link-owed resets. */
    private suspend fun dropLeavers(contacts: Set<String>) {
        val before = lastDirect
        lastDirect = contacts
        // A session's first pass also forgets what it sent anyone no longer a direct contact: one who left while the
        // mesh was stopped and never handed us a key is in no store it can read leavers from.
        if (before == null) sent.keys.retainAll(contacts)
        val leavers = if (before == null) store.peersWithKeys().filterNot { it in contacts } else (before - contacts).toList()
        if (leavers.isEmpty()) return
        store.deletePeerKeys(leavers)
        val owed = store.linkOwedResets().keys
        leavers.filter { it in owed }.forEach { store.dropLinkOwedReset(it) }
        leavers.forEach { sent.remove(it) }
        log("presence keys of ${leavers.size} former direct contacts deleted")
    }

    /**
     * Applies [PresenceKeyPolicy.due] until nothing is due. Bounded: each step settles what made it due. True once it
     * reached [PresenceKeyPolicy.Due.None]; false when a step could not be applied (no generation left to mint) or the
     * bound ran out, so [publish] airs no key a pending fresh pair was about to replace.
     */
    private suspend fun settle(contacts: Set<String>): Boolean {
        repeat(MAX_STEPS) {
            val rows = store.ownKeys()
            val now = wall()
            val allowed = lastClockAdvanceAt?.let { advancedAt -> elapsed() - advancedAt >= PresenceKeyPolicy.DAY_MS } ?: true
            val due = PresenceKeyPolicy.due(rows, contacts, now, allowed, BlindTag::slotOf, BlindTag.WINDOW_AHEAD_SLOTS)
            if (due == PresenceKeyPolicy.Due.None) return true
            if (!apply(due, rows, contacts, now)) return false
        }
        log("presence keys did not settle in $MAX_STEPS steps")
        return false
    }

    /** One rotation's writes: new generations, moved ones, and the generations kept. */
    private class Change(
        val inserted: List<PresenceKeyPolicy.Own>,
        val updated: List<PresenceKeyPolicy.Own>,
        val keep: Set<Long>,
    )

    /** Applies one [due] step; false when a generation could not be minted (nothing changed). */
    private suspend fun apply(
        due: PresenceKeyPolicy.Due,
        rows: List<PresenceKeyPolicy.Own>,
        contacts: Set<String>,
        now: Long,
    ): Boolean {
        val change = changeFor(due, rows, contacts, now) ?: return false
        store.applyOwn(change.inserted, change.updated, change.keep)
        if (due is PresenceKeyPolicy.Due.Advance && due.cause != PresenceKeyPolicy.AdvanceCause.WEEKLY) lastClockAdvanceAt = elapsed()
        if (change.inserted.isNotEmpty()) {
            metrics.onPresenceKeyRotated()
            val current = PresenceKeyPolicy.keysOf(store.ownKeys()).current?.generation
            log("presence key ${due.javaClass.simpleName.lowercase()}: current g=$current")
        }
        return true
    }

    /** The writes [due] asks for, or null when a generation could not be minted. */
    private suspend fun changeFor(
        due: PresenceKeyPolicy.Due,
        rows: List<PresenceKeyPolicy.Own>,
        contacts: Set<String>,
        now: Long,
    ): Change? {
        val keys = PresenceKeyPolicy.keysOf(rows)
        val held = rows.mapTo(HashSet()) { it.generation }
        return when (due) {
            PresenceKeyPolicy.Due.None -> {
                Change(emptyList(), emptyList(), held)
            }

            // With no current key there is nothing to keep; on a shrink the next key may be held by whoever left and goes,
            // while the current one stays as the previous key, never aired again.
            PresenceKeyPolicy.Due.Mint, PresenceKeyPolicy.Due.Fresh -> {
                freshPair(keys, now, contacts)
            }

            is PresenceKeyPolicy.Due.Advance -> {
                advance(keys, now, contacts)
            }

            PresenceKeyPolicy.Due.MintNext -> {
                mint(now, activatedAt = null, contacts)?.let { Change(listOf(it), emptyList(), held + it.generation) }
            }

            is PresenceKeyPolicy.Due.Grow -> {
                val updated =
                    listOfNotNull(
                        keys.next?.let { it.copy(audience = it.audience + due.toNext) },
                        keys.current?.takeIf { due.toCurrent.isNotEmpty() }?.let { it.copy(audience = it.audience + due.toCurrent) },
                    )
                Change(emptyList(), updated, held)
            }
        }
    }

    /** The next key becomes the current one and a new next key is minted; with no next key, a fresh pair. */
    private suspend fun advance(
        keys: PresenceKeyPolicy.Keys,
        now: Long,
        contacts: Set<String>,
    ): Change? {
        val promoted = keys.next ?: return freshPair(keys, now, contacts)
        val next = mint(now, activatedAt = null, contacts) ?: return null
        return Change(
            listOf(next),
            listOf(promoted.copy(activatedAt = now)),
            setOfNotNull(keys.current?.generation, promoted.generation, next.generation),
        )
    }

    /** A new current key and a new next one, the old current kept as the previous key (none on a first mint). */
    private suspend fun freshPair(
        keys: PresenceKeyPolicy.Keys,
        now: Long,
        contacts: Set<String>,
    ): Change? {
        val current = mint(now, activatedAt = now, contacts) ?: return null
        val next = mint(now, activatedAt = null, contacts) ?: return null
        return Change(listOf(current, next), emptyList(), setOfNotNull(keys.current?.generation, current.generation, next.generation))
    }

    /** A new key of the next generation, with that generation recorded as the last before it is used. */
    private suspend fun mint(
        now: Long,
        activatedAt: Long?,
        audience: Set<String>,
    ): PresenceKeyPolicy.Own? {
        val generation = PresenceKeyPolicy.nextGeneration(store.lastGeneration(), now)
        if (generation == null) {
            log("presence key generation would overflow; nothing minted")
            return null
        }
        store.setLastGeneration(generation)
        return PresenceKeyPolicy.Own(generation, newKey(), mintedAt = now, activatedAt = activatedAt, audience = audience)
    }

    private fun encode(wire: WireEnvelope): ByteArray = WireCodec.encodeWire(wire)

    /** A kept reset, back as the frame it was: its wire and the id the router marks seen. */
    private fun unwrap(bytes: ByteArray): Sealed? {
        val wire = WireCodec.decodeWire(bytes) ?: return null
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return null
        return Sealed(wire, env.id, rootTag = 0L) // a resend's tag is the kept one's; nothing reads it
    }

    companion object {
        /** The shortest and longest wait before a link-up's reconcile (the brief: a random 2–20 s). */
        const val LINK_UP_DELAY_MIN_MS = 2_000L
        const val LINK_UP_DELAY_MAX_MS = 20_000L

        // More than any chain of steps the policy can name (mint, then grow; fresh; advance, then grow).
        private const val MAX_STEPS = 6
    }
}

/**
 * [PresenceKeyEngine.Store] over the rows ([PresenceKeyRepository]): the own keys, the peers' keys and a restore's
 * link-owed resets (all local-only tables), and the last generation, the one setting kept beside them (carried in a backup).
 */
internal class PresenceKeyEngineStore(
    private val rows: PresenceKeyRepository,
    private val settings: SettingsStore,
    private val clock: () -> Long,
) : PresenceKeyEngine.Store {
    override suspend fun ownKeys() = rows.ownKeys()

    override suspend fun applyOwn(
        inserted: List<PresenceKeyPolicy.Own>,
        updated: List<PresenceKeyPolicy.Own>,
        keep: Set<Long>,
    ) = rows.applyOwn(inserted, updated, keep)

    override suspend fun lastGeneration() = settings.presenceKeyLastGeneration()

    override suspend fun setLastGeneration(generation: Long) {
        settings.setPresenceKeyLastGeneration(generation)
    }

    override suspend fun peersWithKeys() = rows.peersWithKeys()

    override suspend fun deletePeerKeys(nodeIds: Collection<String>) = rows.deletePeerKeys(nodeIds)

    override suspend fun linkOwedResets(): Map<String, PresenceKeyEngine.KeptReset?> = rows.linkOwedResets()

    override suspend fun setLinkOwedResets(peers: Set<String>) = rows.setLinkOwedResets(peers, clock())

    override suspend fun recordLinkOwedReset(
        peer: String,
        kept: PresenceKeyEngine.KeptReset,
    ) = rows.recordLinkOwedReset(peer, kept)

    override suspend fun dropLinkOwedReset(peer: String) = rows.dropLinkOwedReset(peer)
}
