package app.getknit.knit.ui.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.relay.FreeUploadChoice
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.CommonsInvite
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.HeldTicket
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.MoveResult
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketJournal
import app.getknit.knit.mesh.spool.SpoolTicketPolicy
import app.getknit.knit.mesh.spool.SpoolTicketState
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.mesh.spool.StoredSpoolTicket
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One configured relay, resolved for display: the [url] as stored, a redacted [host] for the row, and
 * whatever the live plane knows about it.
 *
 * [scopeCount] and [carriesPhotos] are null while disconnected rather than zero/false, because "we have
 * not spoken to it yet" is a different statement from "it holds nothing" and from "it refuses photos" —
 * and only the connected form of each is worth showing.
 *
 * [enabled] is this relay's own switch, independent of the master one: it says what the user asked for,
 * not what the plane is doing. The two settle apart for up to one `ScopeSync` reconcile tick after a
 * flip, which is why the row renders intent first.
 */
data class RelayRow(
    val url: String,
    val host: String,
    val enabled: Boolean,
    val connected: Boolean,
    val scopeCount: Int? = null,
    val carriesPhotos: Boolean? = null,
    val lastError: String? = null,
    // The commons this relay runs (§7.4), once its HELLO has said so: the room's advertised name and, when
    // this device has joined it, the thread it lives in. Null while disconnected or when the relay runs none.
    val commons: RelayCommons? = null,
    // What this relay offers calls, once its HELLO has said so, and where this phone stands with a ticket there.
    // Null while disconnected, when it offers calls nothing, and in a build without calls.
    val calls: RelayCalls? = null,
    // The ticket key this phone holds for the relay's host and what its issuer last said. Not a fact about the
    // connection, so it shows while disconnected too; null when no key is held, and in a build without calls.
    val account: RelayAccount? = null,
    // Free tickets for this relay (docs/VOICE_CALLS.md C-7.6-34): the free issuer's host and the person's answer, what
    // the relay screen's switch shows. Null where the relay has no free issuer, and in a build without calls.
    val freeUploads: FreeUploadChoice? = null,
    // What the relay's own screen adds, all facts about the live connection and so null while disconnected: the
    // largest attachment it takes in bytes (null too when it takes none, which [carriesPhotos] says), the software its
    // `/source` named (`SpoolSoftware.label`), and the proof-of-work bits its HELLO asks a stamp to carry (0: none).
    val maxAttachBytes: Int? = null,
    val software: String? = null,
    val powBits: Int? = null,
    // Whether the relay takes this phone's own uploads (C-7.6-24), apart from [maxAttachBytes]: a relay that carries
    // photos may still refuse ours without a files ticket. Null while disconnected and where it carries none.
    val uploads: RelayUploads? = null,
)

/**
 * Whether a connected relay that carries attachments takes this phone's (C-7.6-24), as the relay's screen shows it:
 * the connection's `SpoolUploads`, with the largest one it would take when it does.
 */
sealed interface RelayUploads {
    /** Taken, each up to [maxBytes]: the files ticket's `am` where it sets one under the relay's own limit. */
    data class Open(
        val maxBytes: Long,
    ) : RelayUploads

    /** Gated behind a files ticket this phone does not hold here; [pending] while one is on its way, unanswered. */
    data class Locked(
        val pending: Boolean,
    ) : RelayUploads

    /** The ticket's day of uploads here is used up (`err ticket` `budget`). */
    data object Spent : RelayUploads
}

/** A relay's commons as the row shows it: the operator's name for it, and our thread id when joined. */
data class RelayCommons(
    val name: String?,
    val joinedId: String?,
    // The room joined here is not the one this relay runs (`SpoolStatus.staleCommons`): its invite was replaced.
    val stale: Boolean = false,
)

