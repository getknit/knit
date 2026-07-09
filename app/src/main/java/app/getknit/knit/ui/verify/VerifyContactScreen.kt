package app.getknit.knit.ui.verify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.koin.androidx.compose.koinViewModel

/**
 * Standalone "Verify contact" screen (chat-list overflow → Verify contact). Shows this device's identity
 * QR and scans another's, so two people can verify each other's keys — and add each other as contacts —
 * even before they've ever chatted (and thus before a profile exists to hang [app.getknit.knit.ui.profile
 * .ProfileDetailsScreen]'s verification section on). Reuses the shared [EncryptionSection] in its
 * no-bound-peer (standalone) mode; scanning a code pins + verifies the peer via [VerifyContactViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyContactScreen(
    onBack: () -> Unit,
    viewModel: VerifyContactViewModel = koinViewModel(),
) {
    val myQrPayload by viewModel.myQrPayload.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val addedMessage = stringResource(R.string.verify_added)
    val mismatchMessage = stringResource(R.string.verify_mismatch)
    val selfMessage = stringResource(R.string.verify_self)
    val invalidMessage = stringResource(R.string.verify_invalid)
    LaunchedEffect(scanResult) {
        when (scanResult) {
            VerifyResult.VERIFIED -> snackbarHostState.showSnackbar(addedMessage)
            VerifyResult.MISMATCH -> snackbarHostState.showSnackbar(mismatchMessage)
            VerifyResult.SELF -> snackbarHostState.showSnackbar(selfMessage)
            VerifyResult.INVALID -> snackbarHostState.showSnackbar(invalidMessage)
            null -> Unit
        }
        if (scanResult != null) viewModel.consumeScanResult()
    }

    val scanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { viewModel.onScanned(it) }
        }

    VerifyContactScreenContent(
        myQrPayload = myQrPayload,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onScan = {
            scanLauncher.launch(
                ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifyContactScreenContent(
    myQrPayload: String?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.verify_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EncryptionSection(
                myQrPayload = myQrPayload,
                peer = null,
                onScan = onScan,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VerifyContactScreenPreview() =
    KnitPreview {
        VerifyContactScreenContent(
            myQrPayload = "knit-id:v1:me:bundle",
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onScan = {},
        )
    }
