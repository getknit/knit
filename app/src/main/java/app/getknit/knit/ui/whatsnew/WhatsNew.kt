package app.getknit.knit.ui.whatsnew

import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.data.settings.WhatsNewRecord

/** One feature inside a [WhatsNewEntry]: a short title and a sentence or two on what the user can now do. */
data class WhatsNewItem(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

/**
 * One announcement: the features worth telling people about in a release, written about what they can now
 * do rather than what changed (that is `CHANGELOG.md`'s job). [id] is the content's identity, not the
 * version — a patch release adds no entry, and one feature spread over two versions is announced once.
 * Never reuse or rename an id: it is what [WhatsNewRecord.seenId] remembers.
 */
data class WhatsNewEntry(
    val id: String,
    /** The release it shipped in, as the screen labels it ("Knit 2.9"). */
    val version: String,
    /** The same release in full ("2.9.0"): the changelog link's version, `releaseNotesUrl`. */
    val release: String,
    @param:StringRes val headline: Int,
    /** The card's one line under the headline. */
    @param:StringRes val summary: Int,
    val items: List<WhatsNewItem>,
)

/**
 * Every announcement, newest first, bundled in the APK: Knit is offline, so nothing is fetched, and the
 * release build stays byte-reproducible. Add an entry only for a release with something people would want to
 * be told about; the chat list's card announces [latest] alone.
 */
object WhatsNew {
    val entries: List<WhatsNewEntry> =
        listOf(
            WhatsNewEntry(
                id = "2026-10-calls",
                version = "2.10",
                release = "2.10.0",
                headline = R.string.whats_new_2_10_headline,
                summary = R.string.whats_new_2_10_summary,
                items =
                    listOf(
                        WhatsNewItem(R.string.whats_new_2_10_calls_title, R.string.whats_new_2_10_calls_body),
                        WhatsNewItem(R.string.whats_new_2_10_mesh_call_title, R.string.whats_new_2_10_mesh_call_body),
                        WhatsNewItem(R.string.whats_new_2_10_video_title, R.string.whats_new_2_10_video_body),
                        WhatsNewItem(R.string.whats_new_2_10_anywhere_title, R.string.whats_new_2_10_anywhere_body),
                        WhatsNewItem(R.string.whats_new_2_10_wifi_aware_title, R.string.whats_new_2_10_wifi_aware_body),
                    ),
            ),
            WhatsNewEntry(
                id = "2026-10-bluetooth-range",
                version = "2.9",
                release = "2.9.0",
                headline = R.string.whats_new_2_9_headline,
                summary = R.string.whats_new_2_9_summary,
                items =
                    listOf(
                        WhatsNewItem(R.string.whats_new_2_9_range_title, R.string.whats_new_2_9_range_body),
                        WhatsNewItem(R.string.whats_new_2_9_progress_title, R.string.whats_new_2_9_progress_body),
                        WhatsNewItem(R.string.whats_new_2_9_filter_title, R.string.whats_new_2_9_filter_body),
                        WhatsNewItem(R.string.whats_new_2_9_field_log_title, R.string.whats_new_2_9_field_log_body),
                    ),
            ),
        )

    val latest: WhatsNewEntry? get() = entries.firstOrNull()
}

/**
 * When the chat list's What's New card shows. It announces [latest] until the user closes it or opens the
 * screen ([WhatsNewRecord.seenId]), and retires on its own once it has been up for [SHOW_FOR_MS] or
 * [SHOW_FOR_OPENS] visits to the chat list, so nobody has to swipe it away. A fresh install never sees it:
 * onboarding marks the newest entry seen, because "what's new" means nothing before anything was old.
 */
object WhatsNewPolicy {
    const val SHOW_FOR_MS = 7L * 24 * 60 * 60 * 1000
    const val SHOW_FOR_OPENS = 10

    fun cardFor(
        latest: WhatsNewEntry?,
        record: WhatsNewRecord,
        now: Long,
    ): WhatsNewEntry? {
        if (latest == null || record.seenId == latest.id) return null
        if (record.shownId == latest.id) {
            // A clock set backwards reads as "just shown" rather than "shown forever ago".
            val age = (now - record.shownAt).coerceAtLeast(0L)
            if (age >= SHOW_FOR_MS || record.opens > SHOW_FOR_OPENS) return null
        }
        return latest
    }
}
