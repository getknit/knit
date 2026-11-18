package app.getknit.knit.mesh.sim

import java.io.File
import java.util.Locale

/**
 * The report's headline numbers as `key=value` lines, for a script to read across runs (`scripts/sim-compare.py`): the
 * prose above it says what they mean, this says them once more in a form two builds can be lined up by. A value is a
 * number, `none` (the thing never happened, such as a settle that never reached parity) or a word ([oracleWord]). Keys
 * keep the order they were put in; a key put twice keeps its first place and its last value.
 */
class SimSummary {
    private val values = LinkedHashMap<String, String>()

    operator fun set(
        key: String,
        value: Any?,
    ) {
        require(KEY.matches(key)) { "a summary key is lower_snake_case: $key" }
        values[key] =
            when (value) {
                null -> NONE
                is Double -> "%.2f".format(Locale.ROOT, value)
                is Float -> "%.2f".format(Locale.ROOT, value)
                else -> value.toString().also { require(it.none(Char::isWhitespace)) { "a summary value is one word: $key=$it" } }
            }
    }

    fun putAll(pairs: List<Pair<String, Any?>>) = pairs.forEach { (key, value) -> this[key] = value }

    /** `summary:` and one indented `key=value` line per value. */
    fun lines(): List<String> = listOf(HEADER) + values.map { (key, value) -> "  $key=$value" }

    companion object {
        const val HEADER = "summary:"
        const val NONE = "none"
        private val KEY = Regex("[a-z][a-z0-9_]*")
    }
}

/**
 * The oracle's verdict as one word: `pass` or `fail` when it was asked, and when a gathered outing never asked it, why:
 * `stranded` (a DM never arrived) or `unsettled` (custody never matched in a settle as long as its wait). A walk-only
 * outing, which never asks, writes `skipped`.
 */
internal fun oracleWord(
    failure: String?,
    stranded: Boolean,
    unsettled: Boolean,
): String =
    when {
        failure == null -> "pass"
        stranded -> "stranded"
        unsettled -> "unsettled"
        else -> "fail"
    }

/**
 * A new, empty report file in [dir]: `<name>.txt`, or `<name>-2.txt` and on when an earlier run's report is there. A
 * rerun never overwrites one, so two runs of the same outing both stay to be compared.
 */
internal fun freshReport(
    dir: File,
    name: String,
): File =
    generateSequence(1) { it + 1 }
        .map { n -> dir.resolve(if (n == 1) "$name.txt" else "$name-$n.txt") }
        .first { it.createNewFile() }

/** What the outing was, as a summary's first keys (its radio's follow): two runs line up by everything here but the seed. */
fun SimScenario.summaryValues(): List<Pair<String, Any?>> =
    listOf(
        "seed" to seed,
        "walkers" to walkers,
        "minutes" to durationMs / MS_PER_MINUTE,
        "field_m" to fieldM,
        "inbound_slots" to inboundSlots,
        "gather" to gather,
        "settle_minutes" to settleMs / MS_PER_MINUTE,
        "motion" to motion,
        "groups" to GroupSizes.of(groups, groupSize).name,
        "friends" to friends,
    )

/**
 * [deliveryLines]' numbers per [Reach] and for all DMs: how many were sent, the share that arrived inside the run and by
 * the end, and the latency of the ones that arrived inside it (whole percent, seconds to a tenth; `none` with nothing to
 * measure).
 */
fun deliverySummary(outcomes: List<DmOutcome>): List<Pair<String, Any?>> =
    (Reach.entries.map { reach -> reach.name.lowercase() to outcomes.filter { it.reach == reach } } + ("all" to outcomes))
        .flatMap { (label, sent) ->
            val inRun = sent.filter { it.arrivedInRun }
            val latencies = inRun.mapNotNull { it.latencyMs }.sorted()
            listOf(
                "dm_${label}_sent" to sent.size,
                "dm_${label}_in_run_pct" to share(inRun.size, sent.size),
                "dm_${label}_by_end_pct" to share(sent.count { it.latencyMs != null }, sent.size),
                "dm_${label}_p50_s" to tenths(percentile(latencies, P50)),
                "dm_${label}_p90_s" to tenths(percentile(latencies, P90)),
            )
        }

/** [part] of [whole] in whole percent, or null (`none`) when there is no whole. */
internal fun share(
    part: Int,
    whole: Int,
): Int? = if (whole == 0) null else part * PERCENT / whole

/** [ms] in seconds to a tenth, as a word, or null. */
internal fun tenths(ms: Long?): String? = ms?.let { "%.1f".format(Locale.ROOT, it / MS_PER_S) }

private const val PERCENT = 100
private const val MS_PER_S = 1_000.0
private const val MS_PER_MINUTE = 60_000L
