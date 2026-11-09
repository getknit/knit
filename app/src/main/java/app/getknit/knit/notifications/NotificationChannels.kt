package app.getknit.knit.notifications

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.annotation.RawRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind

/**
 * Single owner of every notification channel + group in the app. Channels are split by context so
 * the user can tune (or silence) each independently from system settings: a "Messages" group with a
 * channel per conversation kind (Nearby / Group messages / Direct messages / Mentions), and an
 * "App" group for the ongoing mesh-service notification plus connection/error alerts.
 *
 * Importance is fixed at creation and can't be raised programmatically afterwards, so the message
 * channels use fresh ids and the flat legacy channels ([LEGACY_MESSAGES] / [LEGACY_MENTIONS]) are
 * deleted in [ensure] rather than reused. Consolidating creation here replaces the two former
 * creation sites (MeshService's raw `NotificationChannel` and MessageNotifier's Compat builders).
 */
object NotificationChannels {
    // Messages group
    const val NEARBY = "knit_msg_nearby"

    // Bumped to _v2 to raise importance to HIGH: a channel's importance is immutable once created, so the
    // DEFAULT-importance `knit_msg_groups` is deleted (see [ensure]) and recreated under a fresh id.
    const val GROUPS = "knit_msg_groups_v2"
    const val DMS = "knit_msg_dms"

    // Bumped to _v2 for the mention sound (ADR 2026-10.xukf): a channel's sound is fixed once it exists.
    const val MENTIONS = "knit_msg_mentions_v2"

    // The Meshtastic room — the paired radio's primary channel. LOW (silent) rather than Nearby's DEFAULT,
    // because it is the one room whose volume nobody here controls: it carries a whole neighbourhood's chat
    // plus whatever bots and MQTT uplinks are on it. Its own channel, so muting that firehose costs nothing else.
    const val MESHTASTIC = "knit_msg_meshtastic"

    // A commons — a relay's private room for a household or team. DEFAULT like Nearby: its authors are
    // pinned peers the user chose to sit in a room with, but it is a room, so a post is not a DM's heads-up.
    const val COMMONS = "knit_msg_commons"

    // Coalesced "message request received" heads-up for a stranger's first (unaccepted) DM/group.
    // Bumped to _v2 to raise importance to HIGH so it pops up (even while the app is foregrounded): a
    // channel's importance is immutable once created, so the LOW-importance `knit_msg_requests` is
    // deleted (see [ensure]) and recreated under a fresh id.
    const val REQUESTS = "knit_msg_requests_v2"

    // App group
    const val STATUS = "knit_mesh" // ongoing foreground notification; id kept stable
    const val ALERTS = "knit_alerts"

    // The "someone nearby is open to chat" cue (presence/OpenToChatWatch). Its own channel rather than
    // [ALERTS] so the user can mute the nudge without muting connection alerts — and because [ALERTS] is LOW
    // (silent) and a channel's importance is immutable once created. Bumped to _v2 for its own sound (ADR
    // 2026-10.xukf).
    const val OPEN_TO_CHAT = "knit_open_to_chat_v2"

    private const val GROUP_MESSAGES = "knit_grp_messages"
    private const val GROUP_APP = "knit_grp_app"

    private const val LEGACY_MESSAGES = "knit_messages"
    private const val LEGACY_MENTIONS = "knit_mentions"

    // The pre-bump DEFAULT-importance Groups channel; replaced by [GROUPS] (_v2) at HIGH importance.
    private const val LEGACY_GROUPS = "knit_msg_groups"

    // The pre-bump LOW-importance Requests channel; replaced by [REQUESTS] (_v2) at HIGH importance.
    private const val LEGACY_REQUESTS = "knit_msg_requests"

    // The channels made before mentions and the open-to-chat cue had sounds of their own; replaced by the _v2s,
    // which carry over what the user set on the old ones ([carryFrom]).
    private const val SOUNDLESS_MENTIONS = "knit_msg_mentions"
    private const val SOUNDLESS_OPEN_TO_CHAT = "knit_open_to_chat"

    private val soundAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** Knit's own sound, which every channel in the Messages group but [MENTIONS] plays (ADR 2026-10.xukf). */
    fun messageSound(context: Context): Uri = rawSound(context, R.raw.knit)

    /** The [MENTIONS] channel's sound: the message's K with a high pip after it. */
    fun mentionSound(context: Context): Uri = rawSound(context, R.raw.knit_mention)

    /** The [OPEN_TO_CHAT] channel's sound: a soft rising arpeggio, plainly not a message. */
    fun openToChatSound(context: Context): Uri = rawSound(context, R.raw.knit_open_to_chat)

