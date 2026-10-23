package app.getknit.knit.translate

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The engine under the translator: slimt and CLD2 on a phone, a fake in a test. */
interface TranslateEngine {
    /** Whether [direction]'s files are on the phone. */
    fun installed(direction: TranslateDirection): Boolean

    /** CLD2's answer for [text], or null when the library is not there. */
    suspend fun detect(text: String): Detection?

    /** [text] through [direction]'s model, or null when the model would not load or run. */
    suspend fun translate(
        direction: TranslateDirection,
        text: String,
    ): String?
}

/** Where one message's translation stands. */
sealed interface MessageTranslation {
    data object Running : MessageTranslation

    /** [flagged] when Content filtering would hide [text] in the message's scope. */
    data class Done(
        val text: String,
        val from: TranslateLang,
        val to: TranslateLang,
        val flagged: Boolean,
        val ms: Long,
    ) : MessageTranslation

    /** [direction]'s model is not on the phone: nothing ran. */
    data class NeedsModel(
        val direction: TranslateDirection,
    ) : MessageTranslation

    data class Failed(
        val reason: Reason,
    ) : MessageTranslation {
        enum class Reason {
            /** The model would not load or run. */
            ENGINE,

            /** It ran and wrote something [TranslationCheck] refuses. */
            UNUSABLE,
        }
    }
}

/** What one body through the model came to, for the composer's own translation (it screens nothing). */
sealed interface TranslateOutcome {
    data class Text(
        val text: String,
        val from: TranslateLang,
        val to: TranslateLang,
    ) : TranslateOutcome

    /** [direction]'s model is not on the phone: nothing ran. */
    data class NeedsModel(
        val direction: TranslateDirection,
    ) : TranslateOutcome

    data class Failed(
        val reason: MessageTranslation.Failed.Reason,
    ) : TranslateOutcome
}

/**
 * Translates messages on the reader's tap, one at a time, in the order asked (ADR 2026-10.t2h6). The text is held in
 * memory, by message id, for this process only: the spike keeps nothing on the row.
 *
 * Each message goes to the model whole, which reads best, and the output is kept only if every protected span (a link,
 * a position, a mention: [VerbatimSpans]) came back unchanged and [TranslationCheck] passes it. Otherwise the words
 * between the spans are translated one run at a time and the spans put back as they were. The result is screened by
 * [flagged] in the message's scope, as the body was on arrival: the word lists and the toxicity model read English, so a
 * Spanish insult passes them and its English translation may not.
 *
 * Neither the body nor the translation is ever logged: lengths, the direction, the path taken and the time only.
 */
