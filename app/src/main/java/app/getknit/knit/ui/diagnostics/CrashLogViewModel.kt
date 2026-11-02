package app.getknit.knit.ui.diagnostics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.crash.CrashIssueUrl
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.crash.shareFileName
import app.getknit.knit.ui.ISSUES_URL
import app.getknit.knit.ui.share.OwnFile
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.SharedContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CrashLogUiState(
    val loading: Boolean = true,
    val ref: CrashReportRef? = null,
    // The report as the user sees it: structurally redacted at capture, name-redacted on this read.
    val text: String = "",
)

/**
 * Backs the "Last crash" detail screen — read the newest stored report, copy it, share it as a file,
 * open a prefilled GitHub bug form for it, or delete every stored report.
 *
 * Nothing here transmits: [issueUrl] returns a link the user's browser opens, [sendInChat] hands the report to
 * the chat picker, and the app never posts.
 */
class CrashLogViewModel(
    private val crashes: CrashReports,
    private val shareInbox: ShareInbox,
) : ViewModel() {
    private val _state = MutableStateFlow(CrashLogUiState())
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    // One-shot snackbar feedback as a string-resource id, matching DiagnosticsViewModel's channel.
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val ref = crashes.latest()
            val text = ref?.let { crashes.read(it) }.orEmpty()
            // A ref whose file has gone unreadable underneath us shows the empty state rather than a
            // header with nothing behind it.
            _state.value = CrashLogUiState(loading = false, ref = ref.takeIf { text.isNotBlank() }, text = text)
        }
    }

    /** The trace reached the clipboard (pre-Android-13 only — the platform confirms it itself after that). */
    fun onCopied() {
        _events.tryEmit(R.string.crash_copied)
    }

    /** Staging the share file, or finding an app to receive it, failed. */
    fun onShareFailed() {
        _events.tryEmit(R.string.crash_share_error)
    }

    /**
     * Writes the report to the share-staging cache and returns its `content://` Uri, or `null` if there
     * is nothing to share or staging failed. Suspending rather than fire-and-forget so the caller can
     * launch the chooser with the result, the same shape as `prepareKnitApk`.
     */
    suspend fun stageForShare(): Uri? = _state.value.ref?.let { crashes.exportForShare(it) }

    /**
     * Send in a chat (#287): the report [stageForShare] would write, read again so the name pass is as fresh as
     * the share sheet's, handed to the chat picker as bytes. Never a URI: the chat refuses Knit's own provider
     * (ADR 2026-11.vdx4).
     */
    fun sendInChat() {
        val ref = _state.value.ref
        if (ref == null) {
            onShareFailed()
            return
        }
        viewModelScope.launch {
            val text = crashes.read(ref)
            if (text == null) {
                onShareFailed()
            } else {
                val file = OwnFile(shareFileName(ref.appVersion, ref.at), DIAGNOSTICS_FILE_MIME, text.encodeToByteArray())
                shareInbox.offer(SharedContent(text = null, imageUri = null, ownFile = file))
            }
        }
    }

    /**
     * A prefilled new-issue URL for the loaded report, or `null` if there is nothing to report. The
     * browser opens it; the user submits it.
     */
    fun issueUrl(): String? {
        val current = _state.value
        val ref = current.ref ?: return null
        return CrashIssueUrl.forReport("$ISSUES_URL/new", ref, current.text)
    }

    /** Removes every stored report. Stays on the screen afterwards so the confirmation is visible. */
    fun delete() {
        viewModelScope.launch {
            crashes.clear()
            _state.value = CrashLogUiState(loading = false)
            _events.tryEmit(R.string.crash_deleted)
        }
    }
}
