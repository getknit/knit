// The file's single top-level class (PeerVerification, the peer-state holder) rides along with the
// EncryptionSection composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.verify

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.components.ButtonRow
import app.getknit.knit.ui.components.leftToRight
import app.getknit.knit.ui.image.QrCode
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.knitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * The block draws no heading of its own. Neither caller wanted the one it used to have: Add contact's top
 * bar already names the screen, and the peer profile now draws `SectionHeader` in the same register as its
 * other sections (ADR 2026-09.hknx).
 *
 * Rendered in two places:
 *  - a peer's read-only profile ([app.getknit.knit.ui.profile.ProfileDetailsScreenContent]) passes a
 *    non-null [peer] and shows the full verification section; and
 *  - the Add-contact screen ([app.getknit.knit.ui.addcontact.AddContactScreenContent]) passes
 *    `peer = null` — there is no bound contact until a code is scanned, so it shows only "share my code
 *    + scan theirs", above that screen's paste-a-link half.
 */
@Composable
fun EncryptionSection(
    myQrPayload: String?,
    peer: PeerVerification?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    onMarkVerified: () -> Unit = {},
    onClearVerification: () -> Unit = {},
    // Standalone mode only: mint + hand out this device's contact link (docs/CONTACT_CARD.md).
    onShareLink: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    // The app's one verified mark (the DM header's shield, the profile's badge), so the
                    // same fact never wears two symbols on the one screen that shows both.
                    imageVector = if (peer.verified) Icons.Filled.VerifiedUser else Icons.Filled.Lock,
                    contentDescription = null,
                    tint =
                        if (peer.verified) {
                            MaterialTheme.knitColors.positive
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
                        style = MaterialTheme.typography.bodyLarge.leftToRight(),
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
            // Encoded off the composition thread. This used to be a plain `remember { QrCode.render(...) }`,
            // so the whole encode ran during the *first* composition of whichever screen shows this — and
            // ProfileDetailsScreen is reached by a tap that is animating a screen transition at the time.
            // The encode is much cheaper since QrCode stopped writing pixels one JNI call at a time, but
            // even the cheap version is half a frame at 120Hz, and none of it needs to block the first frame.
            // A preview (and so a screenshot test) draws only that first frame, so it encodes in place there.
            val inspecting = LocalInspectionMode.current
            val qr by produceState(initialValue = if (inspecting) QrCode.render(payload, QR_SIZE_PX) else null, payload) {
                value = withContext(Dispatchers.Default) { QrCode.render(payload, QR_SIZE_PX) }
            }
            // The slot is held at full size from the start so the section doesn't reflow under the reader
            // when the code lands a frame or two later.
            val qrAlpha by animateFloatAsState(
                targetValue = if (qr == null) 0f else 1f,
                animationSpec = KnitMotion.effects(),
                label = "qrFade",
            )
            Box(modifier = Modifier.size(QR_DISPLAY_SIZE), contentAlignment = Alignment.Center) {
                qr?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(QR_DISPLAY_SIZE)
                                .graphicsLayer { alpha = qrAlpha },
                    )
                }
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

        // The contact link: the QR's job at a distance, offered only where there is no bound peer (a
        // peer's profile shows OUR code for them to scan; a link would be the wrong thing to hand out there).
        if (peer == null && onShareLink != null && onCopyLink != null) {
            ButtonRow {
                Button(onClick = onShareLink, modifier = Modifier.testTag("verify_share_link")) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contact_link_share))
                }
                OutlinedButton(onClick = onCopyLink, modifier = Modifier.testTag("verify_copy_link")) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contact_link_copy))
                }
            }
            Text(
                text = stringResource(R.string.contact_link_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

// The on-screen size of the code; also the reserved slot, so the section's height never depends on whether
// the encode has finished.
private val QR_DISPLAY_SIZE = 200.dp

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
