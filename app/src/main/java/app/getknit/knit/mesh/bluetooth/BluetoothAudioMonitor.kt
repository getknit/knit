package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse Bluetooth-audio state, ordered by how much of the shared radio it plausibly consumes. */
enum class BtAudioState { Idle, Connected, Streaming }

/**
 * Observes whether Bluetooth audio (A2DP) is active on this device, so [BluetoothMeshTransport] can
 * *attribute* connect failures to a busy radio. On phones the Bluetooth and 2.4 GHz Wi-Fi front-end is
 * shared, and A2DP streaming is the reported case: a second L2CAP connect fails while music plays to a
 * speaker, even though the peer is valid and in range.
 *
 * **Instrumentation only (for now).** The transport reads [state] / [contended] to enrich its logs and the
 * Diagnostics transport row; it does **not** yet gate connections on this (see the plan's deferred
 * "promotion gate"). Signals, best-first:
 *  1. The A2DP profile proxy plus its [BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED] /
 *     [BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED] broadcasts — the latter is the "actively streaming" edge.
 *  2. Fallback for the app-started-mid-stream case (the playing broadcast may have already fired): a
 *     Bluetooth-typed [AudioManager] output device plus [AudioManager.isMusicActive].
 *
 * Needs `BLUETOOTH_CONNECT` (granted at onboarding); the Bluetooth calls are [SuppressLint] "MissingPermission"
 * like the transport, which self-degrades if the permission is absent. Registering for the (protected, system)
 * A2DP broadcasts needs no exported flag, mirroring the transport's adapter-state receiver.
 */
@SuppressLint("MissingPermission")
class BluetoothAudioMonitor(
    context: Context,
    private val adapter: BluetoothAdapter?,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(BtAudioState.Idle)
    val state: StateFlow<BtAudioState> = _state.asStateFlow()

    private val _contended = MutableStateFlow(false)

    /** A convenience view for the Diagnostics chip (and the future gate): true only while actively streaming. */
    val contended: StateFlow<Boolean> = _contended.asStateFlow()

    // Latched from the A2DP proxy + broadcasts (written on the main thread, read in recompute).
    @Volatile private var a2dpProxy: BluetoothProfile? = null

    @Volatile private var connected = false

    @Volatile private var playing = false
    private var registered = false

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                if (profile != BluetoothProfile.A2DP) return
                a2dpProxy = proxy
                connected = runCatching { proxy.connectedDevices.isNotEmpty() }.getOrDefault(false)
                recompute("proxyConnected")
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.A2DP) return
                a2dpProxy = null
                connected = false
                playing = false
                recompute("proxyDisconnected")
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                        val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                        connected = st == BluetoothProfile.STATE_CONNECTED
                        if (!connected) playing = false
                        recompute("conn=$st")
                    }

                    BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                        val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING)
                        playing = st == BluetoothA2dp.STATE_PLAYING
                        recompute("play=$st")
                    }
                }
            }
        }

    fun start() {
        if (registered) return
        registered = true
        val proxyRequested =
            runCatching {
                adapter?.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP) == true
            }.getOrDefault(false)
        val filter =
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            }
        runCatching { appContext.registerReceiver(receiver, filter) }
        recompute("start(proxy=$proxyRequested)")
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
        a2dpProxy?.let { p -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, p) } }
        a2dpProxy = null
        connected = false
        playing = false
    }

    /** Re-evaluate against the live [AudioManager] state — the playing edge can be missed when we start mid-stream. */
    fun refresh() = recompute("poll")

    private fun recompute(why: String) {
        val btOut = btAudioOutputPresent()
        val streaming = playing || (btOut && musicActive())
        val next =
            when {
                streaming -> BtAudioState.Streaming
                connected || btOut -> BtAudioState.Connected
                else -> BtAudioState.Idle
            }
        if (_state.value != next) {
            _state.value = next
            _contended.value = next == BtAudioState.Streaming
            log("bt-audio -> $next ($why)")
        }
    }

    private fun musicActive(): Boolean = runCatching { audioManager?.isMusicActive == true }.getOrDefault(false)

    private fun btAudioOutputPresent(): Boolean {
        val am = audioManager ?: return false
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return false
        return devices.any { it.type in BT_OUTPUT_TYPES }
    }

    private companion object {
        // TYPE_BLE_HEADSET/TYPE_BLE_SPEAKER are API 31 but compile-time-inlined ints; on pre-31 they simply
        // never match a device type, so this is safe on minSdk 29. @SuppressLint silences the InlinedApi note.
        @SuppressLint("InlinedApi")
        val BT_OUTPUT_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
            )
    }
}
