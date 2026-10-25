package app.getknit.knit.ui.devview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.blob.AttachmentHolderEntity
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The message inspector (ADR 2026-10.s7wg) at the foot of Message details: what this phone holds about [messageId],
 * raw. Composed only while the developer view is on, so its view-model is never built otherwise.
 */
@Composable
fun MessageInspectSection(
    messageId: String,
    viewModel: MessageInspectViewModel = koinViewModel { parametersOf(messageId) },
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    MessageInspectSectionContent(groups)
}

@Composable
internal fun MessageInspectSectionContent(
    groups: List<InspectGroup>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag("message_details_developer")) {
        Text(
            text = stringResource(R.string.message_details_developer),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        InspectGroups(groups)
    }
}

// The sample: our own DM with a photo, acked over Wi-Fi Aware, still in custody as a v3 frame, its bytes proved held.
private const val SAMPLE_ID = "0b4f9c2e7a1d4e6b8c3f5a2d"
private const val SAMPLE_ME = "me5fdtq2wvzk4hn7cxgae3bjrm"
private const val SAMPLE_PEER = "samr1vz2kq7dw4mhgc5tyxpe3n"
private const val SAMPLE_HASH = "9f2c1e6a7b3d4c5e8f0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6"

private val sampleCustody: ForwardEntity by lazy {
    val enc =
        EncEnvelope(
            v = EncEnvelope.VERSION_DM_V3,
            nonce = ByteArray(0),
            ct = ByteArray(212),
            keys = emptyList(),
            r = RatchetHeader(se = 4, ek = ByteArray(32), pe = 3, n = 17),
        )
    val envelope =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = SAMPLE_ID,
            senderId = SAMPLE_ME,
            sentAt = PREVIEW_NOW - 9 * 60_000L,
            recipientId = SAMPLE_PEER,
            payload = WireCodec.encodePayload(ChatContent(attachmentHash = SAMPLE_HASH, enc = enc)),
        )
    ForwardEntity(
        id = SAMPLE_ID,
        recipientId = SAMPLE_PEER,
        groupId = null,
        senderId = SAMPLE_ME,
        type = FrameType.CHAT,
        origin = ForwardStore.ORIGIN_SELF,
        signed = WireCodec.encodeEnvelope(envelope),
        sig = ByteArray(64),
        sentAt = PREVIEW_NOW - 9 * 60_000L,
        receivedAt = PREVIEW_NOW - 9 * 60_000L,
        expiresAt = PREVIEW_NOW + 3 * 60 * 60_000L,
        attachmentHash = SAMPLE_HASH,
    )
}

private val sampleFacts: InspectFacts by lazy {
    InspectFacts(
        message =
            MessageEntity(
                id = SAMPLE_ID,
                senderId = SAMPLE_ME,
                recipientId = SAMPLE_PEER,
                conversationId = SAMPLE_PEER,
                body = "Trailhead at 7?",
                sentAt = PREVIEW_NOW - 9 * 60_000L,
                received = true,
                receivedVia = DeliveryPlane.WifiAware.code,
                attachmentHash = SAMPLE_HASH,
                attachmentMime = "image/jpeg",
                attachmentKey = "sample-key",
                attachmentSize = 48_213L,
            ),
        selfNodeId = SAMPLE_ME,
        receipts = listOf(MessageReceiptEntity(SAMPLE_ID, SAMPLE_PEER, PREVIEW_NOW - 8 * 60_000L, DeliveryPlane.WifiAware.code)),
        custody = sampleCustody,
        blobSize = 48_213L,
        verdict = BlobVerdictEntity(SAMPLE_HASH, flagged = false, score = 0.012f),
        holders = listOf(AttachmentHolderEntity(SAMPLE_HASH, SAMPLE_PEER, DeliveryPlane.Nearby.code, PREVIEW_NOW - 8 * 60_000L)),
        custodyPins = 1,
        now = PREVIEW_NOW,
    )
}

// Split in two: the whole inspector runs past the screenshot canvas's 2400 px, and what falls off is never checked.
private val FRAME_GROUPS = setOf("identity", "delivery", "custody")

@Preview(name = "Message inspector")
@Composable
fun MessageInspectSectionPreview() =
    KnitPreview {
        Surface {
            MessageInspectSectionContent(
                MessageInspection.groups(sampleFacts).filter { it.title in FRAME_GROUPS },
                Modifier.padding(16.dp),
            )
        }
    }

@Preview(name = "Message inspector attachment")
@Composable
fun MessageInspectSectionAttachmentPreview() =
    KnitPreview {
        Surface {
            MessageInspectSectionContent(
                MessageInspection.groups(sampleFacts).filterNot { it.title in FRAME_GROUPS },
                Modifier.padding(16.dp),
            )
        }
    }
