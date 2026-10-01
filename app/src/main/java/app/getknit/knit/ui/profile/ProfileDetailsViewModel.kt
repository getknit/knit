package app.getknit.knit.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.contacts.ContactRemover
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.groupFaces
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.IntroState
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.contacts.ContactStanding
import app.getknit.knit.ui.contacts.contactStanding
import app.getknit.knit.ui.contacts.observeContactSignals
import app.getknit.knit.ui.reachOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The result of scanning a peer's identity QR, surfaced once to the screen then consumed. */
enum class VerifyScanResult { MATCH, MISMATCH }

/** This device's identity, loaded once for safety-number/QR rendering. */
private data class MyIdentity(
    val nodeId: String,
    val bundle: String,
)

/**
 * What this phone knows about its history with the peer: the groups whose roster holds you both, and when
 * a radio of ours first and last sighted a radio of theirs.
 *
 * The two halves are drawn by different sections — the groups under "In common", the stamps under
 * "Details", because when you met is a fact about the contact rather than something you share — so this
 * holder carries no "is it empty" of its own; each section asks about the half it draws.
 *
 * The two met stamps are historical and short-range only — `met_peers` is written from the *nearby* set,
 * so a contact reached over LoRa or a relay may have none at all. [ProfileDetailsUiState.reach] stays the
 * only claim about now (ADR 2026-09.2ajk), which is why these are worded "met", never "seen".
 */
data class InCommon(
    val groups: List<SharedGroup> = emptyList(),
    val firstMetAt: Long? = null,
    val lastMetAt: Long? = null,
) {
    companion object {
        val EMPTY = InCommon()
    }
}

/**
 * One group this phone and the peer are both in, resolved for a row that draws like the chat list's:
 * the same avatar (photo, else the member cluster) and the same title.
 *
 * [title] comes from `groupTitle`, not `GroupEntity.name` — an unnamed group carries a blank name on the
 * wire on purpose, and each device renders its own default from the members it can name. Reading the
 * column raw prints the empty string, which is how three shared groups once rendered as ", , Sihaya".
 */
data class SharedGroup(
    val groupId: String,
    val title: String,
    val photoHash: String?,
    val faces: List<GroupFace>,
)

/**
 * What the profile's "Remove contact" offers for this peer (ADR 2026-09.adgd), from their [ContactStanding]
 * by [removalFor]. The groups are drawn like [InCommon]'s, but only the accepted, active ones — the ones that
 * actually keep the peer a contact; a group invitation still in Message Requests is in common yet binds no one.
 */
sealed interface ContactRemoval {
    /** Not a contact (a stranger, a request, blocked — or ourselves): the menu offers no Remove. */
    data object NotOffered : ContactRemoval

    /**
     * Removing clears something. [keptBy] are the groups that keep the peer a contact anyway, named in the
     * confirm; [clearsVerification] says the out-of-band key check goes too.
     */
    data class Removes(
        val keptBy: List<SharedGroup>,
        val clearsVerification: Boolean,
    ) : ContactRemoval

    /** A contact only through [groups]: there is nothing of ours to clear, so the dialog explains instead. */
    data class GroupsOnly(
        val groups: List<SharedGroup>,
    ) : ContactRemoval
}

/** [standing] as the menu and its dialog present it; [shared] draws a binding group like an in-common row. */
internal fun removalFor(
    standing: ContactStanding,
    shared: (GroupEntity) -> SharedGroup,
): ContactRemoval =
    when {
        !standing.isContact -> ContactRemoval.NotOffered
        standing.ownSignals -> ContactRemoval.Removes(standing.bindingGroups.map(shared), standing.verified)
        else -> ContactRemoval.GroupsOnly(standing.bindingGroups.map(shared))
    }

