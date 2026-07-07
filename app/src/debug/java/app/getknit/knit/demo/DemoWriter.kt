@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import android.content.Context
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import org.koin.core.Koin
import java.security.MessageDigest

/**
 * Shared write primitives for the demo builds: they persist a [DemoScenario]'s content through the same
 * repositories the real app uses, so the reactive flows repopulate every screen with no UI changes. Both
 * the static screenshot seeder ([DemoSeeder]) and the animated trailer ([DemoDirector]) drive these — the
 * seeder writes the whole history up front; the director writes it beat-by-beat with `now` timestamps so it
 * animates in live. All writes are idempotent upserts keyed by stable ids.
 *
 * The recurring `60_000L` is the minutes->millis factor for the fixture offsets, so [MagicNumber] is
 * suppressed for the whole file.
 */
class DemoWriter(
    koin: Koin,
    private val scenario: DemoScenario,
    private val me: String,
    // Every message in the scenario, keyed by id — so a reply can resolve the message it quotes (for the
    // denormalized author/snippet, mirroring what a real inbound reply carries).
    private val msgById: Map<String, DemoMsg>,
) {
    private val peers = koin.get<PeerRepository>()
    private val messages = koin.get<MessageRepository>()
    private val reactions = koin.get<ReactionRepository>()
    private val groups = koin.get<GroupRepository>()
    private val settings = koin.get<SettingsStore>()
    private val blobs = koin.get<BlobRepository>()
    private val context = koin.get<Context>()

    /**
     * Sets the local profile (name/status/avatar), pins self as a peer row, and upserts the scenario cast.
     * The self peer row feeds the self-referential UI (the group details "You" row) that resolves names
     * against the peer table; self is filtered out of the contact/diagnostics lists, so it's harmless there.
     */
    suspend fun seedProfileAndPeers(now: Long) {
        val myAvatar = avatar("me")
        settings.setDisplayName(scenario.meName)
        settings.setStatus(scenario.meStatus)
        myAvatar?.let { settings.setOwnAvatarHash(it) }
        peers.upsert(
            PeerEntity(nodeId = me, name = scenario.meName, status = scenario.meStatus, avatarHash = myAvatar, updatedAt = now),
        )
        scenario.peers.forEach { p ->
            peers.upsert(
                PeerEntity(
                    nodeId = nodeId(p.slot),
                    name = p.name,
                    status = p.status,
                    avatarHash = avatar(nodeId(p.slot)),
                    // A verified peer needs a pinned key + the out-of-band-confirmed flag for the badge.
                    pubKey = if (p.verified) "demo" else null,
                    verified = p.verified,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Writes the full Nearby history + a read watermark leaving the latest message unread (a "1" badge). */
    suspend fun seedNearby(now: Long) {
        scenario.nearby.forEach { write(it, Conversations.NEARBY, dmPeer = null, now) }
        settings.setLastReadAt(Conversations.NEARBY, now - scenario.nearbyReadMinsAgo * 60_000L)
    }

    /** Writes each DM thread; a read thread gets a watermark (no badge), an unread one leaves a count. */
    suspend fun seedDms(now: Long) {
        scenario.dms.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { write(it, conversationId = peer, dmPeer = peer, now) }
            if (thread.read) settings.setLastReadAt(peer, now)
        }
    }

    /** Upserts the scenario group and writes its history; returns the group id. */
    suspend fun seedGroup(now: Long): String {
        val members = scenario.groupMembers.map { nodeId(it) }
        val groupId = Conversations.groupIdFor(members)
        val opener = scenario.groupMessages.first() // lists are oldest-first -> first = creation
        groups.upsert(
            GroupEntity(
                groupId = groupId,
                name = scenario.groupName,
                members = GroupMembersStore.encode(members),
                createdBy = nodeId(opener.from),
                createdAt = now - opener.minsAgo * 60_000L,
                nameUpdatedAt = now - opener.minsAgo * 60_000L,
            ),
        )
        scenario.groupMessages.forEach { write(it, conversationId = groupId, dmPeer = null, now) }
        settings.setLastReadAt(groupId, now)
        return groupId
    }

    /**
     * Writes one [DemoMsg] (message row + any inline reactions). For a DM, [dmPeer] is the other party so the
     * recipient is set per direction; for the room/group it's null. [received] doubles as the delivery tick and
     * is only meaningful for our own outbound messages, so it tracks "is this mine". A [DemoMsg.image] is
     * ingested as a plaintext blob (JPEG scene photo or animated WebP) and pinned via [MessageEntity.attachmentHash].
     */
    suspend fun write(
        m: DemoMsg,
        conversationId: String,
        dmPeer: String?,
        now: Long,
    ) {
        val fromMe = m.from == Slot.ME
        val imageHash = m.image?.let { imageBlob(it, m.imageMime) }
        messages.save(
            MessageEntity(
                id = m.id,
                senderId = nodeId(m.from),
                recipientId = dmPeer?.let { if (fromMe) it else me },
                conversationId = conversationId,
                body = m.body,
                sentAt = now - m.minsAgo * 60_000L,
                received = fromMe,
                mentions =
                    MentionStore.encode(
                        if (m.mentionsMe) listOf(Mention(me, scenario.meName)) else emptyList(),
                    ),
                attachmentHash = imageHash,
                // Plaintext blob (attachmentKey stays null) → BlobFetcher decodes the bytes directly.
                attachmentMime = if (imageHash != null) m.imageMime else null,
            ).withReply(replyRefFor(m)),
        )
        m.reactions.forEach { r ->
            reactions.apply(ReactionEntity(m.id, nodeId(r.reactor), r.emoji, now - r.minsAgo * 60_000L))
        }
    }

    /** Applies a single reaction now — used by the director to pop a reaction onto an already-written message. */
    suspend fun react(
        messageId: String,
        reactor: Slot,
        emoji: String,
        ts: Long,
    ) = reactions.apply(ReactionEntity(messageId, nodeId(reactor), emoji, ts))

    /** Resolves a [Slot] to its node id ([Slot.ME] is this device's runtime id). */
    fun nodeId(slot: Slot): String =
        when (slot) {
            Slot.ME -> me
            Slot.SAM -> DemoSeeder.SAM
            Slot.DANI -> DemoSeeder.DANI
            Slot.THEO -> DemoSeeder.THEO
            Slot.PRIYA -> DemoSeeder.PRIYA
            Slot.JONAS -> DemoSeeder.JONAS
            Slot.LENA -> DemoSeeder.LENA
        }

    /** The display name of a [Slot]: the local profile name for [Slot.ME], else the peer's scenario name. */
    private fun displayName(slot: Slot): String =
        if (slot == Slot.ME) scenario.meName else scenario.peers.firstOrNull { it.slot == slot }?.name ?: nodeId(slot)

    /**
     * The denormalized [ReplyRef] for [m] when it quotes another scenario message (by [DemoMsg.replyTo] → the
     * quoted [DemoMsg.id]), else null. The snippet/author are copied onto the row exactly like a real inbound
     * reply, so the quote renders even though the demo never ran the mesh.
     */
    private fun replyRefFor(m: DemoMsg): ReplyRef? =
        m.replyTo?.let { refId ->
            msgById[refId]?.let { ref ->
                ReplyRef(
                    messageId = ref.id,
                    authorId = nodeId(ref.from),
                    author = displayName(ref.from),
                    snippet = ref.body.take(120),
                    hasAttachment = ref.image != null,
                )
            }
        }

    /**
     * Loads the bundled demo avatar for [key] (a node id, or "me") from the active theme's asset folder,
     * stores it as a content blob, and returns its hash — or null if the asset is missing, so the avatar
     * falls back to a letter circle. Content-addressed like the real avatar pipeline.
     */
    private suspend fun avatar(key: String): String? =
        runCatching {
            val bytes = context.assets.open("demo/avatars/${scenario.theme}/$key.jpg").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, "image/jpeg", bytes)
            hash
        }.getOrNull()

    /**
     * Loads bundled demo image [name] (base name of `demo/images/<theme>/<name>.<ext>`, ext derived from
     * [mime] — `.webp` for an animated WebP, else `.jpg`), stores it as a plaintext content blob, and returns
     * its hash to pin on a message's attachment — or null if the asset is missing (the message then renders
     * text-only, exactly as before).
     */
    private suspend fun imageBlob(
        name: String,
        mime: String,
    ): String? =
        runCatching {
            val ext = if (mime == "image/webp") "webp" else "jpg"
            val bytes = context.assets.open("demo/images/${scenario.theme}/$name.$ext").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, mime, bytes)
            hash
        }.getOrNull()
}
