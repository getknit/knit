@file:OptIn(ExperimentalCoroutinesApi::class) // runCurrent is an experimental kotlinx API

package app.getknit.knit.ui.devview

import app.getknit.knit.fieldlog.BtDownReason
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * The developer view's event console (ADR 2026-10.s7wg, its slice-2 amendment): nothing kept while the view is off, the
 * newest events kept while it is on, emptied when it goes off; each event's plane; and its fields as the screen reads
 * them, a peer by its label and a relay by its host.
 */
class EventConsoleTest {
    private fun down(peer: String = PEER) = FieldEvent.BtDown(peer, BtDownReason.EOF, lifetimeMs = 5_000, links = 0)

    @Test
    fun aConsoleThatIsOffKeepsNothing() {
        val console = EventConsole()

        console.saw(1L, FieldEvent.MeshOn(34))

        assertEquals(emptyList<ConsoleEvent>(), console.snapshot())
    }

    @Test
    fun anOnConsoleKeepsTheNewestFirstAndDropsTheOldestPastItsCapacity() {
        val console = EventConsole(capacity = 3)
        console.setActive(true)

        (1L..5L).forEach { console.saw(it, FieldEvent.MeshOn(it)) }

        assertEquals(listOf(5L, 4L, 3L), console.snapshot().map { it.at })
    }

    @Test
    fun switchingTheViewOffEmptiesItAndKeepsNothingMore() {
        val console = EventConsole()
        console.setActive(true)
        console.saw(1L, down())

        console.setActive(false)
        console.saw(2L, down())

        assertEquals(emptyList<ConsoleEvent>(), console.snapshot())
    }

    /**
     * `follow` is the app's one path from the developer view's switch to the console (`DevViewWiring`), so it carries
     * the privacy rule itself: kept while on, emptied and keeping nothing once off.
     */
    @Test
    fun followingTheSwitchKeepsWhileOnThenEmptiesAndKeepsNothingOnceOff() =
        runTest {
            val on = MutableStateFlow(true)
            val console = EventConsole()
            console.follow(on, backgroundScope)
            runCurrent()
            console.saw(1L, down())
            assertEquals("the switch's on was followed", 1, console.snapshot().size)

            on.value = false
            runCurrent()
            console.saw(2L, down())

            assertEquals(emptyList<ConsoleEvent>(), console.snapshot())
        }

    @Test
    fun everyChangeMovesTheVersionAReaderSnapshotsOn() {
        val console = EventConsole()
        val start = console.version.value
        console.setActive(true)
        val on = console.version.value
        console.saw(1L, down())
        val saw = console.version.value
        console.clear()

        assertNotEquals(start, on)
        assertNotEquals(on, saw)
        assertNotEquals(saw, console.version.value)
        assertEquals(emptyList<ConsoleEvent>(), console.snapshot())
    }

    @Test
    fun eachKindFallsUnderItsPlaneAndARadioStatusUnderItsRadio() {
        fun radio(kind: TransportKind) =
            FieldEvent.Radio(kind, TransportHealth.Healthy, linked = 1, nearby = 2, contended = false, initiatorHeld = false)

        assertEquals(ConsolePlane.Bluetooth, ConsolePlane.of(down()))
        assertEquals(ConsolePlane.Bluetooth, ConsolePlane.of(radio(TransportKind.Bluetooth)))
        assertEquals(ConsolePlane.WifiAware, ConsolePlane.of(radio(TransportKind.WifiAware)))
        assertEquals(ConsolePlane.WifiAware, ConsolePlane.of(FieldEvent.AwareSwitch(false)))
        assertEquals(ConsolePlane.LoRa, ConsolePlane.of(radio(TransportKind.LoRa)))
        assertEquals(ConsolePlane.Mesh, ConsolePlane.of(radio(TransportKind.Other)))
        assertEquals(ConsolePlane.Relay, ConsolePlane.of(spoolUp()))
        assertEquals(ConsolePlane.Mesh, ConsolePlane.of(FieldEvent.MeshOn(34)))
        assertEquals(ConsolePlane.Mesh, ConsolePlane.of(FieldEvent.MeshOff))
    }

    @Test
    fun theFieldsReadInTheEventsOwnOrderAPeerByItsLabelAndARelayByItsHost() {
        val label = { id: String -> ConsoleLine.peer(if (id == PEER) "Kai" else "?", id) }

        assertEquals("peer=Kai (hzwyqdbj) reason=EOF life=5000 links=0", ConsoleLine.fields(down(), label))
        assertEquals(
            "relay=relay.example hello=412 fails=0 pow=8 attach=true ent=false turn=false cast=false",
            ConsoleLine.fields(spoolUp(), label),
        )
        assertEquals("", ConsoleLine.fields(FieldEvent.MeshOff, label))
    }

    @Test
    fun aRelayWithNoHostShowsItsUrlAndATimeReadsToTheMillisecond() {
        assertEquals("not a url", ConsoleLine.host("not a url"))
        assertEquals("22:13:20.007", ConsoleLine.time(1_700_000_000_007L, ZoneOffset.UTC))
    }

    private fun spoolUp() =
        FieldEvent.SpoolUp(
            relay = "wss://relay.example/spool/v1",
            helloMs = 412,
            fails = 0,
            powBits = 8,
            attachments = true,
            ent = false,
            turn = false,
            cast = false,
        )

    private companion object {
        const val PEER = "hzwyqdbj74hu6gkurmcyxomaca"
    }
}