/**
 * What a relay offers calls (experimental, docs/VOICE_CALLS.md §2), as its HELLO said on the live connection:
 * TURN credentials ([turn], §2.1), credential-free STUN servers ([stunServers], C-7.6-20, counted after the client's
 * URL rule), casts for a walkie's bursts ([walkie], §2.7), and — only on a relay that takes tickets (`ent`, §2.6) —
 * where this phone stands with one there ([ticket]; null on a relay that takes none). Whether it takes this phone's
 * uploads is [RelayRow.uploads], which no call kind gates.
 */
data class RelayCalls(
    val turn: Boolean,
    val stunServers: Int,
    val walkie: Boolean,
    val ticket: RelayTicket?,
)

/**
 * Where this phone stands with a relay's ticket, for the relay's screen. The live connection's answer wins; with none there,
 * the stored ticket's verdict says why (a refusal that keeps it from going out again, or an `exp` past).
 */
sealed interface RelayTicket {
    /** No ticket held for this relay's host. */
    data object None : RelayTicket

    /** One is on its way to the relay, or held and about to go. */
    data object Presenting : RelayTicket

    /**
     * The relay accepted a ticket good until [exp] (Unix seconds), granting TURN, casts and uploads as its flags say,
     * with its own day's upload budget [ab] and per-attachment limit [am] in bytes where it sets them.
     */
    data class Live(
        val exp: Long,
        val turn: Boolean,
        val walkie: Boolean,
        val files: Boolean = false,
        val ab: Long? = null,
        val am: Long? = null,
    ) : RelayTicket

    /** The relay refused the ticket with [reason], its `err ticket` word. */
    data class Refused(
        val reason: String,
    ) : RelayTicket

    /** The held ticket ran out at [exp] (Unix seconds) and nothing replaced it. */
    data class Expired(
        val exp: Long,
    ) : RelayTicket
}

/**
 * The ticket key held for a relay's host (ADR 2026-10.3sk7), as the row shows it: the issuer it is traded with (its
 * host, never the key), the issuer's last verdict ([answer], null before the first), the account's end as the issuer
 * last said it ([endsAt], epoch millis), and how many asks in a row got no verdict ([failures]). A [free] key
 * (C-7.6-37) shows no end: its account lives while it is used. Parked [KeyAnswer.BOUND_ELSEWHERE] (C-7.6-42),
 * [movableAt] is when the person can move it here (epoch millis, null for now).
 */
data class RelayAccount(
    val issuer: String,
    val answer: KeyAnswer?,
    val endsAt: Long?,
    val failures: Int,
    val free: Boolean = false,
    val movableAt: Long? = null,
)

data class InternetRelayUiState(
    val enabled: Boolean = false,
    val relays: List<RelayRow> = emptyList(),
    // The platform reports no validated route to the Internet at all (`InternetGate.online`): a fact about
    // the phone, not any relay, which is why it lives here and not on the rows.
    val offline: Boolean = false,
    // False only before the stores' first answer, so a relay's own screen can tell "not read yet" from "removed".
    val loaded: Boolean = false,
)

/** One-shot outcomes for the screen's snackbar and share sheet. */
sealed interface InternetRelayEvent {
    /** A freshly minted relay invite for the share sheet. */
    data class ShareInvite(
        val url: String,
    ) : InternetRelayEvent

    /** A freshly minted relay invite for the clipboard. */
    data class CopyInvite(
        val url: String,
    ) : InternetRelayEvent

    /** Text that arrived as an invite but does not parse as one this build can use. */
    data class InviteRefused(
        val reason: RelayInvite.Reason,
    ) : InternetRelayEvent

    /** The invite was applied; [host] is the relay's redacted host for the confirmation. */
    data class InviteApplied(
        val host: String,
    ) : InternetRelayEvent

    /** What the person's move of a relay's Knit Anywhere came to (docs/VOICE_CALLS.md C-7.6-44). */
    data class Moved(
        val result: MoveResult,
    ) : InternetRelayEvent
}

/**
 * The Internet relays screen: the master switch, the relay list editor with a switch per relay, and each
 * relay's live health.
 *
 * The switch does **not** write straight through on the way up — [onToggle] raises [showConsent] the
 * first time, and only [acceptConsent] turns the plane on. Turning it *off* is immediate and
 * unconditional: a user reaching for that switch wants it off now, and interposing a confirmation
 * would be a dark pattern on the one control that stops data leaving the device.
 */
