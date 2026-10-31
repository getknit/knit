package app.getknit.knit.mesh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PartialBlobs]: the prefixes of attachments a link drop cut off, kept so the next ask resumes where the bytes
 * stopped (work item #116, ADR 2026-10.wtyc).
 */
class PartialBlobsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun store(
        minBytes: Long = 1,
        maxCount: Int = PartialBlobs.MAX_COUNT,
        maxBytes: Long = PartialBlobs.MAX_BYTES,
        dir: String = "parts",
    ) = PartialBlobs(File(tmp.root, dir), now = { now }, minBytes = minBytes, maxCount = maxCount, maxBytes = maxBytes)

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    /** A link's temp file holding the first [n] bytes of the fixture blob. */
    private fun cut(n: Int): File = tmp.newFile().apply { writeBytes(bytes(n)) }

    @Test
    fun theLongestPrefixIsKeptAndAShorterOneIsDeleted() {
        val partials = store()
        assertTrue(partials.keep(a, cut(100), basedOn = null))
        assertEquals(100L, partials.length(a))
        val shorter = cut(50)
        assertFalse("a shorter cut never replaces a longer prefix", partials.keep(a, shorter, basedOn = null))
        assertFalse("and is deleted, not left in the cache", shorter.exists())
        assertEquals(100L, partials.length(a))
        assertTrue(partials.keep(a, cut(200), basedOn = null))
        assertEquals(200L, partials.length(a))
    }

    @Test
    fun aPrefixBelowTheMinimumIsNotWorthAFile() {
        val partials = store(minBytes = 64)
        val small = cut(63)
        assertFalse(partials.keep(a, small, basedOn = null))
        assertFalse(small.exists())
        assertEquals(0L, partials.length(a))
    }

    @Test
    fun anOpenPrefixReadsTheKeptBytesFromTheStart() {
        val partials = store()
        partials.keep(a, cut(1000), basedOn = null)
        val prefix = checkNotNull(partials.open(a, 600))
        val read = prefix.use { it.input.readNBytes(600) }
        assertArrayEquals(bytes(1000).copyOf(600), read)
        assertNotNull("the whole prefix is an offset too: a complete file gets an empty tail", partials.open(a, 1000)?.also { it.close() })
        assertNull("more than is kept", partials.open(a, 1001))
        assertNull("an offset below one is no resume", partials.open(a, 0))
        assertNull("nothing kept", partials.open(b, 1))
    }

    @Test
    fun aPrefixDroppedWhileACopyWasBuiltOnItIsNeverKeptAgain() {
        // Two links resumed from one prefix; one spliced file failed its hash and dropped it. The other, cut later,
        // must not bring the bad prefix back — but a stream from byte 0 owes it nothing.
        val partials = store()
        partials.keep(a, cut(100), basedOn = null)
        val epoch = checkNotNull(partials.open(a, 100)).also { it.close() }.epoch
        assertTrue(partials.drop(a))
        val stale = cut(300)
        assertFalse(partials.keep(a, stale, basedOn = epoch))
        assertFalse(stale.exists())
        assertEquals(0L, partials.length(a))
        assertTrue("a whole-file stream is eligible", partials.keep(a, cut(150), basedOn = null))
    }

    @Test
    fun aCopyOnTheCurrentPrefixIsKeptWhenItGotFurther() {
        val partials = store()
        partials.keep(a, cut(100), basedOn = null)
        val epoch = checkNotNull(partials.open(a, 100)).also { it.close() }.epoch
        assertTrue(partials.keep(a, cut(400), basedOn = epoch))
        assertEquals(400L, partials.length(a))
        // An eviction is not a verdict on the bytes: a copy built before it may still be kept.
        val evicting = store(maxCount = 1, dir = "evicting")
        evicting.keep(a, cut(100), basedOn = null)
        val held = checkNotNull(evicting.open(a, 100)).also { it.close() }.epoch
        evicting.keep(b, cut(100), basedOn = null)
        assertEquals("a was evicted for b", 0L, evicting.length(a))
        assertTrue(evicting.keep(a, cut(300), basedOn = held))
    }

    @Test
    fun aKeptPrefixIsNeverWrittenToSoAnOpenReaderKeepsItsBytes() {
        val partials = store()
        partials.keep(a, cut(100), basedOn = null)
        val prefix = checkNotNull(partials.open(a, 100))
        partials.keep(a, tmp.newFile().apply { writeBytes(ByteArray(500) { 7 }) }, basedOn = null) // replaced by rename
        val read = prefix.use { it.input.readBytes() }
        assertArrayEquals("the reader reads the bytes it opened", bytes(100), read)
    }

    @Test
    fun aMalformedKeyNeverNamesAFile() {
        val partials = store()
        val evil = cut(100)
        assertFalse(partials.keep("../../escape", evil, basedOn = null))
        assertFalse(evil.exists())
        assertFalse(partials.keep("A".repeat(64), cut(100), basedOn = null))
        assertTrue("nothing written anywhere", File(tmp.root, "parts").listFiles().isNullOrEmpty())
        assertTrue(
            tmp.root.parentFile!!
                .listFiles()!!
                .none { it.name == "escape" },
        )
    }

    @Test
    fun theOldestGoesFirstPastTheCountAndTheBytes() {
        val counted = store(maxCount = 2, dir = "counted")
        counted.keep(a, cut(10), basedOn = null)
        counted.keep(b, cut(10), basedOn = null)
        counted.keep(c, cut(10), basedOn = null)
        assertEquals(listOf(0L, 10L, 10L), listOf(a, b, c).map(counted::length))

        val sized = store(maxBytes = 250, dir = "sized")
        sized.keep(a, cut(100), basedOn = null)
        sized.keep(b, cut(100), basedOn = null)
        sized.keep(c, cut(100), basedOn = null)
        assertEquals(listOf(0L, 100L, 100L), listOf(a, b, c).map(sized::length))
        assertEquals("the evicted file went with it", 2, File(tmp.root, "sized").listFiles()!!.size)
    }

    @Test
    fun aSweepDropsWhatAgedPastTheHourAndWhatArrivedSomeOtherWay() =
        runTest {
            val partials = store()
            partials.keep(a, cut(10), basedOn = null)
            now += PartialBlobs.TTL_MS / 2
            partials.keep(b, cut(10), basedOn = null)
            partials.keep(c, cut(10), basedOn = null)
            now += PartialBlobs.TTL_MS / 2 + 1
            assertEquals(2, partials.sweep { it == c })
            assertEquals("past its hour", 0L, partials.length(a))
            assertEquals("still young, still wanted", 10L, partials.length(b))
            assertEquals("held now: nothing to resume", 0L, partials.length(c))
        }

    @Test
    fun aPurgeDeletesEveryPrefixAndADeadSessionsLeftovers() {
        val partials = store()
        partials.keep(a, cut(100), basedOn = null)
        val epoch = checkNotNull(partials.open(a, 100)).also { it.close() }.epoch
        File(tmp.root, "parts/$b.part").writeBytes(bytes(10)) // a crashed process's prefix
        partials.purge()
        assertTrue(File(tmp.root, "parts").listFiles().isNullOrEmpty())
        assertEquals(0L, partials.length(a))
        assertFalse("a resume begun before the purge keeps nothing", partials.keep(a, cut(200), basedOn = epoch))
    }

    @Test
    fun aDropSaysWhetherAPrefixWentAndAMissingFileIsForgotten() {
        val partials = store()
        assertFalse(partials.drop(a))
        partials.keep(a, cut(100), basedOn = null)
        File(tmp.root, "parts/$a.part").delete() // the cache was cleared from under us
        assertNull(partials.open(a, 50))
        assertEquals(0L, partials.length(a))
    }

    @Test
    fun holderMarksAreBoundedPerHashAndEverythingGoesAtTheNextStart() {
        // #234: a holder id is a neighbour's claim, so the marks one hash keeps are capped, oldest out first.
        val partials = store()
        partials.cap(a, 1_000)
        assertTrue(partials.markHolder(a, "n0"))
        assertFalse("a second mark of the same holder is not new", partials.markHolder(a, "n0"))
        (1..20).forEach { partials.markHolder(a, "n$it") }
        val marked = partials.markedHolders(a)
        assertEquals(16, marked.size)
        assertFalse("the oldest fell out", "n0" in marked)
        assertTrue("n20" in marked)
        partials.refuse(b)
        assertTrue(partials.isRefused(b))
        assertFalse(partials.isRefused(a))

        partials.purge()

        assertNull(partials.capFor(a))
        assertTrue(partials.markedHolders(a).isEmpty())
        assertFalse(partials.isRefused(b))
    }
}
