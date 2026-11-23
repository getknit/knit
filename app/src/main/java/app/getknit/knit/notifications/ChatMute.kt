package app.getknit.knit.notifications

import android.content.Context
import android.text.format.DateUtils
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.pausedUntilLabel
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.chat.EVERYONE_NAME
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The spans a chat can be muted for (ADR 2026-11.sp7k), Signal's five. Their labels (`chat_mute_for_*`) name these
 * numbers.
 */
enum class MuteFor(
    private val millis: Long,
) {
    HOUR(TimeUnit.HOURS.toMillis(1)),
    EIGHT_HOURS(TimeUnit.HOURS.toMillis(EIGHT)),
    DAY(TimeUnit.DAYS.toMillis(1)),
    WEEK(TimeUnit.DAYS.toMillis(SEVEN)),
    ALWAYS(ChatMutes.ALWAYS),
    ;

    /** When a mute of this span started at [now] ends. */
    fun deadline(now: Long): Long = if (this == ALWAYS) ChatMutes.ALWAYS else now + millis
}

private const val EIGHT = 8L
private const val SEVEN = 7L

/**
 * The per-chat mute's rules (ADR 2026-11.sp7k): one reading of a stored deadline for every surface, and the one
 * question the notification gate asks. Pure, so the gate and the marks agree and are tested on the JVM.
 */
object ChatMute {
    /** How a deadline is worded, by how far off it is. */
    enum class LabelStyle { TIME, WEEKDAY_TIME, DATE_TIME }

    /**
     * Past this a weekday names the wrong week: a seven-day mute set on a Monday would read "Mon 3:15 PM". The label
     * then names the date.
     */
    private val DATE_AFTER_MS = TimeUnit.DAYS.toMillis(6)

    /** [until] if it is still ahead of [now], else null: the one reading of a stored mute every surface shares. */
    fun activeUntil(
        until: Long?,
        now: Long,
    ): Long? = until?.takeIf { it > now }

    /**
     * Whether a mute ending at [until] keeps a notification for a [kind] conversation off the shade at [now]. A
     * [personalMention] still gets through in a group or a commons the user accepted, as Signal lets one through a
     * muted group; nowhere else, since a DM's peer could break a mute with one `@` and a room's stranger with one
     * more. Any member of such a room can break it so, a stranger a contact added or an invite let in included,
     * but only there. A bridged room's `@[Name]` is a claim anyone on the channel can type, so it never counts.
     */
    fun silences(
        until: Long?,
        now: Long,
        personalMention: Boolean,
        kind: ConversationKind,
    ): Boolean {
        if (activeUntil(until, now) == null) return false
        val mentionGetsThrough = personalMention && (kind == ConversationKind.GROUP || kind == ConversationKind.COMMONS)
        return !mentionGetsThrough
    }

    /**
     * Whether [mentions] name [me] for themselves. `@everyone` reaches each member as one mention apiece under the name
     * [EVERYONE_NAME] (ADR 2026-10.6h2g), so it is a mention of the room, not of the person, and a mute holds against it.
     */
    fun personalMention(
        mentions: List<Mention>,
        me: String,
    ): Boolean = mentions.any { it.nodeId == me && it.name != EVERYONE_NAME }

    /**
     * The live entries of [source] — conversation id → deadline, a lapsed one dropped — emitted again as each timed
     * mute lapses, so a screen un-mutes on time without a ticker of its own. It re-checks at least every
     * [RECHECK_MS]: `delay` stops while the phone sleeps and [now] is the wall clock, so one long wait would hold a
     * lapsed mark past its moment.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // transformLatest: a new map cancels the wait for the last one's deadline
    fun active(
        source: Flow<Map<String, Long>>,
        now: () -> Long,
    ): Flow<Map<String, Long>> =
        source
            .transformLatest { all ->
                while (true) {
                    val at = now()
                    val live = all.filterValues { it > at }
                    emit(live)
                    val next = live.values.filter { it != ChatMutes.ALWAYS }.minOrNull() ?: break
                    delay(minOf(next - at, RECHECK_MS))
                }
            }.distinctUntilChanged()

    /** The style a deadline at [until] is worded in at [now], on [zone]'s calendar. */
    fun labelStyle(
        until: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LabelStyle =
        when {
            until - now >= DATE_AFTER_MS -> LabelStyle.DATE_TIME
            MeshPause.crossesDay(until, now, zone) -> LabelStyle.WEEKDAY_TIME
            else -> LabelStyle.TIME
        }

    const val RECHECK_MS = 60_000L
}

/**
 * "3:15 PM", "Tue 3:15 PM", or "Nov 29, 3:15 PM" once a weekday would be ambiguous, in the phone's own time format:
 * when a timed mute ends. A mute with no end has its own words (`chat_muted`), never this.
 */
fun mutedUntilLabel(
    context: Context,
    until: Long,
    now: Long,
): String =
    when (ChatMute.labelStyle(until, now)) {
        ChatMute.LabelStyle.DATE_TIME -> {
            DateUtils.formatDateTime(
                context,
                until,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
            )
        }

        else -> {
            pausedUntilLabel(context, until, now)
        }
    }
