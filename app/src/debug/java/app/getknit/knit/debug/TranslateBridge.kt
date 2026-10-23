package app.getknit.knit.debug

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.LocaleList
import android.os.SystemClock
import app.getknit.knit.call.SttModelState
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.identity.Identity
import app.getknit.knit.translate.ComposePolicy
import app.getknit.knit.translate.LanguagePick
import app.getknit.knit.translate.LanguagePolicy
import app.getknit.knit.translate.MessageTranslation
import app.getknit.knit.translate.MessageTranslator
import app.getknit.knit.translate.ReaderLanguages
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.translate.TranslationCheck
import app.getknit.knit.translate.VerbatimSpans
import app.getknit.knit.ui.translate.SlimtEngine
import app.getknit.knit.ui.translate.TranslateModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.math.ceil

/**
 * `…debug.TRANSLATE`, the translation spike's bench (ADR 2026-10.t2h6), in a debug build made with `-Ptranslate=true`.
 *
 * - `status` (the default): each direction's model, which are in memory, the bench's last run and any translation the
 *   `translate` op asked for.
 * - `sideload --es dir <dir>`: installs each direction from `<dir>/<model id>/` (what `scripts/translate-model.py --out`
 *   stages; push it world-readable, e.g. under /data/local/tmp). `download --es direction en-es` fetches one from
 *   getknit.app as the bubble's Download does.
 * - `detect --es text <text>`: CLD2's raw answer and the pick, read without the text's links, positions and mentions.
 * - `translate --es text <text> [--es from es]`: the chat's own path (spans, the check, the screen); poll `status`.
 * - `bench --es file <corpus> --es from en [--ei runs 1]`: in the background, a cold load timed, then every line of the
 *   corpus through the model whole, `runs` times (each later run numbered, past slimt's cache); writes one JSON line
 *   per message to the app's external files (`translate-bench/<from>-<to>.jsonl`, the translation included) and puts
 *   the timings and memory in `status`.
 * - `audit [--ei limit 300]`: the language read over this phone's newest messages from other people, by the words
 *   alone and then with the author's and the chat's language behind them (ADR 2026-10.t2h6's short-line amendment):
 *   how many each settles, how many are still unsure, and how many the priors changed. Counts only, no text.
 * - `reader`: what the product rule would pick as this reader's language, from this phone's own recent messages and the
 *   system's language list, beside the app's resolved locale.
 * - `unload`: lets both models go.
 */
internal object TranslateBridge : KoinComponent {
    private const val TAG_RUN = "bridge-"
    private const val OWN_SAMPLE = 50
    private const val SHORT = 40
    private const val MEDIUM = 160
    private const val PERCENTILE_50 = 0.5
    private const val PERCENTILE_95 = 0.95

    @Volatile
    private var benchRun: Job? = null

    @Volatile
    private var lastBench: JSONObject? = null

