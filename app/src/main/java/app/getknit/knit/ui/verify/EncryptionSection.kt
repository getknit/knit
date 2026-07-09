// The file's single top-level class (PeerVerification, the peer-state holder) rides along with the
// EncryptionSection composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.verify

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.image.QrCode
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The peer-specific half of [EncryptionSection]: whether we hold the peer's key yet, whether the user
 * has verified it, and the human-comparable safety number (null until both keys are known).
 */
data class PeerVerification(
    val displayName: String,
    val hasKey: Boolean,
    val verified: Boolean,
    val safetyNumber: String?,
)

/**
 * The shared "Encryption" block: the local user's identity QR (so someone can scan it) plus a Scan
 * action, and — when bound to a specific [peer] — that peer's end-to-end key-verification status (badge,
 * safety number, and mark-verified/clear actions).
 *
 * Rendered in two places:
 *  - a peer's read-only profile ([app.getknit.knit.ui.profile.ProfileDetailsScreenContent]) passes a
 *    non-null [peer] and shows the full verification section; and
 *  - the standalone Verify-contact screen ([app.getknit.knit.ui.verify.VerifyContactScreenContent])
 *    passes `peer = null` — there is no bound contact until a code is scanned, so it shows only
 *    "share my code + scan theirs".
 */
@Composable
fun EncryptionSection(
    myQrPayload: String?,
    peer: PeerVerification?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    onMarkVerified: () -> Unit = {},
    onClearVerification: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.verify_section_title),
            style = MaterialTheme.typography.titleMedium,
        )

        if (peer != null && !peer.hasKey) {
            Text(
                text = stringResource(R.string.verify_no_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        if (peer != null) {
            // Verified / not-verified badge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (peer.verified) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    contentDescription = null,
                    tint =
                        if (peer.verified) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        stringResource(
                            if (peer.verified) R.string.verify_verified else R.string.verify_not_verified,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            peer.safetyNumber?.let { number ->
                Card {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                    )
                }
            }

            Text(
                text = stringResource(R.string.verify_caption, peer.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.verify_standalone_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        myQrPayload?.let { payload ->
            val qr = remember(payload) { QrCode.render(payload, QR_SIZE_PX) }
            qr?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(200.dp),
                )
            }
            Text(
                text =
                    peer?.let { stringResource(R.string.verify_qr_caption, it.displayName) }
                        ?: stringResource(R.string.verify_qr_caption_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.verify_scan))
        }

        // Verify actions are peer-bound: only shown on a specific contact's profile, not standalone.
        if (peer != null) {
            if (peer.verified) {
                OutlinedButton(onClick = onClearVerification, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.verify_clear))
                }
            } else {
                OutlinedButton(onClick = onMarkVerified, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.verify_mark_verified))
                }
            }
        }
    }
}

private const val QR_SIZE_PX = 480

@Preview(showBackground = true)
@Composable
fun EncryptionSectionVerifiedPreview() =
    KnitPreview {
        EncryptionSection(
            myQrPayload = "knit-id:v1:ada:bundle",
            peer =
                PeerVerification(
                    displayName = "Ada Lovelace",
                    hasKey = true,
                    verified = true,
                    safetyNumber = "12345 67890 12345 67890 12345 67890",
                ),
            onScan = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun EncryptionSectionUnverifiedPreview() =
    KnitPreview {
        EncryptionSection(
            myQrPayload = "knit-id:v1:grace:bundle",
            peer =
                PeerVerification(
                    displayName = "Grace Hopper",
                    hasKey = true,
                    verified = false,
                    safetyNumber = "98765 43210 98765 43210 98765 43210",
                ),
            onScan = {},
        )
    }

// The standalone "verify a new contact" layout: no bound peer, just share-my-code + scan.
@Preview(showBackground = true)
@Composable
fun EncryptionSectionStandalonePreview() =
    KnitPreview {
        EncryptionSection(
            myQrPayload = "knit-id:v1:me:bundle",
            peer = null,
            onScan = {},
        )
    }