/** A remote peer's profile as shown on the read-only details screen. */
data class ProfileDetailsUiState(
    val nodeId: String,
    val displayName: String,
    // The peer's alias — always shown here, since this is where a person learns the word pair that tells
    // "their" Alice from another — and the ` (Alias)` suffix already inside [displayName] when another
    // known peer shares the name (ADR 058).
    val alias: String = Alias.aliasFor(nodeId),
    val discriminator: String? = null,
    val status: String,
    val avatarHash: String?,
    // Live presence, by the best evidence we have — the same three tiers Diagnostics sorts its sections
    // by, so a peer it lists under "Reachable via relay" never reads as offline here.
    val reach: Reach,
    val isBlocked: Boolean,
    // E2E verification: whether we hold the peer's key yet, whether the user has verified it, the
    // human-comparable safety number (null until both keys are known), and our own QR payload.
    val hasKey: Boolean = false,
    val verified: Boolean = false,
    val safetyNumber: String? = null,
    val myQrPayload: String? = null,
    // Where a contact-card intro with this peer stands (null when none is pending or recently confirmed).
    val intro: IntroState? = null,
    // The peer's declared "open to chat" flag, as their latest profile carried it; shown only when set.
    val openToChat: Boolean = false,
    // The Meshtastic board their latest profile claims (`peers.loraNode`), already in the `!hex` form every
    // radio client writes a node number in, or null when the profile named none. Self-asserted, like the
    // status: it says which radio to expect them on, never that a post from it was theirs.
    val loraNodeLabel: String? = null,
    // Shared groups and the met stamps; the section is absent entirely when there is nothing to show.
    val inCommon: InCommon = InCommon.EMPTY,
    // What "Remove contact" would do, and whether the overflow offers it at all.
    val removal: ContactRemoval = ContactRemoval.NotOffered,
)

/**
 * Backs the read-only Profile Details screen for another peer (keyed by [nodeId]). It surfaces the
 * peer's cached profile (name/status/avatar from the `peers` table), live presence (the [Reach] tier —
 * the short-range neighbor set, the long-range reach set and the Internet plane's per-scope presence),
 * block state, and the end-to-end key-verification state (safety number + QR + verified flag). A peer
 * we've only just met (no cached profile row yet) still resolves to a friendly alias.
 */