    private var asked = 0

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod") // a flat dispatch over the ops
    suspend fun handle(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val store =
            getKoin().getOrNull<TranslateModelStore>() ?: return reply("error", "this build wires no translation (-Ptranslate=true)")
        val engine = get<SlimtEngine>()
        val translator = get<MessageTranslator>()
        withContext(Dispatchers.IO) { store.start() }
        when (val op = intent.getStringExtra("op") ?: "status") {
            "status" -> {}

            "sideload" -> {
                val dir = intent.getStringExtra("dir")?.let(::File) ?: return reply("error", "missing 'dir' extra")
                for (direction in TranslateDirection.ALL) store.sideload(direction, File(dir, direction.id))
            }

            "download" -> {
                val direction = direction(intent.getStringExtra("direction")) ?: return reply("error", "direction is en-es or es-en")
                if (!store.download(direction)) return reply("error", "no route to fetch over")
            }

            "detect" -> {
                val text = intent.getStringExtra("text") ?: return reply("error", "missing 'text' extra")
                val words = VerbatimSpans.wordsOnly(text)
                val detection = engine.detect(words)
                return reply("ok", "detected")
                    .put("words", words)
                    .put("cld2", detection?.let { "${it.top} reliable=${it.reliable} bytes=${it.bytes}" } ?: JSONObject.NULL)
                    .put("pick", pickJson(LanguagePolicy.pick(detection, words)))
            }

            "translate" -> {
                val text = intent.getStringExtra("text") ?: return reply("error", "missing 'text' extra")
                val from =
                    intent.getStringExtra("from")?.let(TranslateLang::ofCode)
                        ?: (translator.pick(text) as? LanguagePick.Picked)?.lang
                        ?: return reply("unsure", "the identifier can't tell; pass --es from en|es")
                val id = TAG_RUN + ++asked
                if (!translator.translate(id, text, from, isRoom = false)) return reply("refused", "running, or the model isn't installed")
                return reply("ok", "translating as $id; poll status").put("id", id)
            }

            "bench" -> {
                val file = intent.getStringExtra("file")?.let(::File) ?: return reply("error", "missing 'file' extra")
                val from = intent.getStringExtra("from")?.let(TranslateLang::ofCode) ?: return reply("error", "from is en or es")
                val direction = TranslateDirection.of(from, from.other) ?: return reply("error", "no direction from $from")
                if (store.paths(direction) == null) return reply("error", "$direction isn't installed")
                val lines =
                    withContext(Dispatchers.IO) { runCatching { file.readLines() }.getOrNull() }
                        ?: return reply("error", "can't read $file")
                val runs = intent.getIntExtra("runs", 1).coerceIn(1, 100)
                val out = File(context.getExternalFilesDir("translate-bench"), "${from.code}-${from.other.code}.jsonl")
                benchRun?.cancel()
                lastBench = JSONObject().put("status", "running").put("direction", direction.toString())
                benchRun =
                    get<CoroutineScope>().launch(Dispatchers.Default) {
                        lastBench = bench(engine, direction, lines.filter { it.isNotBlank() }, runs, out)
                    }
                return reply("ok", "benching $direction over ${lines.size} lines x $runs; poll status").put("out", out.path)
            }

            "reader" -> {
                return reader(context, translator)
            }

            "audit" -> {
                return audit(translator, intent.getIntExtra("limit", AUDIT_LIMIT))
            }

            "unload" -> {
                engine.unload()
            }

            else -> {
                return reply("error", "unknown op '$op'")
            }
        }
        return reply("ok", "translate status")
            .put(
                "models",
                JSONObject().also { models ->
                    TranslateDirection.ALL.forEach { models.put(it.toString(), stateJson(store.state(it).value)) }
                },
            ).put("resident", JSONObject().also { r -> engine.resident().forEach { (d, on) -> r.put(d.toString(), on) } })
            .put("bench", lastBench ?: JSONObject.NULL)
            .put("asked", askedJson(translator))
    }

    private fun reply(
        status: String,
        message: String,
    ): JSONObject = JSONObject().put("status", status).put("message", message)

    private fun direction(name: String?): TranslateDirection? =
        TranslateDirection.ALL.firstOrNull { it.toString().replace(">", "") == name }

    private fun stateJson(state: SttModelState): JSONObject =
        when (state) {
            SttModelState.Absent -> JSONObject().put("state", "absent")
            SttModelState.Ready -> JSONObject().put("state", "ready")
            is SttModelState.Downloading -> JSONObject().put("state", "downloading").put("done", state.done).put("total", state.total)
            is SttModelState.Failed -> JSONObject().put("state", "failed").put("reason", state.reason)
        }

    private fun pickJson(pick: LanguagePick): String =
        when (pick) {
            is LanguagePick.Picked -> pick.lang.code
            LanguagePick.Unsure -> "unsure"
        }

