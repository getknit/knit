package app.getknit.knit.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message translated on tap (ADR 2026-10.t2h6): whole first, by pieces when the whole lost a span or looped, refused
 * when it loops anyway, screened in the message's scope, one at a time, and never logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageTranslatorTest {
    private val logs = mutableListOf<String>()

    // The rig's clock: only a fake engine's answer moves it, as a model's time would.
    private var now = 0L
    private val screened = mutableListOf<Pair<String, Boolean>>()

    private class FakeEngine(
        var installed: Set<TranslateDirection> = TranslateDirection.ALL.toSet(),
        var detection: Detection? = null,
        val answer: suspend (TranslateDirection, String) -> String?,
    ) : TranslateEngine {
        val asked = mutableListOf<String>()
        val detected = mutableListOf<String>()
        var running = 0
        var mostRunning = 0

        override fun installed(direction: TranslateDirection) = direction in installed

        val hints = mutableListOf<TranslateLang?>()

        override suspend fun detect(
            text: String,
            hint: TranslateLang?,
        ): Detection? {
            detected += text
            hints += hint
            return detection
        }

        override suspend fun translate(
            direction: TranslateDirection,
            text: String,
        ): String? {
            asked += text
            mostRunning = maxOf(mostRunning, ++running)
            try {
                return answer(direction, text)
            } finally {
                running--
            }
        }
    }

    private fun TestScope.translator(
        engine: TranslateEngine,
        flagged: Boolean = false,
        authors: AuthorLanguages? = null,
    ): MessageTranslator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessageTranslator(
            engine = engine,
            flagged = { text, isRoom ->
                screened += text to isRoom
                flagged
            },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            clock = { now },
            log = { logs += it },
            authors = authors,
        )
    }

    @Test
    fun aMessageIsTranslatedWholeAndScreenedInItsScope() =
        runTest {
            val engine =
                FakeEngine { _, _ ->
                    now += 40
                    "Are you coming to the park later?"
                }
            val t = translator(engine)
            assertTrue(t.translate("m1", "¿Vienes al parque más tarde?", TranslateLang.ES, isRoom = true))
            assertEquals(MessageTranslation.Running, t.state.value["m1"])
            advanceUntilIdle()
            assertEquals(
                MessageTranslation.Done("Are you coming to the park later?", TranslateLang.ES, TranslateLang.EN, flagged = false, ms = 40),
                t.state.value["m1"],
            )
            assertEquals(listOf("¿Vienes al parque más tarde?"), engine.asked)
            assertEquals(listOf("Are you coming to the park later?" to true), screened)
        }

    @Test
    fun aFlaggedTranslationSaysSo() =
        runTest {
            val t = translator(FakeEngine { _, _ -> "an insult" }, flagged = true)
            t.translate("m1", "un insulto", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            assertTrue((t.state.value["m1"] as MessageTranslation.Done).flagged)
            assertEquals(listOf("an insult" to false), screened)
        }

    @Test
    fun aWholeTranslationThatLostALinkIsRedoneByPiecesAroundIt() =
        runTest {
            val engine =
                FakeEngine { _, text ->
                    when (text) {
                        "mira esto https://getknit.app/ ahora" -> "look at this getknit.app now"
                        "mira esto" -> "look at this"
                        "ahora" -> "now"
                        else -> error("unexpected $text")
                    }
                }
            val t = translator(engine)
            t.translate("m1", "mira esto https://getknit.app/ ahora", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            assertEquals("look at this https://getknit.app/ now", (t.state.value["m1"] as MessageTranslation.Done).text)
            assertEquals(listOf("mira esto https://getknit.app/ ahora", "mira esto", "ahora"), engine.asked)
        }

    @Test
    fun aLoopWholeAndByPiecesIsRefused() =
        runTest {
            val t = translator(FakeEngine { _, _ -> "¡".repeat(23) })
            t.translate("m1", "congrats!! so happy for you", TranslateLang.EN, isRoom = false)
            advanceUntilIdle()
            assertEquals(MessageTranslation.Failed(MessageTranslation.Failed.Reason.UNUSABLE), t.state.value["m1"])
            assertTrue(screened.isEmpty())
        }

    @Test
    fun aModelThatWillNotRunFails() =
        runTest {
            val t = translator(FakeEngine { _, _ -> null })
            t.translate("m1", "hola", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            assertEquals(MessageTranslation.Failed(MessageTranslation.Failed.Reason.ENGINE), t.state.value["m1"])
        }

    @Test
    fun aMissingModelRunsNothingAndNamesTheDirection() =
        runTest {
            val engine = FakeEngine(installed = setOf(TranslateDirection.EN_ES)) { _, _ -> "x" }
            val t = translator(engine)
            assertFalse(t.translate("m1", "hola", TranslateLang.ES, isRoom = false))
            advanceUntilIdle()
            assertEquals(MessageTranslation.NeedsModel(TranslateDirection.ES_EN), t.state.value["m1"])
            assertTrue(engine.asked.isEmpty())
        }

    @Test
    fun messagesRunOneAtATimeAndASecondTapOnARunningOneIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val engine =
                FakeEngine { _, text ->
                    if (text == "uno") gate.await()
                    text.uppercase()
                }
            val t = translator(engine)
            assertTrue(t.translate("m1", "uno", TranslateLang.ES, isRoom = false))
            assertTrue(t.translate("m2", "dos", TranslateLang.ES, isRoom = false))
            advanceUntilIdle()
            // m1 holds the engine; m2 waits its turn without reaching it.
            assertEquals(listOf("uno"), engine.asked)
            assertFalse(t.translate("m1", "uno", TranslateLang.ES, isRoom = false))
            // Running is not dismissed.
            t.dismiss("m1")
            assertEquals(MessageTranslation.Running, t.state.value["m1"])
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("uno", "dos"), engine.asked)
            assertEquals(1, engine.mostRunning)
            assertEquals("UNO", (t.state.value["m1"] as MessageTranslation.Done).text)
            assertEquals("DOS", (t.state.value["m2"] as MessageTranslation.Done).text)
        }

    @Test
    fun aDismissedTranslationIsForgotten() =
        runTest {
            val t = translator(FakeEngine { _, _ -> "hello" })
            t.translate("m1", "hola", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            t.dismiss("m1")
            assertFalse("m1" in t.state.value)
        }

    @Test
    fun neitherTheBodyNorTheTranslationIsLogged() =
        runTest {
            val t = translator(FakeEngine { _, _ -> "secret plans" })
            t.translate("m1", "planes secretos", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { "secret" in it || "planes" in it })
        }

    @Test
    fun theLanguageIsReadWithoutTheLinks() =
        runTest {
            val engine = FakeEngine(detection = Detection(listOf("es" to 96, "un" to 0, "un" to 0), true, 9)) { _, _ -> "x" }
            val t = translator(engine)
            assertEquals(LanguagePick.Picked(TranslateLang.ES), t.pick("mira esto https://getknit.app/"))
            assertEquals(listOf("mira esto"), engine.detected)
            // Nothing but a link: nothing to read, and CLD2 is not asked.
            assertEquals(LanguagePick.Unsure, t.pick("https://getknit.app/"))
            assertEquals(listOf("mira esto"), engine.detected)
        }

    @Test
    fun aDraftIsTranslatedNowAndNotScreened() =
        runTest {
            val engine = FakeEngine { _, _ -> "Are you coming?" }
            val t = translator(engine, flagged = true)
            assertEquals(
                TranslateOutcome.Text("Are you coming?", TranslateLang.ES, TranslateLang.EN),
                t.translateNow("¿Vienes?", TranslateLang.ES),
            )
            assertTrue(screened.isEmpty())
            // A draft is the person's own: nothing of it lands in the messages' state.
            assertTrue(t.state.value.isEmpty())
        }

    @Test
    fun aDraftKeepsItsSpansAndRefusesALoop() =
        runTest {
            val engine =
                FakeEngine { _, text ->
                    when (text) {
                        "hola @Ana" -> "hello Ana"
                        "hola" -> "hello"
                        else -> "¡".repeat(23)
                    }
                }
            val t = translator(engine)
            assertEquals(
                TranslateOutcome.Text("hello @Ana", TranslateLang.ES, TranslateLang.EN),
                t.translateNow("hola @Ana", TranslateLang.ES, listOf(5 until 9)),
            )
            assertEquals(
                TranslateOutcome.Failed(MessageTranslation.Failed.Reason.UNUSABLE),
                t.translateNow("congrats!! so happy", TranslateLang.EN),
            )
        }

    @Test
    fun aDraftWithoutItsModelNamesTheDirection() =
        runTest {
            val engine = FakeEngine(installed = emptySet()) { _, _ -> "x" }
            val t = translator(engine)
            assertEquals(TranslateOutcome.NeedsModel(TranslateDirection.EN_ES), t.translateNow("hello there", TranslateLang.EN))
            assertTrue(engine.asked.isEmpty())
        }

    @Test
    fun aDraftWaitsItsTurnBehindAMessage() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val engine =
                FakeEngine { _, text ->
                    if (text == "uno") gate.await()
                    text.uppercase()
                }
            val t = translator(engine)
            t.translate("m1", "uno", TranslateLang.ES, isRoom = false)
            advanceUntilIdle()
            val draft = async { t.translateNow("dos", TranslateLang.ES) }
            advanceUntilIdle()
            // The message holds the engine; the draft has not reached it.
            assertEquals(listOf("uno"), engine.asked)
            assertFalse(draft.isCompleted)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(TranslateOutcome.Text("DOS", TranslateLang.ES, TranslateLang.EN), draft.await())
            assertEquals(1, engine.mostRunning)
        }

    private val unread = Detection(listOf("un" to 0, "un" to 0, "un" to 0), false, 6)

    private fun authorWrites(lang: TranslateLang?) =
        AuthorLanguages(
            bodies = { _, _, _ -> if (lang == null) emptyList() else List(3) { "x" } },
            read = { if (lang == null) LanguagePick.Unsure else LanguagePick.Picked(lang) },
            clock = { 0L },
        )

    @Test
    fun aShortLineTakesItsAuthorsLanguageAndHintsTheIdentifierWithIt() =
        runTest {
            val engine = FakeEngine(detection = unread) { _, _ -> "x" }
            val t = translator(engine, authors = authorWrites(TranslateLang.ES))
            assertEquals(
                Resolution(LanguagePick.Picked(TranslateLang.ES), LanguageSource.AUTHOR),
                t.resolve("ya voy", emptyList(), "g1", "lucia", chat = TranslateLang.EN),
            )
            assertEquals(listOf<TranslateLang?>(TranslateLang.ES), engine.hints)
        }

    @Test
    fun anAuthorWithNoUsualLanguageFallsToTheChats() =
        runTest {
            val engine = FakeEngine(detection = unread) { _, _ -> "x" }
            val t = translator(engine, authors = authorWrites(null))
            assertEquals(
                Resolution(LanguagePick.Picked(TranslateLang.EN), LanguageSource.CHAT),
                t.resolve("omg yes", emptyList(), "g1", "sam", chat = TranslateLang.EN),
            )
            assertEquals(listOf<TranslateLang?>(TranslateLang.EN), engine.hints)
        }

    @Test
    fun aMessageOfLinksAloneHasNothingToTranslateWhateverItsAuthorWrites() =
        runTest {
            val engine = FakeEngine(detection = unread) { _, _ -> "x" }
            val t = translator(engine, authors = authorWrites(TranslateLang.ES))
            assertEquals(Resolution(LanguagePick.Unsure, null), t.resolve("https://getknit.app/", emptyList(), "g1", "lucia", null))
            assertTrue(engine.detected.isEmpty())
        }
}