    /**
     * A channel sound for one of Knit's own `res/raw` sounds (ADR 2026-10.xukf). The URI names the resource
     * (`android.resource://<package>/raw/<name>`), never its id: a channel keeps the URI it was created with for
     * good, and an id can move between builds. So a sound file a channel names must never be renamed or removed;
     * new bytes under the same name change the sound on every channel already made, without a channel bump.
     * A channel's sound is fixed once it exists, so a phone whose channel predates it keeps the system default.
     */
    fun rawSound(
        context: Context,
        @RawRes sound: Int,
    ): Uri =
        Uri
            .Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(sound))
            .appendPath(context.resources.getResourceEntryName(sound))
            .build()

    /** The message channel for a conversation [kind]. (Mentions route to [MENTIONS] separately.) */
    fun channelFor(kind: ConversationKind): String =
        when (kind) {
            ConversationKind.NEARBY -> NEARBY
            ConversationKind.GROUP -> GROUPS
            ConversationKind.DM -> DMS
            ConversationKind.MESHTASTIC -> MESHTASTIC
            ConversationKind.COMMONS -> COMMONS
        }

    /**
     * Creates the channel groups + channels and removes the legacy flat channels. Idempotent.
     *
     * `@Suppress("LongMethod")`: ktlint's builder-chain wrapping inflates the raw line count past
     * detekt's LongMethod=60; this is a flat sequence of createNotificationChannel(Group) calls.
     */
    @Suppress("LongMethod")
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        // Read before anything below deletes them: a bumped channel starts from its defaults, so a user who had
        // silenced, blocked or quietened the old one would otherwise hear it again.
        val oldMentions = manager.getNotificationChannelCompat(SOUNDLESS_MENTIONS)
        val oldOpenToChat = manager.getNotificationChannelCompat(SOUNDLESS_OPEN_TO_CHAT)

        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat
                .Builder(GROUP_MESSAGES)
                .setName(context.getString(R.string.notif_group_messages))
                .build(),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat
                .Builder(GROUP_APP)
                .setName(context.getString(R.string.notif_group_app))
                .build(),
        )

        manager.createNotificationChannel(
            channel(
                context,
                NEARBY,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_nearby_name,
                R.string.channel_nearby_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                GROUPS,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.channel_groups_name,
                R.string.channel_groups_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                DMS,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.channel_dms_name,
                R.string.channel_dms_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                MESHTASTIC,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_LOW,
                R.string.channel_meshtastic_name,
                R.string.channel_meshtastic_desc,
            ),
        )
        // A channel is a row in the OS's notification settings, so a build that hides the commons
        // (`BuildConfig.COMMONS`) registers none; no post can arrive there to need it.
        if (BuildConfig.COMMONS) {
            manager.createNotificationChannel(
                channel(
                    context,
                    COMMONS,
                    GROUP_MESSAGES,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    R.string.channel_commons_name,
                    R.string.channel_commons_desc,
                ),
            )
        }
        manager.createNotificationChannel(
            channel(
                context,
                MENTIONS,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.mention_channel_name,
                R.string.mention_channel_description,
                sound = R.raw.knit_mention,
                old = oldMentions,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                REQUESTS,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.channel_requests_name,
                R.string.channel_requests_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                STATUS,
                GROUP_APP,
                NotificationManagerCompat.IMPORTANCE_MIN,
                R.string.mesh_channel_name,
                R.string.channel_status_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                ALERTS,
                GROUP_APP,
                NotificationManagerCompat.IMPORTANCE_LOW,
                R.string.channel_alerts_name,
                R.string.channel_alerts_desc,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                OPEN_TO_CHAT,
                GROUP_APP,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_open_to_chat_name,
                R.string.channel_open_to_chat_desc,
                sound = R.raw.knit_open_to_chat,
                old = oldOpenToChat,
            ),
        )

        // Drop the pre-reorg flat channels + the pre-bump DEFAULT/LOW channels so they don't linger.
        manager.deleteNotificationChannel(LEGACY_MESSAGES)
        manager.deleteNotificationChannel(LEGACY_MENTIONS)
        manager.deleteNotificationChannel(LEGACY_GROUPS)
        manager.deleteNotificationChannel(LEGACY_REQUESTS)
        manager.deleteNotificationChannel(SOUNDLESS_MENTIONS)
        manager.deleteNotificationChannel(SOUNDLESS_OPEN_TO_CHAT)
    }

    /**
     * A channel made under a new id, built on [old] (the channel it replaces) when that still exists: what the user set
     * there carries over. Importance carries when the user turned it down (silenced, or blocked at
     * `IMPORTANCE_NONE`); sound when it was None or one the user picked rather than the stock default or Knit's
     * message sound, which the new sound replaces; vibration, lights and badge as they were. Lock-screen visibility
     * and Do Not Disturb bypass can't carry: the platform resets both on any channel an app creates. Only the first
     * `ensure` after the bump sees [old]; after that it is gone and the new channel is the user's to change.
     */
    @Suppress("LongParameterList")
    private fun channel(
        context: Context,
        id: String,
        group: String,
        importance: Int,
        nameRes: Int,
        descRes: Int,
        @RawRes sound: Int? = if (group == GROUP_MESSAGES) R.raw.knit else null,
        old: NotificationChannelCompat? = null,
    ): NotificationChannelCompat {
        val builder =
            NotificationChannelCompat
                .Builder(id, old?.importance?.takeIf { it < importance } ?: importance)
                .setName(context.getString(nameRes))
                .setDescription(context.getString(descRes))
                .setGroup(group)
        if (sound != null) builder.setSound(rawSound(context, sound), soundAttributes)
        if (old != null) {
            val oldSound = old.sound
            if (oldSound == null || oldSound !in stockSounds(context)) {
                builder.setSound(oldSound, old.audioAttributes ?: soundAttributes)
            }
            builder
                // Pattern first: the compat setter also sets vibration on only for a non-empty pattern, so a user's
                // plain Vibrate toggle (on, no pattern) would be switched back off if the flag came first.
                .setVibrationPattern(old.vibrationPattern)
                .setVibrationEnabled(old.shouldVibrate())
                .setLightsEnabled(old.shouldShowLights())
                .setLightColor(old.lightColor)
                .setShowBadge(old.canShowBadge())
        }
        return builder.build()
    }

    /** The sounds an old channel held without the user choosing one: the system default, or Knit's message sound. */
    private fun stockSounds(context: Context): Set<Uri> = setOf(Settings.System.DEFAULT_NOTIFICATION_URI, messageSound(context))
}
