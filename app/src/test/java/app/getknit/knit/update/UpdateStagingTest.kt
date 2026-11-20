package app.getknit.knit.update

import android.content.pm.PackageInstaller
import app.getknit.knit.mesh.protocol.FrameId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The staging a peer update waits in (ADR 2026-11.29m7), the status receiver's verdicts, and the relaunch rule: what
 * names a path, what is ever installed, and which status Knit acts on.
 */
class UpdateStagingTest {
    private val root = Files.createTempDirectory("update").toFile()
    private var now = 1_700_000_000_000L
    private val staging = UpdateStaging(root) { now }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun onlyACanonicalTransferIdNamesADirectory() {
        assertNull(staging.dirFor("../outside"))
        assertNull(staging.dirFor("a/b"))
        assertNull(staging.dirFor(""))
        val id = FrameId.new()
        assertEquals(File(root, id), staging.dirFor(id))
    }

    @Test
    fun nothingIsInstallableUntilItsSinkCommitted() {
        val id = FrameId.new()
        val apk = checkNotNull(staging.incomingApk(id))
        apk.parentFile!!.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(staging.stagedApk(id))
        assertTrue(staging.committedIds().isEmpty())
        assertTrue(staging.markCommitted(id))
        assertEquals(apk, staging.stagedApk(id))
        assertEquals(setOf(id), staging.committedIds())
        staging.discard(id)
        assertNull(staging.stagedApk(id))
        assertFalse(File(root, id).exists())
    }

    @Test
    fun theSweepDropsWhatNeverCommittedAndWhatWaitedTooLong() {
        val torn = FrameId.new()
        staging.incomingApk(torn)!!.apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
        val fresh = FrameId.new()
        staging.incomingApk(fresh)!!.apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(2))
        staging.markCommitted(fresh)
        staging.writePending(fresh, 21_400_900L, sessionId = 5)
        staging.sweep(keepMs = 60_000L)
        assertFalse(File(root, torn).exists())
        assertEquals(setOf(fresh), staging.committedIds())
        assertEquals(fresh, staging.readPending()?.transferId)
        now += 10 * 60_000L
        staging.sweep(keepMs = 60_000L)
        assertTrue(staging.committedIds().isEmpty())
    }

    @Test
    fun thePendingInstallRoundTripsAndAForgedOneReadsAsNone() {
        val id = FrameId.new()
        staging.writePending(id, 21_400_900L, sessionId = 42)
        assertEquals(PendingInstall(id, 21_400_900L, 42, now), staging.readPending())
        File(root, "pending").writeText("../x 1 2 3")
        assertNull(staging.readPending())
        File(root, "pending").writeText("garbage")
        assertNull(staging.readPending())
        staging.clearPending()
        assertNull(staging.readPending())
    }

    @Test
    fun aStatusCountsOnlyForTheSessionKnitRecorded() {
        val pending = PendingInstall(FrameId.new(), 21_400_900L, sessionId = 7, at = now)
        val confirm = InstallStatusPolicy.CONFIRM_INSTALL
        assertEquals(
            InstallStatusPolicy.Action.Confirm,
            InstallStatusPolicy.decide(PackageInstaller.STATUS_PENDING_USER_ACTION, 7, confirm, pending),
        )
        // A forged status: another session, or none in flight, does nothing at all.
        assertEquals(
            InstallStatusPolicy.Action.Ignore,
            InstallStatusPolicy.decide(PackageInstaller.STATUS_PENDING_USER_ACTION, 8, confirm, pending),
        )
        assertEquals(InstallStatusPolicy.Action.Ignore, InstallStatusPolicy.decide(PackageInstaller.STATUS_FAILURE, 7, null, null))
        // A pending status whose intent is not the installer's is never launched: the install ends there.
        assertEquals(
            InstallStatusPolicy.Action.Failed(pending.transferId),
            InstallStatusPolicy.decide(PackageInstaller.STATUS_PENDING_USER_ACTION, 7, "android.intent.action.VIEW", pending),
        )
        assertEquals(
            InstallStatusPolicy.Action.Failed(pending.transferId),
            InstallStatusPolicy.decide(PackageInstaller.STATUS_FAILURE_ABORTED, 7, null, pending),
        )
        assertEquals(InstallStatusPolicy.Action.Done, InstallStatusPolicy.decide(PackageInstaller.STATUS_SUCCESS, 7, null, pending))
    }

    @Test
    fun onlyThisPhonesRecentPeerUpdateBringsTheUiBack() {
        val pending = PendingInstall(FrameId.new(), 21_400_900L, sessionId = 7, at = now)
        assertTrue(UpdateLanded.ours(pending, 21_400_900L, now + 30_000L))
        assertFalse("a store's update of another version", UpdateLanded.ours(pending, 21_500_900L, now))
        assertFalse("too long ago", UpdateLanded.ours(pending, 21_400_900L, now + UpdateLanded.RELAUNCH_WINDOW_MS + 1))
        assertFalse("nothing recorded", UpdateLanded.ours(null, 21_400_900L, now))
    }
}