    private fun askedJson(translator: MessageTranslator): JSONObject =
        JSONObject().also { out ->
            translator.state.value
                .filterKeys { it.startsWith(TAG_RUN) }
                .forEach { (id, state) ->
                    out.put(
                        id,
                        when (state) {
                            MessageTranslation.Running -> {
                                JSONObject().put("state", "running")
                            }

                            is MessageTranslation.Done -> {
                                JSONObject()
                                    .put("state", "done")
                                    .put("text", state.text)
                                    .put("flagged", state.flagged)
                                    .put("ms", state.ms)
                            }

                            is MessageTranslation.NeedsModel -> {
                                JSONObject().put("state", "needs model ${state.direction}")
                            }

                            is MessageTranslation.Failed -> {
                                JSONObject().put("state", "failed ${state.reason}")
                            }
                        },
                    )
                }
        }

    // A cold load timed, then every line through the model whole: speed, memory, and what the chat's checks would say.
    @Suppress("LongMethod") // one bench, read top to bottom
    private suspend fun bench(
        engine: SlimtEngine,
        direction: TranslateDirection,
        lines: List<String>,
        runs: Int,
        out: File,
    ): JSONObject {
        engine.unload()
        System.gc()
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val pssBefore = Debug.getPss()
        val loadMs = engine.timeLoad(direction) ?: return JSONObject().put("status", "error").put("message", "the model wouldn't load")
        val heapAfter = Debug.getNativeHeapAllocatedSize()
        val pssAfter = Debug.getPss()
        val times = mutableMapOf("short" to mutableListOf<Long>(), "medium" to mutableListOf(), "long" to mutableListOf())
        var unsure = 0
        var lost = 0
        var unusable = 0
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            out.bufferedWriter().use { writer ->
                repeat(runs) { run ->
                    lines.forEachIndexed { index, base ->
                        // slimt caches what it translated: a later run's line is numbered, so it is translated again.
                        val line = if (run == 0) base else "$run. $base"
                        val words = VerbatimSpans.wordsOnly(line)
                        val detection = engine.detect(words)
                        val pick = LanguagePolicy.pick(detection, words)
                        val t0 = SystemClock.elapsedRealtime()
                        val output = engine.translate(direction, line) ?: ""
                        val ms = SystemClock.elapsedRealtime() - t0
                        val kept = VerbatimSpans.spans(line).all { output.contains(it) }
                        val usable = TranslationCheck.usable(line, output)
                        val bucket =
                            if (line.length <= SHORT) {
                                "short"
                            } else if (line.length <= MEDIUM) {
                                "medium"
                            } else {
                                "long"
                            }
                        times.getValue(bucket).add(ms)
                        if (run == 0) {
                            if (pick == LanguagePick.Unsure) unsure++
                            if (!kept) lost++
                            if (!usable) unusable++
                            writer.write(
                                JSONObject()
                                    .put("n", index + 1)
                                    .put("ms", ms)
                                    .put("chars", line.length)
                                    .put("pick", pickJson(pick))
                                    .put("spansKept", kept)
                                    .put("usable", usable)
                                    .put("out", output)
                                    .toString(),
                            )
                            writer.newLine()
                        }
                    }
                }
            }
        }
        return JSONObject()
            .put("status", "done")
            .put("direction", direction.toString())
            .put("lines", lines.size)
            .put("runs", runs)
            .put("loadMs", loadMs)
            .put("nativeHeapDeltaKb", (heapAfter - heapBefore) / KB)
            .put("pssDeltaKb", pssAfter - pssBefore)
            .put("wallMs", SystemClock.elapsedRealtime() - started)
            .put("unsure", unsure)
            .put("spansLost", lost)
            .put("unusable", unusable)
            .put(
                "ms",
                JSONObject().also { ms ->
                    times.forEach { (bucket, list) -> if (list.isNotEmpty()) ms.put(bucket, percentiles(list)) }
                },
            ).put("out", out.path)
    }

    // Nearest rank: the smallest value at least [p] of the list is at or under.
    private fun percentiles(list: List<Long>): JSONObject {
        val sorted = list.sorted()

        fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return JSONObject()
            .put("n", sorted.size)
            .put("p50", rank(PERCENTILE_50))
            .put("p95", rank(PERCENTILE_95))
            .put("max", sorted.last())
    }

    // The product rule's pick for this reader (ADR 2026-10.t2h6), measured: never what the spike translates into.
    private suspend fun reader(
        context: Context,
        translator: MessageTranslator,
    ): JSONObject {
        val me = get<Identity>().nodeId()
        val own = get<MessageDao>().recentOwnBodies(me, OWN_SAMPLE)
        val picks = own.map { translator.pick(it) }
        val system = LocaleList.getDefault()
        val preferred = (0 until system.size()).map { system[it].toLanguageTag() }
        val estimate = ReaderLanguages.estimate(picks, preferred)
        return reply("ok", "reader languages")
            .put("ownSampled", own.size)
            .put("written", JSONObject().also { w -> estimate.written.forEach { (lang, n) -> w.put(lang.code, n) } })
            .put("unsure", estimate.unsure)
            .put("systemLanguages", JSONArray(preferred))
            .put(
                "appLocale",
                context.resources.configuration.locales[0]
                    .toLanguageTag(),
            ).put("target", estimate.target?.code ?: JSONObject.NULL)
    }

    private const val KB = 1024
    private const val AUDIT_LIMIT = 300
    private const val AUDIT_SHORT = 20

    // The words alone against the full resolution, over real messages: what the author's and the chat's language add.
    @Suppress("LongMethod") // one tally, read top to bottom
    private suspend fun audit(
        translator: MessageTranslator,
        limit: Int,
    ): JSONObject {
        val me = get<Identity>().nodeId()
        val rows =
            get<MessageDao>()
                .recentWithText(limit.coerceIn(1, AUDIT_LIMIT * 10))
                .filter { it.senderId != me && VerbatimSpans.wordsOnly(it.body).isNotBlank() }
        val chats =
            rows.groupBy { it.conversationId }.mapValues { (_, newestFirst) ->
                ComposePolicy.threadLanguage(newestFirst.take(ComposePolicy.THREAD_SAMPLE).map { translator.pick(it.body) })
            }
        var wordsUnsure = 0
        var shortTotal = 0
        var shortWordsUnsure = 0
        var shortStillUnsure = 0
        var changed = 0
        val bySource = mutableMapOf<String, Int>()
        for (m in rows) {
            val words = translator.pick(m.body)
            val resolved = translator.resolve(m.body, emptyList(), m.conversationId, m.senderId, chats[m.conversationId])
            val short = VerbatimSpans.wordsOnly(m.body).length <= AUDIT_SHORT
            if (words == LanguagePick.Unsure) wordsUnsure++
            if (short) {
                shortTotal++
                if (words == LanguagePick.Unsure) shortWordsUnsure++
                if (resolved.pick == LanguagePick.Unsure) shortStillUnsure++
            }
            if (words is LanguagePick.Picked && resolved.pick is LanguagePick.Picked && words != resolved.pick) changed++
            val key = resolved.source?.name?.lowercase() ?: "unsure"
            bySource[key] = (bySource[key] ?: 0) + 1
        }
        return reply("ok", "language audit")
            .put("messages", rows.size)
            .put("chats", chats.size)
            .put("chatsWithALanguage", chats.values.count { it != null })
            .put("wordsAloneUnsure", wordsUnsure)
            .put("resolvedBy", JSONObject(bySource.toMap()))
            .put("changedByHint", changed)
            .put(
                "short",
                JSONObject()
                    .put("chars", AUDIT_SHORT)
                    .put("messages", shortTotal)
                    .put("wordsAloneUnsure", shortWordsUnsure)
                    .put("stillUnsure", shortStillUnsure),
            )
    }
}