class ProfileDetailsViewModel(
    private val nodeId: String,
    private val peers: PeerRepository,
    groups: GroupRepository,
    metPeers: MetPeerRepository,
    meshManager: MeshController,
    private val settings: SettingsStore,
    identity: Identity,
    messages: MessageRepository,
    private val remover: ContactRemover,
    // The per-spool status flow, not the repository that produces it: the production flow is an infinite
    // poller, and under a test's virtual clock its `delay` is instant, so a test driving this VM with
    // `advanceUntilIdle()` could never reach idle. Taking the flow lets a test supply a finite one.
    spoolStatuses: Flow<List<SpoolStatus>>,
    // Wall clock, for ageing the Internet plane's per-scope presence stamps. Injected so a test can drive
    // the linger; [spoolStatuses] re-emits on its poll, so an expiry lands within one.
    // For the one localized string a shared group can need: the fallback title of a group nobody named.
    // The same dependency, for the same reason, that `GroupDetailsViewModel` takes.
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val me = MutableStateFlow<MyIdentity?>(null)

    // Our node id alone, resolved on the main dispatcher (as the contacts picker resolves it) rather than
    // with [me]'s key bundle on IO: the contact standing keys on it, and nothing about it needs the bundle.
    private val myNodeId = MutableStateFlow<String?>(null)

    private val _removed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once a [removeContact] has finished writing, so the screen can close. */
    val removed: SharedFlow<Unit> = _removed.asSharedFlow()

    // One directory flow for both combines below: the labels it carries name a group's members, and a
    // second `observeDirectory()` would rebuild the whole label index on every peer write.
    private val directory = peers.observeDirectory()

    private val _scanResult = MutableStateFlow<VerifyScanResult?>(null)
    val scanResult: StateFlow<VerifyScanResult?> = _scanResult.asStateFlow()

    // Latest pinned key for the peer, captured for the scan comparison (avoids re-reading the DB).
    @Volatile
    private var peerBundle: String? = null

    // Latest device tag for the peer, captured so block/unblock can keep the block sticky across the
    // peer regenerating its key (and thus its nodeId). See [DeviceTag].
    @Volatile
    private var peerDeviceTag: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            me.value = MyIdentity(identity.nodeId(), identity.publicKeyBundle())
        }
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // The presence tier, pre-combined so the main [state] combine stays within its five-source limit.
    // `distinctUntilChanged` matters: the spool poller re-emits on a fixed interval whether or not anything
    // moved, and without it the whole profile would recompute every poll for a tier that rarely changes.
    private val reach: Flow<Reach> =
        combine(
            meshManager.neighbors,
            meshManager.reachable,
            spoolStatuses,
        ) { neighbors, reachable, spools ->
            reachOf(
                nodeId,
                nearby = neighbors.mapTo(mutableSetOf()) { it.nodeId },
                reachable = reachable.mapTo(mutableSetOf()) { it.nodeId },
                spoolPresent = spoolPresentPeers(spools, clock()),
            )
        }.distinctUntilChanged()

    // Groups in common plus the met stamps, pre-combined for the same reason [reach] is: the main [state]
    // combine is already at its five-source limit.
    private val inCommon: Flow<InCommon> =
        combine(
            groups.observeGroupsWith(nodeId),
            metPeers.observe(nodeId),
            directory,
            me,
        ) { shared, met, dir, myId ->
            InCommon(
                groups = shared.map { sharedGroup(it, dir, myId?.nodeId) },
                firstMetAt = met?.firstMetAt,
                lastMetAt = met?.lastMetAt,
            )
        }.distinctUntilChanged()

    /** The same two helpers the chat list titles and draws a group with (`ui/ConversationTitles.kt`). */
    private fun sharedGroup(
        group: GroupEntity,
        dir: PeerDirectory,
        myNodeId: String?,
    ): SharedGroup {
        val memberIds = GroupMembersStore.decode(group.members)
        return SharedGroup(
            groupId = group.groupId,
            title =
                groupTitle(
                    storedName = group.name,
                    memberIds = memberIds,
                    selfId = myNodeId,
                    fallback = context.getString(R.string.group_unnamed),
                ) { id -> dir.label(id).text },
            photoHash = group.photoShownHash,
            faces = groupFaces(memberIds, myNodeId, dir),
        )
    }

    // Where the peer stands as a contact, by the rule the contacts picker draws (ADR 2026-09.wnh6), and so
    // what Remove would do. Offered for nobody until our own id resolves: until then a thread we wrote in
    // cannot be told from one we did not, and we could be looking at our own profile.
    private val removal: Flow<ContactRemoval> =
        combine(
            observeContactSignals(messages, groups, settings, myNodeId),
            directory,
            settings.blockedNodeIds,
            myNodeId,
        ) { signals, dir, blocked, myId ->
            if (myId == null || myId == nodeId) return@combine ContactRemoval.NotOffered
            removalFor(contactStanding(nodeId, signals, dir.verified, blocked)) { sharedGroup(it, dir, myId) }
        }.distinctUntilChanged()

    /** This device's identity, [inCommon] and [removal] — one source for the main combine's five. */
    private data class Extras(
        val myId: MyIdentity?,
        val shared: InCommon,
        val removal: ContactRemoval,
    )

    // They ride in as one source: the main combine below is at Kotlin's five-flow limit, and widening it
    // would mean the untyped array overload.
    private val extras: Flow<Extras> = combine(me, inCommon, removal, ::Extras)

    val state: StateFlow<ProfileDetailsUiState> =
        combine(
            directory,
            reach,
            settings.blockedNodeIds,
            extras,
            meshManager.introState(nodeId),
        ) { directory, reach, blocked, (myId, shared, removal), intro ->
            val peer = directory.byNode[nodeId]
            val label = directory.label(nodeId)
            peerBundle = peer?.pubKey
            peerDeviceTag = peer?.deviceTag
            val safety =
                if (peer?.pubKey != null && myId != null) {
                    SafetyNumber.compute(myId.nodeId, myId.bundle, nodeId, peer.pubKey)
                } else {
                    null
                }
            ProfileDetailsUiState(
                nodeId = nodeId,
                displayName = label.text,
                alias = label.alias,
                discriminator = label.discriminator,
                status = peer?.status.orEmpty(),
                avatarHash = peer?.avatarHash,
                reach = reach,
                isBlocked = nodeId in blocked,
                hasKey = peer?.pubKey != null,
                verified = peer?.verified == true,
                safetyNumber = safety,
                myQrPayload = myId?.let { VerifyPayload.encode(it.nodeId, it.bundle) },
                intro = intro,
                openToChat = peer?.openToChat == true,
                loraNodeLabel = peer?.loraNode?.let(::meshNodeLabel),
                inCommon = shared,
                removal = removal,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ProfileDetailsUiState(
                nodeId = nodeId,
                displayName = displayNameFor(null, nodeId),
                status = "",
                avatarHash = null,
                reach = Reach.Known,
                isBlocked = false,
            ),
        )

    /**
     * Accepts this peer's DM (idempotent). Tapping Message means we've chosen to converse, so any
     * pending request from them clears — their thread moves into the main chat list and their messages
     * notify normally. A no-op for a peer that's already accepted, so it's safe on every Message tap.
     */
    fun accept() {
        viewModelScope.launch { settings.accept(nodeId) }
    }

    /** Blocks this peer locally: their messages/reactions stop being stored, shown, and notified. */
    fun block() {
        viewModelScope.launch { settings.block(nodeId, peerDeviceTag) }
    }

    /** Unblocks this peer, restoring their (never-deleted) message history. */
    fun unblock() {
        viewModelScope.launch { settings.unblock(nodeId, peerDeviceTag) }
    }

    /**
     * Removes this peer from contacts (`ContactRemover`, ADR 2026-09.adgd): our accept, their verification, the
     * DM thread and a pending intro go; nothing is sent. [removed] fires once the writes are done.
     */
    fun removeContact() {
        viewModelScope.launch {
            remover.remove(nodeId)
            _removed.tryEmit(Unit)
        }
    }

    /** Marks this peer's pinned key as verified out of band (safety numbers matched / QR scanned). */
    fun markVerified() {
        viewModelScope.launch { peers.setVerified(nodeId, true) }
    }

    /** Clears verification (e.g. user wants to re-check). */
    fun clearVerification() {
        viewModelScope.launch { peers.setVerified(nodeId, false) }
    }

    /** Compares a scanned identity QR with the pinned key; marks verified on an exact match. */
    fun onScanned(payload: String) {
        val matches = scannedMatchesPinned(payload)
        if (matches) markVerified()
        _scanResult.value = if (matches) VerifyScanResult.MATCH else VerifyScanResult.MISMATCH
    }

    private fun scannedMatchesPinned(payload: String): Boolean {
        val pinned = peerBundle ?: return false
        // Compare-only, so the legacy code needs no self-certification here: the pinned key was
        // self-certified when it was pinned, and an exact (id, bundle) match is the whole check. A
        // contact-link QR (docs/CONTACT_CARD.md) goes through the codec, which verifies it fully.
        VerifyPayload.parse(payload)?.let { return it.nodeId == nodeId && it.bundle == pinned }
        val card = ContactCard.parse(payload) as? ContactCard.Parsed.Card ?: return false
        return card.nodeId == nodeId && card.bundle == pinned
    }

    fun consumeScanResult() {
        _scanResult.value = null
    }
}
