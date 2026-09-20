package app.getknit.knit.ui.signout

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The one dialog behind "Sign out here" (ADR 2026-09.ypcc), shared by the chat-list banner and the Settings
 * row so its copy lives in one place: what goes, what the other phone keeps, and what the next open asks.
 */
@Composable
fun SignOutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signout_confirm_title)) },
        text = { Text(stringResource(R.string.signout_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("signout_confirm")) {
                Text(
                    text = stringResource(R.string.signout_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
fun SignOutDialogPreview() =
    KnitPreview {
        SignOutDialog(onConfirm = {}, onDismiss = {})
    }
