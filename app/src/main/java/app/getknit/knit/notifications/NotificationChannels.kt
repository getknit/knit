package app.getknit.knit.notifications

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
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
    const val MENTIONS = "knit_msg_mentions"

    // Coalesced "message request received" heads-up for a stranger's first (unaccepted) DM/group.
    // Bumped to _v2 to raise importance to HIGH so it pops up (even while the app is foregrounded): a
    // channel's importance is immutable once created, so the LOW-importance `knit_msg_requests` is
    // deleted (see [ensure]) and recreated under a fresh id.
    const val REQUESTS = "knit_msg_requests_v2"

    // App group
    const val STATUS = "knit_mesh" // ongoing foreground notification; id kept stable
    const val ALERTS = "knit_alerts"

    private const val GROUP_MESSAGES = "knit_grp_messages"
    private const val GROUP_APP = "knit_grp_app"

    private const val LEGACY_MESSAGES = "knit_messages"
    private const val LEGACY_MENTIONS = "knit_mentions"

    // The pre-bump DEFAULT-importance Groups channel; replaced by [GROUPS] (_v2) at HIGH importance.
    private const val LEGACY_GROUPS = "knit_msg_groups"

    // The pre-bump LOW-importance Requests channel; replaced by [REQUESTS] (_v2) at HIGH importance.
    private const val LEGACY_REQUESTS = "knit_msg_requests"

    /** The message channel for a conversation [kind]. (Mentions route to [MENTIONS] separately.) */
    fun channelFor(kind: ConversationKind): String =
        when (kind) {
            ConversationKind.NEARBY -> NEARBY
            ConversationKind.GROUP -> GROUPS
            ConversationKind.DM -> DMS
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
                MENTIONS,
                GROUP_MESSAGES,
                NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.mention_channel_name,
                R.string.mention_channel_description,
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

        // Drop the pre-reorg flat channels + the pre-bump DEFAULT/LOW channels so they don't linger.
        manager.deleteNotificationChannel(LEGACY_MESSAGES)
        manager.deleteNotificationChannel(LEGACY_MENTIONS)
        manager.deleteNotificationChannel(LEGACY_GROUPS)
        manager.deleteNotificationChannel(LEGACY_REQUESTS)
    }

    private fun channel(
        context: Context,
        id: String,
        group: String,
        importance: Int,
        nameRes: Int,
        descRes: Int,
    ) = NotificationChannelCompat
        .Builder(id, importance)
        .setName(context.getString(nameRes))
        .setDescription(context.getString(descRes))
        .setGroup(group)
        .build()
}
