package app.getknit.knit.contacts

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.first

/**
 * Turns a parsed [ContactCard] into a contact: previews what importing would do, then pins the key,
 * accepts the conversation and registers the intro (docs/CONTACT_CARD.md). The rules mirror the QR
 * scanner's (`AddContactViewModel.onScanned`): a card is self-certifying, a pinned key is immutable — a
 * differing key for a known node id is refused, never swapped in — and `updatedAt` is left untouched so
 * the peer's own profile frame still wins the last-writer-wins check and fills in what the card lacks.
 *
 * A link is **not** a QR scan: the channel it arrived over is unauthenticated and the name on it is
 * chosen by whoever sent it, so an import pins + accepts but never sets `verified` — the safety number
 * is shown for the pair to compare out of band, exactly as for a peer met over the radios.
 *
 * Relay hints are surfaced, never applied *silently*: adding a relay hands it every scope id and IP this
 * device has, so a hint becomes a relay only through the relay invite sheet (docs/RELAY_INVITE.md) — the
 * host named, the cost stated, the disclosure folded in when it has not been accepted — never through
 * [import].
 */
class ContactImporter(
    private val peers: PeerRepository,
    private val settings: SettingsStore,
    private val identity: Identity,
    private val mesh: MeshController,
    private val internetPlane: Boolean,
) {
    sealed interface Preview {
        /** The card is our own identity. */
        data object Self : Preview

        /** A key is already pinned for this node id and the card carries a different one. */
        data class Mismatch(
            val displayName: String,
        ) : Preview

        data class Invalid(
            val reason: ContactCard.Reason,
        ) : Preview

        data class Ready(
            val nodeId: String,
            val displayName: String,
            /** The alias derived from the key — shown outright, since the card's name is chosen by whoever sent it. */
            val alias: String,
            val safetyNumber: String,
            val alreadyContact: Boolean,
            val blocked: Boolean,
            /** The Internet plane is absent from this build or switched off — the card's relays can't be met at. */
            val relaysOff: Boolean,
            /** Relays the card names that this device does not use (shown, never auto-added). */
            val unknownRelays: List<String>,
            val card: ContactCard.Parsed.Card,
        ) : Preview
    }

    suspend fun preview(parsed: ContactCard.Parsed): Preview {
        val card =
            when (parsed) {
                is ContactCard.Parsed.Invalid -> return Preview.Invalid(parsed.reason)
                is ContactCard.Parsed.Card -> parsed
            }
        if (card.nodeId == identity.nodeId()) return Preview.Self
        val existing = peers.find(card.nodeId)
        val storedName = existing?.name?.ifBlank { null } ?: card.name.ifBlank { null }
        val label = peers.labelIndex().labelFor(card.nodeId, storedName)
        val displayName = label.text
        val pinned = existing?.pubKey
        if (pinned != null && pinned != card.bundle) return Preview.Mismatch(displayName)
        val relaysOn = internetPlane && settings.spoolEnabled.first()
        return Preview.Ready(
            nodeId = card.nodeId,
            displayName = displayName,
            alias = label.alias,
            safetyNumber = SafetyNumber.compute(identity.nodeId(), identity.publicKeyBundle(), card.nodeId, card.bundle),
            alreadyContact = existing?.verified == true || card.nodeId in settings.acceptedConversations.first(),
            blocked = card.nodeId in settings.blockedNodeIds.first(),
            relaysOff = !relaysOn,
            // Listed whether or not the plane is on — the sheet an "Add" raises owns the consent — but never
            // in a build where the plane is dark, where there is no screen to add one to.
            // Folded to the stored form first, so a card naming a renamed relay's former host asks for nothing held.
            unknownRelays =
                if (internetPlane) {
                    card.spools
                        .map(
                            SpoolUrl::canonical,
                        ).distinct() - settings.spoolUrls.first()
                } else {
                    emptyList()
                },
            card = card,
        )
    }

    /**
     * Pins, accepts, and registers the intro. Every step is idempotent, so a re-tap after a crash
     * converges. [unblock] lifts an existing block first (the user chose to add them back).
     */
    suspend fun import(
        ready: Preview.Ready,
        unblock: Boolean = false,
    ) {
        val card = ready.card
        val existing = peers.find(card.nodeId)
        if (ready.blocked && unblock) settings.unblock(card.nodeId, existing?.deviceTag)
        peers.upsert(
            (existing ?: PeerEntity(card.nodeId)).copy(
                pubKey = card.bundle,
                // A stored name (from a real profile) always outranks the card's; the card only fills a blank.
                name = existing?.name?.ifBlank { null } ?: card.name,
                verified = existing?.verified == true,
            ),
        )
        settings.accept(card.nodeId)
        mesh.importContact(card.nodeId)
    }
}
