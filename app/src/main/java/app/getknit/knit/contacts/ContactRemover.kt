package app.getknit.knit.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.contacts.groupsAcceptedOnlyThrough
import app.getknit.knit.ui.contacts.observeContactSignals
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Removes a contact — [ContactImporter]'s inverse, and a local decision only (ADR 2026-09.adgd). A contact is
 * derived, not stored (`ui/contacts/ContactUniverse.kt`), so removing one clears each signal of our own that
 * makes them one: the explicit accept, the out-of-band verification, the DM thread we wrote in (deleted with
 * its draft, its notifications and its conversation shortcut), and a contact-card intro still pending. What
 * remains is a stranger: their next DM lands in Message Requests.
 *
 * Nothing is sent and nothing the mesh converges on moves: the peer row (the key pin that lets this node carry
 * their frames), the ratchet session, custody and the block list are left exactly as they were, so the
 * removal is invisible to them and to every carrier. A group co-member stays a contact for as long as the
 * group does — only their own signed leave shrinks its roster — and removing a person never demotes a group:
 * one whose acceptance rested on this peer alone is accepted outright first.
 */
class ContactRemover(
    private val settings: SettingsStore,
    private val peers: PeerRepository,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val drafts: DraftRepository,
    private val mesh: MeshController,
    private val notifier: Notifier,
    private val identity: Identity,
) {
    /**
     * Removes [nodeId] from contacts. Every step is idempotent and the explicit accept — the signal most often
     * present — is cleared last, so a removal cut short leaves the person a contact, still offered Remove,
     * and a second run finishes it. No transaction: the accepted set is in DataStore and cannot join one,
     * and every state between two steps is one the rules already allow.
     */
    suspend fun remove(nodeId: String) {
        val me = identity.nodeId()
        if (nodeId == me) return
        // Every read before the first write (rules/coding.md): the groups to keep are judged on the
        // signals as they stand now, before this removal takes any of them away.
        val signals = observeContactSignals(messages, groups, settings, flowOf(me)).first()
        val verified = peers.verifiedNodeIds().toSet()
        val keep = groupsAcceptedOnlyThrough(nodeId, signals, verified)
        withContext(NonCancellable) {
            keep.forEach { settings.accept(it) }
            mesh.cancelIntro(nodeId)
            notifier.forgetConversation(nodeId)
            messages.deleteByConversation(nodeId)
            drafts.clear(nodeId)
            peers.setVerified(nodeId, false)
            settings.unaccept(nodeId)
        }
    }
}
