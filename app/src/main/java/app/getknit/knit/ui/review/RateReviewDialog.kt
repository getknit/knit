package app.getknit.knit.ui.review

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The "Enjoying Knit?" rate prompt — one dialog, two sentiment branches (the gated-feedback pattern a
 * Play in-app-review card can't do). [onPositive] takes a happy user to rate (the Play listing or the
 * source repo, see [app.getknit.knit.review] `ReviewPrompter.rateUrl`); [onNegative] routes a lukewarm one
 * to private feedback (the issue tracker) instead of a public 1-star review; [onDismiss] (tap-away / back)
 * just closes it. Stateless — the show/hide flag lives in `KnitApp`, driven by [ReviewPromptInbox], and the
 * attempt is already recorded by the time this shows, so every path only needs to close the dialog.
 */
@Composable
fun RateReviewDialog(
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_prompt_title)) },
        text = { Text(stringResource(R.string.review_prompt_body)) },
        confirmButton = {
            TextButton(onClick = onPositive) {
                Text(stringResource(R.string.review_prompt_positive))
            }
        },
        dismissButton = {
            TextButton(onClick = onNegative) {
                Text(stringResource(R.string.review_prompt_negative))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
fun RateReviewDialogPreview() =
    KnitPreview {
        RateReviewDialog(onPositive = {}, onNegative = {}, onDismiss = {})
    }
