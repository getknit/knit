package app.getknit.knit.data.relay

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.spool.CommonsAdverts
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.mesh.spool.TicketKeyJournal
import kotlinx.coroutines.flow.first

/**
 * The one home for what accepting a relay invite *does* (docs/RELAY_INVITE.md §3): turn the plane on,
 * store the relay, un-park it, join its room, dial now. Both doors — a tapped link on the relays screen
 * and a contact card's "Add" on the Add-contact screen — [preview] the same facts onto the same sheet and
 * [apply] the same sequence, for ADR 063's reason: two copies of "what enabling means" would drift, and
 * the one that drifts is the one that skips the disclosure.
 *
 * Every step is idempotent, so a second tap on the same link converges rather than doubling anything.
 * Consent is re-read at apply time rather than trusted from the preview, and is recorded through
 * [SettingsStore.acceptSpoolConsent] alone — the sheet is the master switch's disclosure raised from a
 * second door, not a second consent.
 *
 * [commons] is null while the commons is dark (`BuildConfig.COMMONS` off): the room half of a link is
 * then ignored without comment, and the relay half still applies. [keys] is null in a build without Knit Anywhere
 * (release: `ANYWHERE_WIRED` off, ADR 2026-10.3sk7), and the issuer and customer key a link carries are ignored the same
 * way; with it, the key is held for the relay's host in the same confirmation, and the ticket refresher trades it.
 */
