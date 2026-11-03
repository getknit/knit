package app.getknit.knit.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.ui.theme.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.Locale

/**
 * One published conversation shortcut as [planShortcuts] reads it. [live] is the copy the launcher's long-press
 * menu and the system's conversation surfaces read — dynamic, or cached by the system for a conversation
 * notification — which the app can remove; [pinned] is one the user placed on the home screen, which the app can
 * only disable. [fingerprint] is what it was built from ([shortcutFingerprint]); null on one an older build
 * published.
 */
internal data class ShortcutState(
    val id: String,
    val live: Boolean,
    val pinned: Boolean,
    val enabled: Boolean,
    val fingerprint: String?,
)

/** What one pass does to the published shortcuts, in the order it must be done ([ConversationShortcuts.apply]). */
internal data class ShortcutPlan(
    val remove: List<String> = emptyList(),
    val disable: List<String> = emptyList(),
    val enable: List<String> = emptyList(),
    val update: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = remove.isEmpty() && disable.isEmpty() && enable.isEmpty() && update.isEmpty()
}

/**
 * The pass over the published shortcuts against the conversations the chat list offers now: [offered] maps each
 * offered id to the fingerprint its shortcut should carry, and an id it does not hold is gone (ADR 2026-10.jbsa).
 *
 * A gone conversation's live copy is removed, and a copy pinned to the home screen is disabled, so its tap shows the
 * disabled message rather than open a thread that is no longer there. An offered one is enabled again if it was
 * disabled, and updated when what it was built from has changed — never re-created: a shortcut is only ever born of
 * a notification ([ConversationShortcuts.push]), so nothing a removal took away can come back through a refresh.
 * [skip] holds the ids pushed since the pass read its inputs; the push is newer than anything the pass knows.
 */
internal fun planShortcuts(
    existing: List<ShortcutState>,
    offered: Map<String, String>,
    skip: Set<String> = emptySet(),
): ShortcutPlan {
    val remove = ArrayList<String>()
    val disable = ArrayList<String>()
    val enable = ArrayList<String>()
    val update = ArrayList<String>()
    for (s in existing.filter { it.id !in skip }) {
        val want = offered[s.id]
        if (want == null) {
            if (s.live) remove += s.id
            if (s.pinned && s.enabled) disable += s.id
        } else {
            // The platform will not change `enabled` through an update, so a disabled one is enabled first and then
            // refreshed: it was disabled while the conversation was gone, and its face may have moved meanwhile.
            if (!s.enabled) enable += s.id
            if (!s.enabled || s.fingerprint != want) update += s.id
        }
    }
    return ShortcutPlan(remove, disable, enable, update)
}

/**
 * What a conversation's shortcut is built from, as a short stable digest: its [title] as shown, its kind, the
 * palette the generated avatars are painted from, and the bytes of its photo or of each face in its cluster.
 * Stored in the shortcut's extras, so a pass can tell a stale one from a current one across process deaths without
 * a table of its own. Enum names and byte contents only — nothing whose hash changes from one process to the next.
 */
