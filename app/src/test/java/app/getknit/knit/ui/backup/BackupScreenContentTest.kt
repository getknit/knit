package app.getknit.knit.ui.backup

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.backup.BackupKeys
import app.getknit.knit.data.backup.BackupManifest
import app.getknit.knit.data.backup.BackupProblem
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The stateless screen, one phase at a time. The two things that must hold: the file picker for a backup
 * stays behind "I've saved this key" (the key is the only way back in), and the restore that replaces the
 * phone sits behind a dialog that names whose backup it is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = BackupKeys.generate()

    private class Calls {
        var start = 0
        var pickBackup = 0
        var pickRestore = 0
        var staged = ""
        var confirm = 0
        var cancelRestore = 0
        var copied = ""
    }

    private fun render(
        backup: BackupPhase = BackupPhase.Idle,
        restore: RestorePhase = RestorePhase.Idle,
        restoreOnly: Boolean = false,
    ): Calls {
        val calls = Calls()
        compose.setContent {
            KnitTheme {
                BackupScreenContent(
                    backup = backup,
                    restore = restore,
                    restoreOnly = restoreOnly,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onStartBackup = { calls.start++ },
                    onCancelBackup = {},
                    onCopyKey = { calls.copied = it },
                    onPickBackupFile = { calls.pickBackup++ },
                    onPickRestoreFile = { calls.pickRestore++ },
                    onStageRestore = { calls.staged = it },
                    onCancelRestore = { calls.cancelRestore++ },
                    onConfirmRestore = { calls.confirm++ },
                    formatSize = { "$it B" },
                )
            }
        }
        return calls
    }

    @Test
    fun createBackupMintsAKey() {
        val calls = render()
        compose.onNodeWithTag("backup_start").performClick()
        assertEquals(1, calls.start)
    }

    @Test
    fun theFilePickerWaitsUntilTheKeyIsSaved() {
        val calls = render(backup = BackupPhase.KeyShown(key))
        compose.onNodeWithTag("backup_key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_pick_file").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("backup_key_copy").performScrollTo().performClick()
        assertEquals(key, calls.copied)
        compose.onNodeWithTag("backup_key_saved").performScrollTo().performClick()
        compose
            .onNodeWithTag("backup_pick_file")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, calls.pickBackup)
    }

    @Test
    fun aWrittenBackupKeepsTheKeyOnScreen() {
        render(backup = BackupPhase.Written(key, createdAt = 1_700_000_000_000L))
        compose.onNodeWithTag("backup_written").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theOnboardingDoorShowsOnlyTheRestoreHalf() {
        val calls = render(restoreOnly = true)
        compose.onNodeWithTag("backup_start").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.backup_restore_title)).assertIsDisplayed()
        compose.onNodeWithTag("restore_pick_file").performScrollTo().performClick()
        assertEquals(1, calls.pickRestore)
    }

    @Test
    fun aPickedFileAsksForTheKeyAndHandsItOnAsTyped() {
        val calls = render(restore = RestorePhase.Picked(Uri.parse("content://docs/1")))
        compose.onNodeWithTag("restore_stage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("restore_key").performScrollTo().performTextInput("12345 67890")
        compose
            .onNodeWithTag("restore_stage")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals("12345 67890", calls.staged)
    }

    @Test
    fun aBadKeyIsSaidSo() {
        render(restore = RestorePhase.Picked(Uri.parse("content://docs/1"), badKey = true))
        compose.onNodeWithText(context.getString(R.string.backup_key_not_a_key)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aStagedRestoreConfirmsWithTheOwnerAndTheDate() {
        val manifest =
            BackupManifest(
                v = 1,
                schemaVersion = 14,
                appVersionCode = 1,
                appVersionName = "2.7.0",
                createdAt = 1_700_000_000_000L,
                nodeId = "node",
                displayName = "Ada",
                entries = emptyList(),
            )
        val calls = render(restore = RestorePhase.Staged(manifest))
        compose.onNodeWithText(context.getString(R.string.backup_restore_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText("Ada", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("restore_confirm").performClick()
        assertEquals(1, calls.confirm)
    }

    @Test
    fun aWrongKeyOffersTheKeyFieldAgain() {
        render(restore = RestorePhase.Failed(Uri.parse("content://docs/1"), BackupProblem.WRONG_KEY_OR_DAMAGED))
        compose.onNodeWithTag("restore_failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("restore_key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aNewerBackupIsRefusedWithNoWayToRetry() {
        render(restore = RestorePhase.Failed(Uri.parse("content://docs/1"), BackupProblem.NEWER_APP))
        compose.onNodeWithText(context.getString(R.string.backup_problem_newer_app), substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("restore_key").assertDoesNotExist()
    }
}
