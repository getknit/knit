@file:Suppress("MagicNumber") // stamps are the test's subject

package app.getknit.knit.usbcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class NewHeadsTest {
    private data class Head(
        val id: String,
        val stamp: Long,
    )

    private fun heads(
        since: Long = 100,
        memory: Int = 4096,
    ) = NewHeads<Head>(since = since, idOf = { it.id }, stampOf = { it.stamp }, memory = memory)

    @Test
    fun theFirstSnapshotIsTheBaseline() {
        assertEquals(emptyList<Head>(), heads().next(listOf(Head("a", 150), Head("b", 50))))
    }

    @Test
    fun aNewHeadYieldsOnce() {
        val feed = heads()
        feed.next(listOf(Head("a", 50)))
        assertEquals(listOf(Head("c", 120)), feed.next(listOf(Head("c", 120))))
        assertEquals(emptyList<Head>(), feed.next(listOf(Head("c", 120))))
    }

    @Test
    fun aHeadStampedAtTheSessionStartIsNews() {
        val feed = heads(since = 100)
        feed.next(emptyList())
        assertEquals(listOf(Head("edge", 100)), feed.next(listOf(Head("edge", 100))))
    }

    @Test
    fun aHeadFromBeforeTheSessionIsNotNews() {
        // The thread's newest row was deleted, so an older message became its head again.
        val feed = heads()
        feed.next(listOf(Head("a", 150)))
        assertEquals(emptyList<Head>(), feed.next(listOf(Head("old", 40))))
    }

    @Test
    fun severalNewHeadsComeInStampOrder() {
        val feed = heads()
        feed.next(emptyList())
        assertEquals(
            listOf(Head("x", 110), Head("y", 130)),
            feed.next(listOf(Head("y", 130), Head("x", 110))),
        )
    }

    @Test
    fun theOldestSeenIdIsForgottenPastTheBound() {
        val feed = heads(memory = 2)
        feed.next(listOf(Head("a", 150)))
        feed.next(listOf(Head("b", 160), Head("c", 170)))
        // "b" and "c" are still inside the bound; only the oldest, "a", was forgotten.
        assertEquals(emptyList<Head>(), feed.next(listOf(Head("b", 160), Head("c", 170))))
        assertEquals(listOf(Head("a", 150)), feed.next(listOf(Head("a", 150))))
    }
}
