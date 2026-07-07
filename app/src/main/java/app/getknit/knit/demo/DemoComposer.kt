package app.getknit.knit.demo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Debug trailer seam: scripted commands that drive the **real** Nearby composer so the promo trailer shows
 * the local user typing a message and sending it, rather than a bubble simply materializing. The animated
 * [app.getknit.knit.demo.DemoDirector] (debug-only) emits commands; a `BuildConfig.DEMO_DIRECTOR`-gated
 * `LaunchedEffect` in `ChatScreen` collects them, types into the field char-by-char (which fires the real
 * "now typing" cue), then routes through the real send path.
 *
 * Lives in `src/main` (like [app.getknit.knit.ui.share.ShareInbox]) so `ChatScreen` can reference it in
 * every variant; it is completely inert unless something emits, which only the debug director ever does, so
 * it is dead weight R8 strips from release. Registered as a Koin singleton in `AppModule`.
 */
class DemoComposer {
    private val _commands = MutableSharedFlow<DemoComposeCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<DemoComposeCommand> = _commands.asSharedFlow()

    /** Type [text] into the Nearby composer, one character every [perCharMs] ms. */
    suspend fun type(
        text: String,
        perCharMs: Long = DEFAULT_PER_CHAR_MS,
    ) = _commands.emit(DemoComposeCommand.Type(text, perCharMs))

    /** Fire the send button on whatever is currently in the composer. */
    suspend fun send() = _commands.emit(DemoComposeCommand.Send)

    private companion object {
        const val DEFAULT_PER_CHAR_MS = 55L
    }
}

/** A single scripted composer action (see [DemoComposer]). */
sealed interface DemoComposeCommand {
    data class Type(
        val text: String,
        val perCharMs: Long,
    ) : DemoComposeCommand

    data object Send : DemoComposeCommand
}
