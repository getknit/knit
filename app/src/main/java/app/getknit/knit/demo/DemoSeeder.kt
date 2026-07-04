package app.getknit.knit.demo

import android.content.Context
import android.util.Log
import app.getknit.knit.BuildConfig
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
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.protocol.Mention
import org.koin.core.Koin
import java.security.MessageDigest

/**
 * Populates the database with a believable conversation history so the app renders fully on an
 * emulator — used only by the demo-screenshot build (`-PseedDemo=true`; the field defaults false, so
 * this never runs in normal/release builds). The concrete content (cast, messages, group) comes from a
 * [DemoScenario] chosen by `-PdemoTheme` (see [demoScenarioFor]), so we can shoot multiple marketing
 * themes from the same code.
 *
 * It writes through the same repositories the app uses, so the reactive flows repopulate every screen
 * with no UI changes. Paired with [app.getknit.knit.mesh.DemoTransport], which reports [ONLINE_NODE_IDS]
 * as connected so the "connected" header and contact "online" dots light up. All writes are idempotent
 * upserts keyed by stable ids, so a relaunch re-seeds deterministically.
 *
 * The recurring `60_000L` is the minutes->millis factor for the fixture offsets, so [MagicNumber] is
 * suppressed for the whole class.
 */
@Suppress("MagicNumber")
class DemoSeeder(
    private val koin: Koin,
) {
    private val peers = koin.get<PeerRepository>()
    private val messages = koin.get<MessageRepository>()
    private val reactions = koin.get<ReactionRepository>()
    private val groups = koin.get<GroupRepository>()
    private val settings = koin.get<SettingsStore>()
    private val blobs = koin.get<BlobRepository>()
    private val context = koin.get<Context>()

    private lateinit var me: String
    private lateinit var scenario: DemoScenario

    suspend fun seed() {
        runCatching { seedInternal() }
            .onFailure { Log.e("DemoSeeder", "demo seeding failed", it) }
    }

    private suspend fun seedInternal() {
        me = koin.get<Identity>().nodeId()
        scenario = demoScenarioFor(BuildConfig.DEMO_THEME)
        val now = System.currentTimeMillis()

        settings.setDisplayName(scenario.meName)
        settings.setStatus(scenario.meStatus)
        avatar("me")?.let { settings.setOwnAvatarHash(it) }

        seedPeers(now)
        seedNearby(now)
        seedDms(now)
        seedGroup(now)
    }

    private suspend fun seedPeers(now: Long) {
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

    private suspend fun seedNearby(now: Long) {
        scenario.nearby.forEach { save(it, Conversations.NEARBY, dmPeer = null, now) }
        // Read up to nearbyReadMinsAgo, leaving the latest message unread -> a "1" badge on Nearby.
        settings.setLastReadAt(Conversations.NEARBY, now - scenario.nearbyReadMinsAgo * 60_000L)
    }

    private suspend fun seedDms(now: Long) {
        scenario.dms.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { save(it, conversationId = peer, dmPeer = peer, now) }
            // A read watermark clears the badge; an unread thread gets none, so the peer's messages count.
            if (thread.read) settings.setLastReadAt(peer, now)
        }
    }

    private suspend fun seedGroup(now: Long) {
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
        scenario.groupMessages.forEach { save(it, conversationId = groupId, dmPeer = null, now) }
        settings.setLastReadAt(groupId, now)
    }

    /**
     * Writes one [DemoMsg] (and any reactions on it). For a DM, [dmPeer] is the other party so the
     * recipient is set per direction; for the room/group it's null. [received] doubles as the delivery
     * tick and is only meaningful for our own outbound messages, so it tracks "is this mine".
     */
    private suspend fun save(
        m: DemoMsg,
        conversationId: String,
        dmPeer: String?,
        now: Long,
    ) {
        val fromMe = m.from == Slot.ME
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
            ),
        )
        m.reactions.forEach { r ->
            reactions.apply(ReactionEntity(m.id, nodeId(r.reactor), r.emoji, now - r.minsAgo * 60_000L))
        }
    }

    /** Resolves a [Slot] to its node id ([Slot.ME] is this device's runtime id). */
    private fun nodeId(slot: Slot): String =
        when (slot) {
            Slot.ME -> me
            Slot.SAM -> SAM
            Slot.DANI -> DANI
            Slot.THEO -> THEO
            Slot.PRIYA -> PRIYA
            Slot.JONAS -> JONAS
            Slot.LENA -> LENA
        }

    /**
     * Loads the bundled demo avatar for [key] (a node id, or "me") from the active theme's asset folder,
     * stores it as a content blob, and returns its hash to pin on the peer/own profile — or null if the
     * asset is missing, so the avatar falls back to a letter circle. Assets live in the debug-only source
     * set, so they never ship.
     */
    private suspend fun avatar(key: String): String? =
        runCatching {
            val bytes = context.assets.open("demo/avatars/${scenario.theme}/$key.jpg").use { it.readBytes() }
            // Content-address the blob (like the real avatar pipeline) so swapping the image swaps the hash
            // too — a fixed key would collide with the prior run's blob (insert is conflict-IGNORE).
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            blobs.insert(hash, "image/jpeg", bytes)
            hash
        }.getOrNull()

    companion object {
        // Stable demo node ids ([a-z0-9], length 8 — see NodeId). Names/avatars/messages vary by theme,
        // but the id slots stay constant so ONLINE_NODE_IDS and the fake transport are theme-independent.
        const val SAM = "samr1v00"
        const val DANI = "danich01"
        const val THEO = "theob123"
        const val PRIYA = "priyan07"
        const val JONAS = "jonasw88"
        const val LENA = "lenaf042"

        /** The subset of demo peers reported as connected by [app.getknit.knit.mesh.DemoTransport]. */
        val ONLINE_NODE_IDS: Set<String> = setOf(SAM, DANI, PRIYA)
    }
}