class RelayInviteApplier(
    private val settings: SettingsStore,
    private val commons: CommonsRepository?,
    private val mesh: MeshController,
    private val keys: TicketKeyJournal? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * What the sheet shows and [apply] acts on. [url] is what will be stored — the invite's, or the entry
     * already in the list when that one is the better credential (see [preview]). A plain class: the
     * secret inside [room] must never reach a log line through a data class's `toString`.
     */
    class Preview(
        val url: String,
        /** `SpoolUrl.host` — the only rendering of the address the sheet ever makes. */
        val host: String,
        /** The URL carries a `?k=` bearer token. */
        val private: Boolean,
        /** The relay (by redacted URL) is already in the list. */
        val alreadyAdded: Boolean,
        /** …and parked; applying turns it back on. */
        val parked: Boolean,
        /** An untokened entry for the same host that the invite's tokened URL will replace. */
        val replaces: String?,
        /** The plane is off; applying turns it on. */
        val planeOff: Boolean,
        /** The disclosure has never been accepted; the sheet carries it and applying records consent. */
        val consentNeeded: Boolean,
        /** The room the link joins, or null when it carries none or this build cannot join one. */
        val room: Room?,
        /** The customer key the link carries, or null when it carries none or this build has no tickets. */
        val ticketKey: TicketKey? = null,
    ) {
        /** Nothing would change: every fact is already true. The sheet still shows, so the tap is answered. */
        val isNoOp: Boolean
            get() =
                alreadyAdded && !parked && !planeOff && replaces == null && (room == null || room.alreadyJoined) &&
                    (ticketKey == null || ticketKey.alreadyHeld)
    }

    /**
     * A customer key the link carries for this relay's host, and the issuer it is traded with (ADR 2026-10.3sk7). A
     * plain class whose [toString] names the issuer only: the key must never reach a log line.
     */
    class TicketKey(
        val host: String,
        val held: HeldKey,
        /** These very key and issuer are already held for this host: applying changes nothing. */
        val alreadyHeld: Boolean,
        /** A different key (or issuer) is held for this host: applying replaces it — an `account rotate-key`. */
        val replacesKey: Boolean,
    ) {
        /** What the sheet names: the issuer's host, never its scheme, never the key. */
        val issuerHost: String get() = IssuerOrigin.host(held.issuer)

        override fun toString(): String = "TicketKey(host=$host, issuer=$issuerHost)"
    }

    class Room(
        val secret: ByteArray,
        val name: String?,
        /** This room (by secret) is already joined at this relay. */
        val alreadyJoined: Boolean,
        /**
         * Another room is joined at this relay, and this one joins beside it: two secrets at one spool are two
         * rooms, never a rotation (spec C-7.4-18). Only the spool can say a room is gone.
         */
        val beside: Boolean,
    )

    /**
     * Resolves [invite] against what this device already has. [live] is what the relay's HELLO advertised, if the
     * relay is connected: the room's name is read from the entry its tag matches when the link carries none.
     *
     * A relay is matched by its **redacted** URL: a card's hint is untokened and an operator's invite is
     * tokened, and by exact string the two would be separate rows for one host, the untokened one refused
     * `4001` forever. A tokened invite therefore replaces an untokened entry; an untokened invite over a
     * tokened entry is "already added" and keeps the stored credential — never a downgrade.
     */
    suspend fun preview(
        invite: RelayInvite.Parsed.Invite,
        live: CommonsAdverts? = null,
    ): Preview {
        val stored = settings.spoolUrls.first()
        val parked = settings.disabledSpoolUrls.first()
        // Matching is by exact string, so the link's URL is folded to the stored form first: a link that
        // spells its scheme `WSS://` names the relay a lowercase row already holds.
        val inviteUrl = SpoolUrl.canonical(invite.url)
        val redacted = SpoolUrl.redact(inviteUrl)
        val sameHost = stored.firstOrNull { SpoolUrl.redact(it) == redacted }
        val inviteTokened = inviteUrl != redacted
        val storedTokened = sameHost != null && sameHost != SpoolUrl.redact(sameHost)
        val url = if (sameHost != null && (storedTokened || !inviteTokened)) sameHost else inviteUrl
        val replaces = sameHost?.takeIf { it != url }
        val room =
            invite.secret?.let { secret ->
                val store = commons ?: return@let null
                val bound = store.roots().filter { it.spoolUrl == url || it.spoolUrl == replaces }
                val joined = bound.any { it.secret.contentEquals(secret) }
                Room(
                    secret = secret,
                    name = invite.name ?: live?.advertForScope(ScopeCrypto.commonsScopeId(secret))?.info?.name,
                    alreadyJoined = joined,
                    beside = !joined && bound.isNotEmpty(),
                )
            }
        val ticketKey = ticketKeyFor(invite, url)
        return Preview(
            url = url,
            host = SpoolUrl.host(url),
            private = url != SpoolUrl.redact(url),
            alreadyAdded = sameHost != null && replaces == null,
            parked = url in parked,
            replaces = replaces,
            planeOff = !settings.spoolEnabled.first(),
            consentNeeded = !settings.spoolConsented.first(),
            room = room,
            ticketKey = ticketKey,
        )
    }

    /**
     * The link's key against the one held for [url]'s host, or null: no key in the link, no key store in this build,
     * or a relay with no host name a ticket could name (an IPv6 literal). An invite without a key never yields one, so
     * applying it never removes a held key — the same rule as an untokened invite over a tokened row.
     */
    private suspend fun ticketKeyFor(
        invite: RelayInvite.Parsed.Invite,
        url: String,
    ): TicketKey? {
        val store = keys ?: return null
        val issuer = invite.issuer ?: return null
        val key = invite.key ?: return null
        val host = SpoolUrl.hostname(url) ?: return null
        val offered = HeldKey(issuer, key)
        val held = store.keys.first()[host]
        return TicketKey(host, offered, alreadyHeld = held == offered, replacesKey = held != null && held != offered)
    }

    /** Applies [preview], in order, each step idempotent; ends by nudging the plane so the relay dials now. */
    suspend fun apply(preview: Preview) {
        when {
            !settings.spoolConsented.first() -> settings.acceptSpoolConsent()
            !settings.spoolEnabled.first() -> settings.setSpoolEnabled(true)
        }
        preview.replaces?.let { old -> replaceEntry(old, preview.url) }
        settings.addSpoolUrl(preview.url)
        if (preview.parked) settings.setSpoolUrlEnabled(preview.url, true)
        val store = commons
        val room = preview.room
        // A room already joined is left alone: `join` upserts the whole row, and re-writing it with the link's
        // (possibly absent) name would erase the one the relay advertised at join time. Any other room joined here
        // stays (C-7.4-18).
        if (store != null && room != null && !room.alreadyJoined) store.join(preview.url, room.secret, room.name, clock())
        val ticketKey = preview.ticketKey
        // `putKey` is a no-op for the same key, so a re-tap never resets the refresher's schedule.
        if (keys != null && ticketKey != null) keys.putKey(ticketKey.host, ticketKey.held)
        mesh.refreshRelays()
    }

    /**
     * Swaps the untokened [old] entry for [new]. A room joined under the old address is re-bound rather
     * than left: the affinity moves with the relay, and the thread and its history stay.
     */
    private suspend fun replaceEntry(
        old: String,
        new: String,
    ) {
        commons?.let { store ->
            for (root in store.roots().filter { it.spoolUrl == old }) {
                store.join(new, root.secret, store.find(root.conversationId)?.name, clock())
            }
        }
        settings.removeSpoolUrl(old)
    }
}
