package app.getknit.knit.redact

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The covered copy a direct transfer reads: the name the peer is shown, a folder of its own, a week's keep. */
class CoveredCopiesTest {
    @get:Rule
    val cache = TemporaryFolder()

    @Test
    fun aCopyIsNamedAfterTheOriginalWithTheExtensionOfWhatItWasEncodedAs() {
        assertEquals("IMG_0001.jpg", CoveredCopies.nameFor("IMG_0001.png", "image/jpeg"))
        assertEquals("rally.webp", CoveredCopies.nameFor("rally.heic", "image/webp"))
        assertEquals("march.2026.jpg", CoveredCopies.nameFor("march.2026.jpeg", "image/jpeg"))
        assertEquals("scan.jpg", CoveredCopies.nameFor("scan", "image/jpeg"))
    }

    @Test
    fun aCopyWithNoUsableOriginalNameIsCalledPhoto() {
        for (original in listOf(null, "", "   ", ".jpg", "..", "/")) {
            assertEquals("for \"$original\"", "photo.jpg", CoveredCopies.nameFor(original, "image/jpeg"))
        }
    }

    @Test
    fun aNameCannotLeaveItsFolder() {
        val name = CoveredCopies.nameFor("../../shared_prefs/x.jpg", "image/jpeg")

        assertFalse(name, '/' in name)
    }

    @Test
    fun aNameLongInBytesIsCutToWhatAFileCanBeCalled() {
        val name = CoveredCopies.nameFor("😀".repeat(120) + ".png", "image/jpeg")

        assertTrue(name, name.toByteArray().size <= 255)
        assertTrue(name, name.endsWith("😀.jpg"))
        CoveredCopies.write(cache.root, name, byteArrayOf(1))
    }

    @Test
    fun twoCopiesWithOneNameEachKeepTheirOwnBytes() {
        val first = CoveredCopies.write(cache.root, "photo.jpg", byteArrayOf(1))
        val second = CoveredCopies.write(cache.root, "photo.jpg", byteArrayOf(2))

        assertNotEquals(first, second)
        assertEquals("photo.jpg", second.name)
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test
    fun aDiscardedCopyGoesWithItsFolderAndNothingElseIsTouched() {
        val copy = CoveredCopies.write(cache.root, "photo.jpg", byteArrayOf(1))
        val outside = cache.newFile("picked.jpg")

        assertTrue(CoveredCopies.discard(cache.root, copy))
        assertFalse(copy.parentFile!!.exists())
        assertFalse(CoveredCopies.discard(cache.root, outside))
        assertTrue(outside.exists())
        // Read as written, this path's folder's folder is the copies' own: only its real path says it is the cache
        // itself, which a recursive delete would empty. Such a delete takes the picked file or another copy first,
        // whichever the folder lists first, so both are checked.
        val other = CoveredCopies.write(cache.root, "other.jpg", byteArrayOf(2))
        assertFalse(CoveredCopies.discard(cache.root, File(File(cache.root, CoveredCopies.DIR), "../picked.jpg")))
        assertTrue("the picked file was deleted", outside.exists())
        assertTrue("another copy was deleted", other.exists())
    }

    @Test
    fun pruningAloneDeletesCopiesOlderThanAWeek() {
        val now = 100L * CoveredCopies.KEEP_MS
        val old = CoveredCopies.write(cache.root, "old.jpg", byteArrayOf(1), now = now)
        old.parentFile!!.setLastModified(now - CoveredCopies.KEEP_MS - 1)

        CoveredCopies.prune(cache.root, now)

        assertFalse(old.parentFile!!.exists())
    }

    @Test
    fun writingACopyDeletesOnlyCopiesOlderThanAWeek() {
        val now = 100L * CoveredCopies.KEEP_MS
        val old = CoveredCopies.write(cache.root, "old.jpg", byteArrayOf(1), now = now)
        old.parentFile!!.setLastModified(now - CoveredCopies.KEEP_MS - 1)
        val kept = CoveredCopies.write(cache.root, "kept.jpg", byteArrayOf(2), now = now)
        kept.parentFile!!.setLastModified(now - CoveredCopies.KEEP_MS)

        CoveredCopies.write(cache.root, "new.jpg", byteArrayOf(3), now = now)

        assertFalse("a copy past a week is kept", old.parentFile!!.exists())
        assertTrue("a copy a week old is gone", kept.exists())
    }
}