class MessageTranslator(
    private val engine: TranslateEngine,
    private val flagged: suspend (text: String, isRoom: Boolean) -> Boolean,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<Map<String, MessageTranslation>>(emptyMap())

    /** Each message asked for in this process, by message id. */
    val state: StateFlow<Map<String, MessageTranslation>> = _state.asStateFlow()

    private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val serial = Mutex()
    private val lock = Any()

    /** Which language [body] is in, read without its protected spans. */
    suspend fun pick(
        body: String,
        mentions: List<IntRange> = emptyList(),
    ): LanguagePick {
        val words = VerbatimSpans.wordsOnly(body, mentions)
        if (words.isBlank()) return LanguagePick.Unsure
        return LanguagePolicy.pick(engine.detect(words), words)
    }

    /**
     * Queues [body] of message [messageId], read as [from], for translation into the pair's other language. False when
     * it is already running, or its model is not on the phone ([MessageTranslation.NeedsModel]).
     */
    fun translate(
        messageId: String,
        body: String,
        from: TranslateLang,
        isRoom: Boolean,
        mentions: List<IntRange> = emptyList(),
    ): Boolean {
        val direction = TranslateDirection.of(from, from.other) ?: return false
        synchronized(lock) {
            if (_state.value[messageId] is MessageTranslation.Running) return false
            if (!engine.installed(direction)) {
                _state.update { it + (messageId to MessageTranslation.NeedsModel(direction)) }
                return false
            }
            _state.update { it + (messageId to MessageTranslation.Running) }
        }
        workScope.launch(dispatcher) {
            serial.withLock {
                val outcome =
                    runCatching { run(direction, body, isRoom, mentions) }
                        .onFailure { log("translate $direction: ${it.javaClass.simpleName}") }
                        .getOrDefault(MessageTranslation.Failed(MessageTranslation.Failed.Reason.ENGINE))
                _state.update { it + (messageId to outcome) }
            }
        }
        return true
    }

    /**
     * [body], read as [from], into the pair's other language, now: the composer's translation of the person's own draft,
     * which they read and may edit before it is sent (ADR 2026-10.t2h6's amendment). Queued behind any message's, on the
     * same model; not screened, since a send screens what it sends.
     */
    suspend fun translateNow(
        body: String,
        from: TranslateLang,
        mentions: List<IntRange> = emptyList(),
    ): TranslateOutcome {
        val direction =
            TranslateDirection.of(from, from.other) ?: return TranslateOutcome.Failed(MessageTranslation.Failed.Reason.ENGINE)
        if (!engine.installed(direction)) return TranslateOutcome.NeedsModel(direction)
        return withContext(dispatcher) {
            serial.withLock {
                runCatching { core(direction, body, mentions, "draft") }
                    .onFailure { log("translate draft $direction: ${it.javaClass.simpleName}") }
                    .getOrDefault(TranslateOutcome.Failed(MessageTranslation.Failed.Reason.ENGINE))
            }
        }
    }

    /** Forgets [messageId]'s translation: the bubble shows the original alone again. */
    fun dismiss(messageId: String) {
        synchronized(lock) {
            if (_state.value[messageId] is MessageTranslation.Running) return
            _state.update { it - messageId }
        }
    }

    // Under [serial]: a message's translation, screened as its body was.
    private suspend fun run(
        direction: TranslateDirection,
        body: String,
        isRoom: Boolean,
        mentions: List<IntRange>,
    ): MessageTranslation {
        val started = clock()
        return when (val outcome = core(direction, body, mentions, "message")) {
            is TranslateOutcome.Text -> {
                val hidden = flagged(outcome.text, isRoom)
                MessageTranslation.Done(outcome.text, outcome.from, outcome.to, hidden, clock() - started)
            }

            is TranslateOutcome.Failed -> {
                MessageTranslation.Failed(outcome.reason)
            }

            is TranslateOutcome.NeedsModel -> {
                MessageTranslation.NeedsModel(outcome.direction)
            }
        }
    }

    // Under [serial]: whole first, the pieces when the whole lost a span or looped.
    private suspend fun core(
        direction: TranslateDirection,
        body: String,
        mentions: List<IntRange>,
        what: String,
    ): TranslateOutcome {
        val started = clock()
        val spans = VerbatimSpans.spans(body, mentions)
        val whole = engine.translate(direction, body) ?: return failed(direction, what, MessageTranslation.Failed.Reason.ENGINE)
        val wholeKept = spans.all { whole.contains(it) } && TranslationCheck.usable(body, whole)
        val text =
            if (wholeKept) {
                whole
            } else {
                pieces(direction, body, mentions) ?: return failed(direction, what, MessageTranslation.Failed.Reason.ENGINE)
            }
        if (!TranslationCheck.usable(body, text)) return failed(direction, what, MessageTranslation.Failed.Reason.UNUSABLE)
        log(
            "translate $what $direction: in=${body.length} out=${text.length} " +
                "path=${if (wholeKept) "whole" else "pieces"} ms=${clock() - started}",
        )
        return TranslateOutcome.Text(text, direction.from, direction.to)
    }

    private suspend fun pieces(
        direction: TranslateDirection,
        body: String,
        mentions: List<IntRange>,
    ): String? =
        buildString {
            for (piece in VerbatimSpans.pieces(body, mentions)) {
                when (piece) {
                    is BodyPiece.Verbatim -> {
                        append(piece.text)
                    }

                    is BodyPiece.Words -> {
                        // The model trims; the run's own edges are put back so a span keeps its spacing.
                        val core = piece.text.trim()
                        val lead = piece.text.substring(0, piece.text.indexOf(core))
                        val trail = piece.text.substring(lead.length + core.length)
                        append(lead).append(engine.translate(direction, core) ?: return null).append(trail)
                    }
                }
            }
        }

    private fun failed(
        direction: TranslateDirection,
        what: String,
        reason: MessageTranslation.Failed.Reason,
    ): TranslateOutcome {
        log("translate $what $direction: failed, $reason")
        return TranslateOutcome.Failed(reason)
    }
}
