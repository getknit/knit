package app.getknit.knit.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Redaction's phase 2: the names a dying process could not read are removed on the way to the screen and
 * the share sheet. Every name this device could show for someone — a stored peer name, the alias a nameless
 * peer is shown under, the user's own name and alias — must be gone from what [CrashReports.read] and
 * [CrashReports.exportForShare] hand out, and when the name list cannot be read the footer must say so
 * rather than claim a guarantee it did not deliver. (The rules themselves are `CrashRedactorTest`'s.)
 */
@RunWith(AndroidJUnit4::class)
class CrashReportsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "crash-store-${System.nanoTime()}")
    private val store = CrashStore(dir) { 1_700_000_000_000L }
    private val identity = mockk<Identity> { coEvery { nodeId() } returns ME }
    private val settings = mockk<SettingsStore> { every { displayName } returns flowOf("Wilhelmina") }
    private val namedPeer = PeerEntity(nodeId = NAMED, name = "Rosalind")
    private val namelessPeer = PeerEntity(nodeId = NAMELESS)

    @After
    fun tearDown() {
        dir.deleteRecursively()
        File(context.cacheDir, "crash").deleteRecursively()
    }

    private fun reports(peers: PeerRepository = mockk { every { observePeers() } returns flowOf(listOf(namedPeer, namelessPeer)) }) =
        CrashReports(context, store, identity, peers, settings)

    /** A crash whose message names everyone this device knows, the way a stray `toString` would. */
    private fun recordCrash() {
        val names = listOf("Rosalind", Alias.aliasFor(NAMELESS), "Wilhelmina", Alias.aliasFor(ME))
        store.record(testEnvironment(), "main", throwableWith("sent to ${names.joinToString(" and ")} failed"))
    }

    private fun assertNamesGone(text: String) {
        for (name in listOf("Rosalind", Alias.aliasFor(NAMELESS), "Wilhelmina", Alias.aliasFor(ME))) {
            assertFalse("$name leaked:\n$text", text.contains(name))
        }
        assertTrue(text.contains("names known to this device were removed"))
    }

    @Test
    fun readRemovesEveryNameThisDeviceKnows() =
        runTest {
            recordCrash()
            val reports = reports()
            val text = reports.read(checkNotNull(reports.latest()))!!
            assertNamesGone(text)
            assertTrue(text.contains("sent to"))
        }

    @Test
    fun theSharedCopyIsRedactedToo() =
        runTest {
            recordCrash()
            val reports = reports()
            val uri = reports.exportForShare(checkNotNull(reports.latest()))
            assertNotNull(uri)
            val shared = File(context.cacheDir, "crash").listFiles()!!.single()
            assertNamesGone(shared.readText())
        }

    @Test
    fun aShareReplacesTheLastStagedCopy() =
        runTest {
            recordCrash()
            val stale = File(context.cacheDir, "crash").apply { mkdirs() }.resolve("knit-crash-old.txt")
            stale.writeText("redacted against an older name list: Rosalind")
            val reports = reports()
            reports.exportForShare(checkNotNull(reports.latest()))
            assertFalse(stale.exists())
            assertEquals(1, File(context.cacheDir, "crash").listFiles()!!.size)
        }

    @Test
    fun aNameListThatCannotBeReadIsSaidSo() =
        runTest {
            recordCrash()
            val broken = mockk<PeerRepository> { every { observePeers() } returns flow { error("database locked") } }
            val reports = reports(broken)
            val text = reports.read(checkNotNull(reports.latest()))!!
            assertTrue(text.contains("redaction: structural only"))
            assertFalse(text.contains("names known to this device were removed"))
        }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NAMED = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val NAMELESS = "cccccccccccccccccccccccccc"
    }
}
