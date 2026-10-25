package app.getknit.knit.ui.devview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.blob.AttachmentHolderDao
import app.getknit.knit.data.blob.AttachmentHolderEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.MeshController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the message inspector (ADR 2026-10.s7wg) for [messageId]: the rows [MessageInspection] builds from what this
 * phone holds about the message. Built only where the developer view is wired, so `MessageDetailsViewModel` reads
 * nothing more in any build.
 *
 * The row and its receipts are flows. Custody and the attachment's bytes, verdict, holders and pins are one-shot reads
 * on a [tickMs] tick while the screen is started, never a table subscription: Room invalidates per table, so a
 * subscription would re-run on every write anywhere (ADR 2026-09.fjcw). The links are polled for the attachment's
 * progress ([MeshController.arrivals]) only while its bytes are not here, as the chat does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageInspectViewModel(
    private val messageId: String,
    messages: MessageRepository,
    receipts: MessageReceiptRepository,
    private val forward: ForwardDao,
    private val blobs: BlobRepository,
    private val verdicts: BlobVerdictDao,
    private val holders: AttachmentHolderDao,
    private val mesh: MeshController,
    settings: SettingsStore,
    identity: IdentitySource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val selfNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { selfNodeId.value = identity.nodeId() }
    }

    /** What the tick reads; [hash] is the attachment's, null for a message without one. */
    private data class Held(
        val hash: String? = null,
        val custody: ForwardEntity? = null,
        val blobSize: Long? = null,
        val verdict: BlobVerdictEntity? = null,
        val holders: List<AttachmentHolderEntity> = emptyList(),
        val pins: Int = 0,
    )

    private val row = messages.observeMessage(messageId)

    // One tick for both readers below, so the reads run once a tick however many collect them.
    private val held: Flow<Held> =
        row
            .map { it?.attachmentHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                flow {
                    while (true) {
                        emit(read(hash))
                        delay(tickMs)
                    }
                }
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val arriving: Flow<ArrivingFile?> =
        held
            .map { it.hash?.takeIf { _ -> it.blobSize == null } }
            .distinctUntilChanged()
            .flatMapLatest { waiting -> if (waiting == null) flowOf(null) else mesh.arrivals.map { it[waiting] } }

    val groups: StateFlow<List<InspectGroup>> =
        combine(
            row,
            receipts.observeForMessage(messageId),
            held,
            arriving,
            settings.contentFilteringEnabled,
        ) { message, acks, h, file, filterOn ->
            if (message == null) {
                emptyList()
            } else {
                MessageInspection.groups(
                    InspectFacts(
                        message = message,
                        selfNodeId = selfNodeId.value,
                        receipts = acks,
                        custody = h.custody,
                        blobSize = h.blobSize,
                        arriving = file,
                        verdict = h.verdict,
                        holders = h.holders,
                        custodyPins = h.pins,
                        filterOn = filterOn,
                        now = clock(),
                    ),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private suspend fun read(hash: String?): Held {
        val custody = forward.liveRow(messageId, clock())
        if (hash == null) return Held(custody = custody)
        return Held(
            hash = hash,
            custody = custody,
            blobSize = blobs.sizes(listOf(hash))[hash],
            verdict = verdicts.find(hash),
            holders = holders.holdersOf(hash),
            pins = forward.countByAttachmentHash(hash),
        )
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
