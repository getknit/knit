package app.getknit.knit.contacts

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.spool.CommonsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * The people this phone hands its presence key to (ADR 2026-11.xp6d, knit-next #285): the ones the user chose, and no
 * one someone else's act can add. After removing [me], [blocked] and [blockedByTag] (every node id whose pinned device
 * tag the user blocked), a direct contact is a DM-kind id in the union of:
 *  - [accepted] less [commonsAccepts]: a card imported, a request accepted, the profile's Message. A commons poster is
 *    accepted on first sighting (`MeshManager.onCommonsMember`) with a mark, and whoever holds the invite grants
 *    commons reach, so a marked accept alone never counts;
 *  - [verified]: the out-of-band key check;
 *  - [authored]: a DM thread the user has written in.
 *
 * Not `contactIds` (ui/contacts/ContactUniverse.kt): that rule also counts members of binding groups, and a group's
 * roster grows by its members' invitations. Pure.
 */
internal fun directContactIds(
    accepted: Set<String>,
    commonsAccepts: Set<String>,
    verified: Set<String>,
    authored: Set<String>,
    blocked: Set<String>,
    blockedByTag: Set<String>,
    me: String,
): Set<String> {
    val chosen = (accepted - commonsAccepts) + verified + authored
    return chosen.filterTo(HashSet()) { Conversations.kindFor(it) == ConversationKind.DM } - blocked - blockedByTag - me
}

/**
 * [directContactIds] over the stored facts: [read] queries every source afresh (the key engine decides on it, never on
 * a cached value), and [observe] emits whenever the set changes (the engine re-checks on it).
 *
 * Both first mark, once per install, the commons posters a build before ADR 2026-11.xp6d accepted without a mark
 * ([SettingsStore.backfillCommonsAccepts]), from [commons]' members, which every build binds whether or not it shows
 * the commons. Until that has run, an unmarked accept would read as the user's own, so nothing is judged before it.
 */
class DirectContacts(
    private val settings: SettingsStore,
    private val peers: PeerRepository,
    private val messages: MessageRepository,
    private val commons: CommonsStore,
    private val identity: IdentitySource,
) {
    private suspend fun ensureBackfilled() {
        if (!settings.commonsAcceptsBackfilled()) settings.backfillCommonsAccepts(commons.allMembers().mapTo(HashSet()) { it.nodeId })
    }

    /** The set as the stores hold it now. */
    suspend fun read(): Set<String> {
        ensureBackfilled()
        val me = identity.nodeId()
        return directContactIds(
            accepted = settings.acceptedConversations.first(),
            commonsAccepts = settings.commonsAccepts.first(),
            verified = peers.verifiedNodeIds().toSet(),
            authored = messages.conversationsIAuthoredIn(me).toSet(),
            blocked = settings.blockedNodeIds.first(),
            blockedByTag = peers.nodeIdsWithDeviceTags(settings.blockedDeviceTags.first()).toSet(),
            me = me,
        )
    }

    /** The set as it changes. A device-tag block is read afresh on each change of the other inputs. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Set<String>> =
        flow {
            ensureBackfilled()
            emit(identity.nodeId())
        }.flatMapLatest { me ->
            val settingsInputs =
                combine(
                    settings.acceptedConversations,
                    settings.commonsAccepts,
                    settings.blockedNodeIds,
                    settings.blockedDeviceTags,
                ) { accepted, marks, blocked, tags -> SettingsInputs(accepted, marks, blocked, tags) }
            combine(
                settingsInputs,
                peers.observeVerifiedNodeIds(),
                messages.observeConversationsIAuthoredIn(me),
            ) { s, verified, authored ->
                directContactIds(
                    accepted = s.accepted,
                    commonsAccepts = s.commonsAccepts,
                    verified = verified.toSet(),
                    authored = authored.toSet(),
                    blocked = s.blocked,
                    blockedByTag = peers.nodeIdsWithDeviceTags(s.blockedTags).toSet(),
                    me = me,
                )
            }
        }.distinctUntilChanged()

    private data class SettingsInputs(
        val accepted: Set<String>,
        val commonsAccepts: Set<String>,
        val blocked: Set<String>,
        val blockedTags: Set<String>,
    )
}
