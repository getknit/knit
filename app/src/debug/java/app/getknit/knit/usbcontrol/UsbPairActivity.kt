package app.getknit.knit.usbcontrol

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.getknit.knit.R
import app.getknit.knit.ui.theme.KnitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Pairing a computer (debug builds only): opened from [UsbPairNotice]. It shows who claims to be asking, and the code
 * only after a tap, with a word to type it nowhere but the computer the person ran `knit pair` on, so a kiosk that
 * posted the notice can't simply have its own screen ask for "the code your phone shows". Below it, the paired
 * computers, each with Forget.
 *
 * Not exported and not shown over the keyguard: a tap on the notice from a locked phone asks for the unlock first.
 * `FLAG_SECURE` keeps the code out of the recents thumbnail and out of screen capture.
 */
class UsbPairActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            KnitTheme {
                Surface(Modifier.fillMaxSize()) { PairScreen(onDone = ::finish) }
            }
        }
    }
}

@Composable
private fun PairScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { UsbControlServer.pending(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var listVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val pair = remember(now) { pending.live() }
    // Per exchange: a new one (the same computer again, or another) asks for its own tap before its code shows.
    var shown by remember(pair) { mutableStateOf(false) }
    // The Keystore can stall (KeystoreSecret retries a refusal), so the list is read off the main thread.
    var pairings by remember { mutableStateOf<List<Pairing>?>(emptyList()) }
    LaunchedEffect(listVersion, pair == null) {
        pairings = withContext(Dispatchers.IO) { (UsbControlServer.pairings(context).read() as? Stored.Ok)?.pairings }
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.usbctl_pair_title), style = MaterialTheme.typography.headlineSmall)
        if (pair == null) {
            Text(stringResource(R.string.usbctl_pair_none))
        } else {
            Text(stringResource(R.string.usbctl_pair_asking, pair.name))
            if (!shown) {
                Button(onClick = { shown = true }) { Text(stringResource(R.string.usbctl_pair_show)) }
            } else {
                Text(
                    UsbPairingCrypto.display(pair.code),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.usbctl_pair_warning, ((pair.expiresAt - now).coerceAtLeast(0) / MS_PER_S).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = {
                pending.cancel()
                onDone()
            }) { Text(stringResource(R.string.usbctl_pair_cancel)) }
        }
        HorizontalDivider()
        Text(stringResource(R.string.usbctl_paired_title), style = MaterialTheme.typography.titleMedium)
        val list = pairings
        when {
            list == null -> {
                Text(stringResource(R.string.usbctl_paired_unavailable))
            }

            list.isEmpty() -> {
                Text(stringResource(R.string.usbctl_paired_empty))
            }

            else -> {
                list.forEach { p ->
                    PairedRow(p) {
                        scope.launch {
                            withContext(Dispatchers.IO) { UsbControlServer.forget(context, p.handleText) }
                            listVersion++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedRow(
    pairing: Pairing,
    onForget: () -> Unit,
) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pairing.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.usbctl_paired_last_used, format.format(Date(pairing.lastUsedAt))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onForget) { Text(stringResource(R.string.usbctl_paired_forget)) }
    }
}

private const val TICK_MS = 1_000L
private const val MS_PER_S = 1_000L
