package app.getknit.knit.ui.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.backup.BackupException
import app.getknit.knit.data.backup.BackupKeys
import app.getknit.knit.data.backup.BackupManifest
import app.getknit.knit.data.backup.BackupProblem
import app.getknit.knit.data.backup.BackupWriter
import app.getknit.knit.data.backup.RestoreStager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The screen's two state machines over a mocked writer and stager. What matters to the user: the recovery
 * key stays the one they were shown through every phase of a backup, a refused backup names its problem,
 * a restore never starts on a key that is not thirty digits, and backing out of
 * a staged restore throws the staged files away. The writes run on `Dispatchers.IO` behind `NonCancellable`,
 * so the end state is awaited, not assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val writer = mockk<BackupWriter>()
    private val stager = mockk<RestoreStager>(relaxed = true)
    private val dir = File(context.cacheDir, "backup-vm-${System.nanoTime()}").apply { mkdirs() }
    private val target: Uri = Uri.fromFile(File(dir, "phone.knitbackup"))
    private val manifest =
        BackupManifest(
            v = 1,
            schemaVersion = 17,
            appVersionCode = 1,
            appVersionName = "test",
            createdAt = 1_700_000_000_000L,
            nodeId = "abcdefghijklmnopqrstuvwxyz",
            entries = emptyList(),
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        runBlocking {
            withTimeout(5_000) {
                while (!predicate(value)) delay(10)
                value
            }
        }

    private fun viewModel() = BackupViewModel(context, writer, stager)

    // --- backup ---

    @Test
    fun aBackupKeepsTheKeyItShowedThroughToWritten() {
        coEvery { writer.write(any(), any(), any()) } answers {
            firstArg<OutputStream>().write(byteArrayOf(1, 2, 3))
            thirdArg<(Long, Long) -> Unit>()(3, 3)
            manifest
        }
        val vm = viewModel()
        vm.startBackup()
        val key = (vm.backup.value as BackupPhase.KeyShown).key
        assertEquals(key, BackupKeys.parse(key))

        vm.writeBackup(target)

        val done = vm.backup.await { it is BackupPhase.Written } as BackupPhase.Written
        assertEquals(key, done.key)
        assertEquals(manifest.createdAt, done.createdAt)
        coVerify { writer.write(any(), key, any()) }
        assertTrue(File(target.path!!).exists())
    }

    @Test
    fun aDismissedPickerOrNoKeyWritesNothing() {
        val vm = viewModel()
        vm.writeBackup(target) // no key minted yet
        assertEquals(BackupPhase.Idle, vm.backup.value)
        vm.startBackup()
        vm.writeBackup(null) // picker dismissed
        assertTrue(vm.backup.value is BackupPhase.KeyShown)
        vm.cancelBackup()
        assertEquals(BackupPhase.Idle, vm.backup.value)
        coVerify(exactly = 0) { writer.write(any(), any(), any()) }
    }

    @Test
    fun aRefusedBackupNamesItsProblemAndKeepsTheKey() {
        coEvery { writer.write(any(), any(), any()) } throws BackupException(BackupProblem.NO_SPACE, "need more")
        val vm = viewModel()
        vm.startBackup()
        val key = (vm.backup.value as BackupPhase.KeyShown).key
        vm.writeBackup(target)

        val failed = vm.backup.await { it is BackupPhase.Failed } as BackupPhase.Failed
        assertEquals(BackupPhase.Failed(key, BackupProblem.NO_SPACE), failed)
    }

    @Test
    fun anIoFailureIsAFailureWithNoNamedProblem() {
        coEvery { writer.write(any(), any(), any()) } throws IOException("disk went away")
        val vm = viewModel()
        vm.startBackup()
        vm.writeBackup(target)
        val failed = vm.backup.await { it is BackupPhase.Failed } as BackupPhase.Failed
        assertEquals(null, failed.problem)
    }

    // --- restore ---

    @Test
    fun aMistypedKeyNeverReachesTheStager() {
        val vm = viewModel()
        vm.pickRestore(target)
        vm.stageRestore("1234")
        assertEquals(RestorePhase.Picked(target, badKey = true), vm.restore.value)
        coVerify(exactly = 0) { stager.stage(any(), any(), any()) }
    }

    @Test
    fun aGoodKeyStagesAndBackingOutDiscards() {
        File(target.path!!).writeBytes(byteArrayOf(9))
        coEvery { stager.stage(any(), any(), any()) } returns manifest
        val key = BackupKeys.generate()
        val vm = viewModel()
        vm.pickRestore(target)
        vm.stageRestore(BackupKeys.display(key)) // typed with the display grouping

        assertEquals(RestorePhase.Staged(manifest), vm.restore.await { it is RestorePhase.Staged })
        coVerify { stager.stage(any(), key, any()) }

        vm.cancelRestore()
        verify { stager.discard() }
        assertEquals(RestorePhase.Idle, vm.restore.value)
    }

    @Test
    fun aRefusedRestoreNamesItsProblemAndCanBeRetried() {
        File(target.path!!).writeBytes(byteArrayOf(9))
        coEvery { stager.stage(any(), any(), any()) } throws BackupException(BackupProblem.WRONG_KEY_OR_DAMAGED, "seal")
        val vm = viewModel()
        vm.pickRestore(target)
        vm.stageRestore(BackupKeys.generate())

        assertEquals(
            RestorePhase.Failed(target, BackupProblem.WRONG_KEY_OR_DAMAGED),
            vm.restore.await { it is RestorePhase.Failed },
        )
        // A retry from the failed phase takes the same file.
        coEvery { stager.stage(any(), any(), any()) } returns manifest
        vm.stageRestore(BackupKeys.generate())
        assertEquals(RestorePhase.Staged(manifest), vm.restore.await { it is RestorePhase.Staged })
    }

    @Test
    fun aFileThatCannotBeOpenedIsAFailure() {
        val vm = viewModel()
        val missing = Uri.fromFile(File(dir, "gone.knitbackup"))
        vm.pickRestore(missing)
        vm.stageRestore(BackupKeys.generate())
        assertEquals(RestorePhase.Failed(missing, null), vm.restore.await { it is RestorePhase.Failed })
    }

    @Test
    fun confirmingWithoutAStagedRestoreDoesNothing() {
        every { stager.isStaged() } returns false
        val vm = viewModel()
        vm.confirmRestore()
        assertEquals(RestorePhase.Idle, vm.restore.value)
        assertFalse(vm.restore.value is RestorePhase.Restarting)
    }
}