class InternetRelayViewModel(
    private val settings: SettingsStore,
    relayStatus: RelayStatusRepository,
    // The joined commons (§7.4) and the plane to nudge when one is joined or left. Nullable and last so the
    // screen tests' two-argument rig keeps compiling; production passes a store only while
    // `BuildConfig.COMMONS` is on, and without one the row shows no room and the verbs are no-ops.
    private val commons: CommonsRepository? = null,
    private val mesh: MeshController? = null,
    // The relay invite's two halves (docs/RELAY_INVITE.md): the link handoff a tapped `getknit.app/r` link
    // lands in, and the one apply sequence the sheet confirms. Observed rather than read once, so a second
    // link arriving while the screen is up (`launchSingleTop`) still raises the sheet.
    private val inbox: RelayInviteInbox? = null,
    private val applier: RelayInviteApplier? = null,
    // The phone's own route to the Internet, for the one line that is not about a relay. Null (the
    // screen tests' rig) reads as online, so every row keeps saying what the relay said.
    gate: InternetGate? = null,
    // Where a relay's customer key, its ask state and its ticket live (ADR 2026-10.3sk7), so removing the relay
    // forgets them (C-7.6-29). Null in the screen tests' rig, which removes the row alone.
    private val ticketKeys: TicketKeyJournal? = null,
    // Where a relay's held ticket and its verdict live (`SettingsStore`), and the one seam for a relay's call details:
    // production passes it only where calls or Knit Anywhere are wired (`CALLS_WIRED`, `ANYWHERE_WIRED`), and without it
    // no row shows any, nor the key.
    private val spoolTickets: SpoolTicketJournal? = null,
    // Free tickets' switch (C-7.6-34), only where Knit Anywhere is wired; without it no row offers one.
    private val freeUploads: FreeUploads? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val state: StateFlow<InternetRelayUiState> =
        combine(rows(relayStatus), gate?.online ?: MutableStateFlow(true)) { rows, online -> rows.copy(offline = !online) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InternetRelayUiState())

    /** The stored tickets and keys a relay's call details read, or null in a build without calls. */
    private class CallStores(
        val tickets: Map<String, StoredSpoolTicket>,
        val keys: Map<String, HeldKey>,
        val keyStates: Map<String, KeyState>,
        val free: Map<String, FreeUploadChoice>,
    )

    private fun callStores(): Flow<CallStores?> {
        val tickets = spoolTickets ?: return flowOf(null)
        return combine(
            tickets.spoolTickets,
            ticketKeys?.keys ?: flowOf(emptyMap()),
            ticketKeys?.keyStates ?: flowOf(emptyMap()),
            freeUploads?.choices ?: flowOf(emptyMap()),
            ::CallStores,
        )
    }

    private fun rows(relayStatus: RelayStatusRepository): Flow<InternetRelayUiState> =
        combine(
            settings.spoolEnabled,
            combine(settings.spoolUrls, settings.disabledSpoolUrls, ::Pair),
            relayStatus.statuses,
            commons?.observeAll() ?: flowOf(emptyList()),
            callStores(),
        ) { enabled, (urls, parked), statuses, rooms, stores ->
            val byUrl = statuses.associateBy { it.url }
            val joinedByUrl = rooms.associateBy { it.spoolUrl }
            InternetRelayUiState(
                enabled = enabled,
                loaded = true,
                relays =
                    urls.sorted().map { url ->
                        val live = byUrl[url]?.takeIf { it.connected }
                        RelayRow(
                            url = url,
                            host = SpoolUrl.host(url),
                            enabled = url !in parked,
                            connected = live != null,
                            scopeCount = live?.scopes?.count { !it.retiring },
                            carriesPhotos = live?.let { it.maxAttachBytes != null },
                            lastError = byUrl[url]?.lastError,
                            // Drawn only when this build can join one: a Join that could not join is worse
                            // than no line, so a store-less build (`BuildConfig.COMMONS` off) shows no room.
                            commons =
                                if (commons != null) {
                                    live?.commons?.let {
                                        val joined = joinedByUrl[url]?.conversationId
                                        RelayCommons(
                                            name = it.name,
                                            joinedId = joined,
                                            stale =
                                                joined != null && joined in live.staleCommons,
                                        )
                                    }
                                } else {
                                    null
                                },
                            calls = stores?.let { live?.let { relayCalls(it, SpoolUrl.hostname(url)?.let(stores.tickets::get), clock()) } },
                            account = stores?.let { relayAccount(SpoolUrl.hostname(url), stores.keys, stores.keyStates) },
                            freeUploads = stores?.let { SpoolUrl.hostname(url)?.let(stores.free::get) },
                            maxAttachBytes = live?.maxAttachBytes,
                            software = live?.software?.label,
                            powBits = live?.powBits,
                            uploads = live?.let(::relayUploads),
                        )
                    },
            )
        }

    private val _showConsent = MutableStateFlow(false)

    /** True while the first-enable disclosure sheet should be on screen. */
    val showConsent: StateFlow<Boolean> = _showConsent.asStateFlow()

    private val _invitePreview = MutableStateFlow<RelayInviteApplier.Preview?>(null)

    /** The invite awaiting confirmation on the sheet, or null while none is. */
    val invitePreview: StateFlow<RelayInviteApplier.Preview?> = _invitePreview.asStateFlow()

    private val _events = MutableSharedFlow<InternetRelayEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<InternetRelayEvent> = _events.asSharedFlow()

    init {
        if (inbox != null) {
            viewModelScope.launch {
                inbox.pending.collect { text ->
                    if (text == null) return@collect
                    inbox.consume()
                    previewInvite(text)
                }
            }
        }
    }

    /**
     * Parses [text] as a relay invite and raises the sheet for it, or reports why it cannot be one. The
     * scheme policy is the editor's own (`BuildConfig.DEBUG` allows `ws://`), so a link is refused at entry
     * exactly where a typed URL would be.
     */
    fun previewInvite(text: String) {
        val applier = applier ?: return
        when (val parsed = RelayInvite.parse(text, BuildConfig.DEBUG)) {
            is RelayInvite.Parsed.Invalid -> {
                _events.tryEmit(InternetRelayEvent.InviteRefused(parsed.reason))
            }

            is RelayInvite.Parsed.Invite -> {
                viewModelScope.launch {
                    val liveName =
                        state.value.relays
                            .firstOrNull { it.url == parsed.url }
                            ?.commons
                            ?.name
                    _invitePreview.value = applier.preview(parsed, liveName)
                }
            }
        }
    }

    /** The sheet's confirmation: applies the previewed invite and drops the sheet. */
    fun confirmInvite() {
        val applier = applier ?: return
        val preview = _invitePreview.value ?: return
        viewModelScope.launch {
            applier.apply(preview)
            _invitePreview.value = null
            _events.tryEmit(InternetRelayEvent.InviteApplied(preview.host))
        }
    }

    /** The sheet dismissed: nothing is stored, and the link is gone — the user can tap it again. */
    fun dismissInvite() {
        _invitePreview.value = null
    }

    /**
     * Mints the invite for [url] as stored — token included, that is the point — plus the room this device
     * has joined there, if any (docs/RELAY_INVITE.md §4), for the share sheet.
     */
    fun shareInvite(url: String) {
        viewModelScope.launch { _events.tryEmit(InternetRelayEvent.ShareInvite(mintInvite(url))) }
    }

    /** As [shareInvite], for the clipboard. */
    fun copyInvite(url: String) {
        viewModelScope.launch { _events.tryEmit(InternetRelayEvent.CopyInvite(mintInvite(url))) }
    }

    private suspend fun mintInvite(url: String): String {
        val room = commons?.observeAll()?.first()?.firstOrNull { it.spoolUrl == url }
        return RelayInvite.url(RelayInvite.mint(url, room?.secret, room?.name))
    }

    fun onToggle(on: Boolean) {
        viewModelScope.launch {
            when {
                !on -> settings.setSpoolEnabled(false)
                settings.spoolConsented.first() -> settings.setSpoolEnabled(true)
                else -> _showConsent.value = true
            }
        }
    }

    /** Consent given in the sheet: records it and enables the plane in one write. */
    fun acceptConsent() {
        viewModelScope.launch {
            settings.acceptSpoolConsent()
            _showConsent.value = false
        }
    }

    /** Sheet dismissed without accepting — the plane stays off and the disclosure will be shown again. */
    fun dismissConsent() {
        _showConsent.value = false
    }

    /**
     * Whether [url] can be stored. Deliberately the dialer's own rule ([SpoolUrl.isAcceptable]) rather
     * than a second scheme check, so the editor refuses at entry exactly what the dialer would refuse at
     * dial time — otherwise a release user could save a `ws://` relay that then silently never connects.
     */
    fun isValidUrl(url: String): Boolean = SpoolUrl.isAcceptable(url.trim(), BuildConfig.DEBUG)

    fun addRelay(url: String) {
        val trimmed = url.trim()
        if (!isValidUrl(trimmed)) return
        viewModelScope.launch {
            // Stored in the one canonical form, so a hand-typed `WSS://` row and the same host arriving
            // later as an invite link are one relay rather than two.
            settings.addSpoolUrl(SpoolUrl.canonical(trimmed))
            // Dial now rather than at the next reconcile tick, so the row the user just added goes green
            // while they are still looking at it.
            mesh?.refreshRelays()
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch {
            settings.removeSpoolUrl(url)
            // The key, its schedule and the held ticket go with the relay — unless another row still names the same
            // host (two ports, one audience), which keeps using them.
            val host = SpoolUrl.hostname(url)
            if (host != null && settings.spoolUrls.first().none { SpoolUrl.hostname(it) == host }) ticketKeys?.forgetHost(host)
            // A room without its relay is nothing: the row, its key and its history go with the relay.
            commons?.leaveBoundTo(url)
            mesh?.refreshRelays()
        }
    }

    /** Whether [invite] is a commons invite (`knit-commons:v1:…`) — the join field's validator. */
    fun isValidInvite(invite: String): Boolean = CommonsInvite.looksLikeInvite(invite)

    /**
     * Joins the commons [invite] unlocks at [url], titled with what the relay advertised. Idempotent on the
     * secret. The plane is nudged so the room is subscribed now, not at the next reconcile tick.
     */
    fun joinCommons(
        url: String,
        invite: String,
    ) {
        val secret = CommonsInvite.decode(invite) ?: return
        val store = commons ?: return
        viewModelScope.launch {
            val name =
                state.value.relays
                    .firstOrNull { it.url == url }
                    ?.commons
                    ?.name
            store.join(url, secret, name, System.currentTimeMillis())
            mesh?.refreshRelays()
        }
    }

    /** Leaves the commons [conversationId]: the room, its key and its history go; its members stay contacts. */
    fun leaveCommons(conversationId: String) {
        val store = commons ?: return
        viewModelScope.launch {
            store.leave(conversationId)
            mesh?.refreshRelays()
        }
    }

    /**
     * Parks or un-parks one relay. No consent interlock, unlike [onToggle]: the disclosure is about the
     * plane existing at all, and this switch can only ever narrow what an already-consented plane sends —
     * turning a relay on when the master switch is off still opens no socket.
     */
    fun setRelayEnabled(
        url: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch { settings.setSpoolUrlEnabled(url, enabled) }
    }

    /**
     * Moves the Knit Anywhere account held for [url]'s relay to this phone (docs/VOICE_CALLS.md C-7.6-44): only from the
     * person's confirmed tap, once, and the outcome is the snackbar's. Without a mesh nothing moves.
     */
    fun moveHere(url: String) {
        val host = SpoolUrl.hostname(url) ?: return
        viewModelScope.launch {
            val result = mesh?.moveAnywhereHere(host) ?: MoveResult.Unavailable
            _events.tryEmit(InternetRelayEvent.Moved(result))
        }
    }

    /**
     * The relay row's free-tickets switch for [host] (C-7.6-34): on lets this phone make a free account at the relay's
     * free issuer when an upload waits; off forgets a free key held there. Written past the screen's life.
     */
    fun setFreeUploads(
        host: String,
        allowed: Boolean,
    ) {
        val uploads = freeUploads ?: return
        viewModelScope.launch { withContext(NonCancellable) { uploads.answer(host, allowed) } }
    }
}

/**
 * What [status] — a connected relay — offers calls, or null when its HELLO offered none of TURN, STUN, casts or
 * tickets. [stored] is the ticket held for its host, which explains a connection with nothing presented ([now] in
 * epoch millis tells an `exp` past).
 */
internal fun relayCalls(
    status: SpoolStatus,
    stored: StoredSpoolTicket?,
    now: Long,
): RelayCalls? {
    val offersNothing = !status.turn && status.stun.isEmpty() && status.cast == null && !status.ent
    if (offersNothing) return null
    return RelayCalls(
        turn = status.turn,
        stunServers = status.stun.size,
        walkie = status.cast != null,
        ticket = if (status.ent) relayTicket(status.ticket, stored, now) else null,
    )
}

/**
 * Whether [status] — a connected relay — takes this phone's uploads, or null when it carries no attachments at all. An
 * open relay's limit is its own, or the files ticket's `am` (C-7.6-32) where that is smaller.
 */
internal fun relayUploads(status: SpoolStatus): RelayUploads? {
    val relayMax = status.maxAttachBytes?.toLong() ?: return null
    return when (val uploads = status.uploads) {
        SpoolUploads.Open -> {
            val ticketMax = (status.ticket as? SpoolTicketState.Accepted)?.am
            RelayUploads.Open(ticketMax?.coerceAtMost(relayMax) ?: relayMax)
        }

        is SpoolUploads.Locked -> {
            RelayUploads.Locked(pending = uploads.reason == SpoolUploads.PENDING)
        }

        SpoolUploads.Spent -> {
            RelayUploads.Spent
        }
    }
}

/** The row's ticket line: the connection's own answer first, then what the store's verdict says about the held one. */
internal fun relayTicket(
    live: SpoolTicketState,
    stored: StoredSpoolTicket?,
    now: Long,
): RelayTicket =
    when (live) {
        is SpoolTicketState.Accepted -> {
            RelayTicket.Live(
                live.exp,
                turn = live.fl and SpoolTicket.FL_TURN != 0L,
                walkie = live.fl and SpoolTicket.FL_CAST != 0L,
                files = live.fl and SpoolTicket.FL_FILES != 0L,
                ab = live.ab,
                am = live.am,
            )
        }

        SpoolTicketState.Pending -> {
            RelayTicket.Presenting
        }

        is SpoolTicketState.Refused -> {
            RelayTicket.Refused(live.reason)
        }

        SpoolTicketState.None -> {
            val held = stored?.let { HeldTicket.parse(it.text) }
            val verdict = stored?.verdict
            when {
                held == null -> RelayTicket.None
                verdict != null && !SpoolTicketPolicy.presentable(verdict) -> RelayTicket.Refused(verdict)
                now / MS_PER_S > held.claims.exp -> RelayTicket.Expired(held.claims.exp)
                else -> RelayTicket.Presenting
            }
        }
    }

/** The key held for [host] as the row shows it, or null when none is. */
internal fun relayAccount(
    host: String?,
    keys: Map<String, HeldKey>,
    states: Map<String, KeyState>,
): RelayAccount? {
    val key = host?.let(keys::get) ?: return null
    val state = states[host] ?: KeyState.FRESH
    return RelayAccount(IssuerOrigin.host(key.issuer), state.answer, state.endsAt, state.failures, key.free, state.movableAt)
}

private const val MS_PER_S = 1_000L