internal fun shortcutFingerprint(
    title: String,
    conversation: NotifConversation,
    palette: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun field(text: String) {
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    fun bytes(value: ByteArray?) {
        if (value == null) field("-") else digest.update(value).also { digest.update(1) }
    }
    field(FINGERPRINT_VERSION)
    field(conversation.kind.name)
    field(palette)
    field(title)
    bytes(conversation.avatarBytes)
    for ((nodeId, name, avatarBytes) in conversation.faces) {
        field(nodeId)
        field(name)
        bytes(avatarBytes)
    }
    return digest.digest().take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** The published shortcuts at one moment: their [states], and the push stamp ([epoch]) read with them. */
internal class ShortcutSnapshot(
    val epoch: Long,
    val states: List<ShortcutState>,
) {
    val ids: Set<String> get() = states.mapTo(HashSet()) { it.id }
}

/**
 * The platform's shortcut store as [ConversationShortcuts] uses it, behind a seam: Robolectric's shadow makes
 * `removeLongLivedShortcuts` a no-op and never marks a disabled shortcut, so the tests drive a faithful fake instead
 * ([PlatformShortcutStore] is the production one).
 */
internal interface ShortcutStore {
    /** Every shortcut this app published — dynamic, cached and pinned — one entry per id. */
    fun read(): List<ShortcutState>

    fun push(shortcut: ShortcutInfoCompat)

    /** Removes the dynamic and the system-cached copies; a pinned one stays (only the user can unpin it). */
    fun removeLive(ids: List<String>)

    fun disable(
        ids: List<String>,
        message: CharSequence,
    )

    fun enable(shortcuts: List<ShortcutInfoCompat>)

    /** Refreshes shortcuts that exist, never adding one; false when the platform refused (its background rate limit). */
    fun update(shortcuts: List<ShortcutInfoCompat>): Boolean
}

/** The fingerprint [ConversationShortcuts] stored in a shortcut's extras, or null on one an older build published. */
internal fun ShortcutInfoCompat.fingerprint(): String? = extras?.getString(EXTRA_FINGERPRINT)

internal class PlatformShortcutStore(
    private val context: Context,
) : ShortcutStore {
    override fun read(): List<ShortcutState> =
        ShortcutManagerCompat
            .getShortcuts(context, MATCH_ALL)
            .groupBy { it.id }
            // One entry per id. The platform keeps one record per shortcut whatever its flags, but nothing promises a
            // reader that, and two entries for one id would plan it twice.
            .map { (id, copies) ->
                ShortcutState(
                    id = id,
                    live = copies.any { it.isDynamic || it.isCached },
                    pinned = copies.any { it.isPinned },
                    enabled = copies.all { it.isEnabled },
                    fingerprint = copies.firstNotNullOfOrNull { it.fingerprint() },
                )
            }

    override fun push(shortcut: ShortcutInfoCompat) {
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    override fun removeLive(ids: List<String>) = ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)

    override fun disable(
        ids: List<String>,
        message: CharSequence,
    ) = ShortcutManagerCompat.disableShortcuts(context, ids, message)

    override fun enable(shortcuts: List<ShortcutInfoCompat>) = ShortcutManagerCompat.enableShortcuts(context, shortcuts)

    override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean = ShortcutManagerCompat.updateShortcuts(context, shortcuts)
}

/**
 * The one owner of the app's conversation shortcuts (ADR 2026-10.jbsa). A long-lived dynamic shortcut per
 * conversation, keyed by its id, carrying its title, its avatar and a deep link to the thread: a MessagingStyle
 * notification that names it via `setShortcutId` gets Android's conversation treatment (the avatar as the prominent
 * icon in every view, which a bare `setLargeIcon` does not achieve), and the launcher lists it in the app icon's
 * long-press menu. Nothing else surfaces them — the share sheet does not: there is no share target.
 *
 * A shortcut is born only of a notification ([push]); everything after is a pass ([apply]) that keeps the published
 * set inside the chat list's universe and each face current, so a deleted or blocked chat leaves the menu and a
 * contact's new photo reaches it without waiting for their next message. The per-id push stamps let a pass stand
 * aside for a push that landed while it read its inputs.
 */
internal class ConversationShortcuts(
    private val context: Context,
    themePrefs: ThemePreferences,
    private val store: ShortcutStore = PlatformShortcutStore(context),
) {
    /** Every bitmap a notification or a shortcut shows — decoded photos and the generated fallbacks. */
    val avatars = NotificationAvatars(context, themePrefs)

    /** Guards [seq] and [pushedAt], and serializes a pass's platform calls against a push. */
    private val lock = Any()

    private var seq = 0L

    /** The stamp of the latest push per conversation id. */
    private val pushedAt = HashMap<String, Long>()

    private val _published = MutableStateFlow(false)

    /**
     * Whether this app has published a shortcut — seen in a [snapshot] or made by a [push] in this process. Until it
     * has there is nothing for a pass to keep, so `ConversationShortcutSync` does not watch the database for one.
     */
    val published: StateFlow<Boolean> = _published.asStateFlow()

    private val _pushedIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Every conversation this process has pushed a shortcut for — so the sync watches a thread's rows from its first
     * notification, not only from the pass after it. Bounded by the threads that notified in one process's life.
     */
    val pushedIds: StateFlow<Set<String>> = _pushedIds.asStateFlow()

    /** A fresh stamp, for a notification's own state ([MessageNotifier]) to be judged by a pass's [ShortcutSnapshot.epoch]. */
    fun stamp(): Long = synchronized(lock) { ++seq }

    /** The title [kind]'s conversation shows when [title] is missing or blank — the shortcut's label and the avatar's initial. */
    fun titleOf(
        kind: ConversationKind,
        title: String?,
    ): String =
        title?.takeIf { it.isNotBlank() } ?: when (kind) {
            ConversationKind.NEARBY -> context.getString(R.string.notif_title_nearby)
            ConversationKind.MESHTASTIC -> context.getString(R.string.notif_title_meshtastic)
            ConversationKind.COMMONS -> context.getString(R.string.commons_title)
            ConversationKind.GROUP -> context.getString(R.string.group_unnamed)
            ConversationKind.DM -> "?"
        }

    /** [conversation]'s avatar: its photo, else the generated twin of what the chat list draws for it. */
    fun avatarOf(
        conversation: NotifConversation,
        title: String,
    ): Bitmap =
        avatars.bitmapFor(conversation.avatarBytes)
            ?: avatars.fallbackAvatar(conversation.kind, title, key = conversation.conversationId, faces = conversation.faces)

    /**
     * Publishes (or refreshes) [conversation]'s shortcut, drawn as [title] and [avatar] — the notification's own, so
     * the two cannot differ. `pushDynamicShortcut` moves it to the front of the launcher's order, which is what a
     * new message should do; the platform LRU-evicts once over the per-app cap, so this stays bounded.
     */
    fun push(
        conversation: NotifConversation,
        title: String,
        avatar: Bitmap,
    ) {
        val shortcut = build(conversation, title, avatar)
        synchronized(lock) {
            pushedAt[conversation.conversationId] = ++seq
            runCatching { store.push(shortcut) }.onFailure { Log.w(TAG, "shortcut push failed", it) }
        }
        _published.value = true
        _pushedIds.update { it + conversation.conversationId }
    }

    /** Every shortcut this app has published, read with the push stamp a pass judges later pushes by. */
    fun snapshot(): ShortcutSnapshot =
        synchronized(lock) {
            val states =
                runCatching { store.read() }.getOrElse {
                    Log.w(TAG, "shortcut read failed", it)
                    emptyList()
                }
            ShortcutSnapshot(seq, states)
        }.also { if (it.states.isNotEmpty()) _published.value = true }

    /**
     * One pass over [snapshot] against [offered] — every offered conversation among the snapshot's ids, with its face
     * now; an id it does not hold is gone. Returns false when the platform refused the update (rate-limited while the
     * app is in the background): the fingerprints then still read stale, and the next pass tries again.
     */
    fun apply(
        snapshot: ShortcutSnapshot,
        offered: Map<String, NotifConversation>,
    ): Boolean {
        val palette = avatars.paletteKey()
        // Fingerprinted outside the lock: a push is free to land meanwhile — it is judged by its stamp below, not by
        // when it ran.
        val built =
            offered.mapValues { (_, conversation) ->
                val title = titleOf(conversation.kind, conversation.title)
                Built(title, shortcutFingerprint(title, conversation, palette), conversation)
            }
        return synchronized(lock) {
            val skip = pushedAt.filterValues { it > snapshot.epoch }.keys
            val plan = planShortcuts(snapshot.states, built.mapValues { it.value.fingerprint }, skip)
            if (plan.isEmpty) return@synchronized true
            Log.i(
                TAG,
                "shortcuts: remove ${plan.remove.size}, disable ${plan.disable.size}, " +
                    "enable ${plan.enable.size}, update ${plan.update.size}",
            )
            if (plan.remove.isNotEmpty()) runCatching { store.removeLive(plan.remove) }
            if (plan.disable.isNotEmpty()) runCatching { store.disable(plan.disable, context.getString(R.string.chat_gone)) }

            fun shortcuts(ids: List<String>) =
                ids.mapNotNull { id ->
                    built[id]?.let {
                        build(it.conversation, it.title, avatarOf(it.conversation, it.title))
                    }
                }
            if (plan.enable.isNotEmpty()) runCatching { store.enable(shortcuts(plan.enable)) }
            plan.update.isEmpty() || runCatching { store.update(shortcuts(plan.update)) }.getOrDefault(false)
        }
    }

    /**
     * Takes [conversationIds]' shortcuts away at once — the removal paths that know the thread is gone as they act
     * ([MessageNotifier.forgetConversation]). The live copy goes and a pinned one is disabled, as a pass would.
     */
    fun forget(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        synchronized(lock) {
            runCatching { store.removeLive(conversationIds) }
            val pinned =
                runCatching { store.read() }
                    .getOrDefault(emptyList())
                    .filter { it.id in conversationIds && it.pinned && it.enabled }
                    .map { it.id }
            if (pinned.isNotEmpty()) runCatching { store.disable(pinned, context.getString(R.string.chat_gone)) }
        }
    }

    private class Built(
        val title: String,
        val fingerprint: String,
        val conversation: NotifConversation,
    )

    private fun build(
        conversation: NotifConversation,
        title: String,
        avatar: Bitmap,
    ): ShortcutInfoCompat {
        val id = conversation.conversationId
        val icon = IconCompat.createWithAdaptiveBitmap(avatar)
        val person =
            Person
                .Builder()
                .setKey(id)
                .setName(title)
                .setIcon(icon)
                .build()
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/$id")
        val extras =
            PersistableBundle().apply {
                putString(
                    EXTRA_FINGERPRINT,
                    shortcutFingerprint(title, conversation, avatars.paletteKey()),
                )
            }
        return ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(title.ifBlank { context.getString(R.string.app_name) })
            .setLongLived(true)
            .setIcon(icon)
            .setPerson(person)
            .setLocusId(LocusIdCompat(id))
            .setIntent(intent)
            .setExtras(extras)
            .build()
    }

    companion object {
        private const val TAG = "KnitShortcuts"

        /**
         * Removes every shortcut this app published — dynamic, cached and pinned — before the app's data goes (sign
         * out, a restore's relaunch). Static, because both callers act on a process that is about to end, with or
         * without a Koin graph.
         */
        fun wipeAll(context: Context) {
            runCatching {
                val ids = ShortcutManagerCompat.getShortcuts(context, MATCH_ALL).map { it.id }
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
            }
        }
    }
}

private const val EXTRA_FINGERPRINT = "knit.fingerprint"

private const val MATCH_ALL =
    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED

/** Bumped when the fingerprint's inputs change, so every shortcut is rebuilt once. */
private const val FINGERPRINT_VERSION = "1"

/** Sixteen bytes of SHA-256: a digest, not a secret — only a changed face needs to read differently. */
private const val FINGERPRINT_BYTES = 16
