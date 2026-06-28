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
    const val GROUPS = "knit_msg_groups"
    const val DMS = "knit_msg_dms"
    const val MENTIONS = "knit_msg_mentions"

    // App group
    const val STATUS = "knit_mesh" // ongoing foreground notification; id kept stable
    const val ALERTS = "knit_alerts"

    private const val GROUP_MESSAGES = "knit_grp_messages"
    private const val GROUP_APP = "knit_grp_app"

    private const val LEGACY_MESSAGES = "knit_messages"
    private const val LEGACY_MENTIONS = "knit_mentions"

    /** The message channel for a conversation [kind]. (Mentions route to [MENTIONS] separately.) */
    fun channelFor(kind: ConversationKind): String = when (kind) {
        ConversationKind.NEARBY -> NEARBY
        ConversationKind.GROUP -> GROUPS
        ConversationKind.DM -> DMS
    }

    /** Creates the channel groups + channels and removes the legacy flat channels. Idempotent. */
    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(GROUP_MESSAGES)
                .setName(context.getString(R.string.notif_group_messages))
                .build(),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(GROUP_APP)
                .setName(context.getString(R.string.notif_group_app))
                .build(),
        )

        manager.createNotificationChannel(
            channel(context, NEARBY, GROUP_MESSAGES, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_nearby_name, R.string.channel_nearby_desc),
        )
        manager.createNotificationChannel(
            channel(context, GROUPS, GROUP_MESSAGES, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_groups_name, R.string.channel_groups_desc),
        )
        manager.createNotificationChannel(
            channel(context, DMS, GROUP_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.channel_dms_name, R.string.channel_dms_desc),
        )
        manager.createNotificationChannel(
            channel(context, MENTIONS, GROUP_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.mention_channel_name, R.string.mention_channel_description),
        )
        manager.createNotificationChannel(
            channel(context, STATUS, GROUP_APP, NotificationManagerCompat.IMPORTANCE_MIN,
                R.string.mesh_channel_name, R.string.channel_status_desc),
        )
        manager.createNotificationChannel(
            channel(context, ALERTS, GROUP_APP, NotificationManagerCompat.IMPORTANCE_LOW,
                R.string.channel_alerts_name, R.string.channel_alerts_desc),
        )

        // Drop the pre-reorg flat channels so they don't linger in system settings.
        manager.deleteNotificationChannel(LEGACY_MESSAGES)
        manager.deleteNotificationChannel(LEGACY_MENTIONS)
    }

    private fun channel(
        context: Context,
        id: String,
        group: String,
        importance: Int,
        nameRes: Int,
        descRes: Int,
    ) = NotificationChannelCompat.Builder(id, importance)
        .setName(context.getString(nameRes))
        .setDescription(context.getString(descRes))
        .setGroup(group)
        .build()
}
