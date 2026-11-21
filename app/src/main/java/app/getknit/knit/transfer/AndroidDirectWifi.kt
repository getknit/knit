package app.getknit.knit.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiAvailableChannel
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.MeshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [DirectWifi] over `android.net.wifi.p2p` — the only importer of it in the app (detekt's `ForbiddenImport`).
 *
 * Hosting is `createGroup` with our own name and passphrase; joining is `connect` with the same two facts and
 * nothing else (the API-29 "join a known group" path: no discovery, no device address, no dialog on either
 * side). Formation is read off `WIFI_P2P_CONNECTION_CHANGED_ACTION` plus a poll of `requestConnectionInfo`,
 * matched by SSID and role, and the group-owner address is taken from the platform, never assumed.
 *
 * Around every group this class also does what the pure manager must not know about: it lends the Wi-Fi
 * radio out of the mesh ([MeshTransport.pause] — on Android 12+ our own Aware session would otherwise make
 * `createGroup`/`connect` answer BUSY, and the transport's attach loop would burn its leak budget while the
 * group is up), holds a partial wake lock so a long transfer survives the screen going off, and on
 * [release] removes the group, **closes the channel** (the P2P interface lingers ~150 s otherwise and blocks
 * Aware from coming back) and hands the radio back ([MeshTransport.resume]).
 *
 * The runtime grant (`NEARBY_WIFI_DEVICES` on 33+, fine location below) is the radios' own, asked at
 * onboarding; it is checked at entry and the calls are marked [SuppressLint] for "MissingPermission", the
 * `WifiAwareTransport` precedent.
 */
@SuppressLint("MissingPermission")
class AndroidDirectWifi(
    context: Context,
    private val mesh: MeshTransport,
) : DirectWifi {
    private val app = context.applicationContext
    private val p2p: WifiP2pManager? = app.getSystemService(WifiP2pManager::class.java)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val power: PowerManager? = app.getSystemService(PowerManager::class.java)

    // host / join / release are serialized; every field below is touched only under this lock.
    private val mutex = Mutex()
    private var channel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lent = false

    private class Formation(
        val info: WifiP2pInfo,
        val group: WifiP2pGroup,
        val address: InetAddress,
    ) {
        fun matches(
            ssid: String,
            owner: Boolean,
        ): Boolean = info.isGroupOwner == owner && group.networkName == ssid
    }

    override fun refusal(): TransferRefusal? =
        when {
            p2p == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) -> TransferRefusal.NoWifiDirect
            wifi?.isWifiEnabled != true -> TransferRefusal.WifiOff
            !hasPermission() -> TransferRefusal.Permission
            else -> null
        }

    override suspend fun host(
        credentials: GroupCredentials,
        timeoutMs: Long,
        peerChannels: Set<Int>?,
    ): HostedGroup = hostOn(credentials, timeoutMs, peerChannels, forced = null)

    /**
     * [host], on [forced] when it is set instead of where [DirectChannels.choose] would put the group: the seam
     * `DirectGroupProbeTest` raises each kind of group through on a phone, with the retry and the platform's answers
     * exactly as a transfer meets them.
     */
    internal suspend fun hostOn(
        credentials: GroupCredentials,
        timeoutMs: Long,
        peerChannels: Set<Int>?,
        forced: GroupChannel?,
    ): HostedGroup =
        mutex.withLock {
            val manager = ready()
            val ch = prepare(manager)
            val view = channels()
            val pick = forced?.let { ChannelPick(it, "forced") } ?: DirectChannels.choose(view.sta, peerChannels, view.go)
            Log.i(TAG, "group channel=${pick.channel} sta=${view.sta} peerChannels=${peerChannels?.size} (${pick.reason})")
            val formed =
                DirectChannels.raise(
                    pick,
                    attempt = { channel -> createGroup(manager, ch, credentials, channel, timeoutMs) },
                    clear = {
                        // Only after a try that formed nothing in time (DirectChannels.raise): the framework is still
                        // creating it, and the next createGroup answers BUSY (a Redmi Note 13 on Device Farm,
                        // 2026-11-21). removeGroup does not stop a creation, cancelConnect does; then remove what it
                        // may already have raised.
                        runCatching { withTimeoutOrNull(RELEASE_MS) { act("cancelConnect(pinned)") { manager.cancelConnect(ch, it) } } }
                        runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup(pinned)") { manager.removeGroup(ch, it) } } }
                    },
                    log = { Log.w(TAG, it) },
                ) ?: throw DirectWifiException("group did not form within ${timeoutMs}ms", TransferRefusal.NoWifiDirect)
            val addresses = addressesOf(formed.group, formed.address)
            Log.i(
                TAG,
                "hosting ${credentials.ssid} on ${addresses.joinToString { "${it.address.hostAddress}/${it.prefixLength}" }} " +
                    "freq=${frequencyOf(formed.group)}",
            )
            HostedGroup(addresses, frequencyOf(formed.group), formed.group.`interface`)
        }

    // One createGroup on [channel], or null when no group formed within [timeoutMs]. A refused request throws, as before.
    private suspend fun createGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        credentials: GroupCredentials,
        channel: GroupChannel,
        timeoutMs: Long,
    ): Formation? {
        val config = config(credentials, channel, ipv6 = false)
        return undoOnCancel(manager, ch) {
            withTimeoutOrNull(timeoutMs) {
                awaitFormation(manager, ch, credentials.ssid, owner = true) { manager.createGroup(ch, config, it) }
            }
        }
    }

    override suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
        ipv4: Boolean,
    ): JoinedGroup =
        mutex.withLock {
            val manager = ready()
            val ch = prepare(manager)
            val ipv6 = !ipv4 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ipv6LinkLocalSupported()
            val config = config(credentials, channel = null, ipv6 = ipv6)
            val formed =
                undoOnCancel(manager, ch) {
                    withTimeoutOrNull(attemptMs) {
                        awaitFormation(manager, ch, credentials.ssid, owner = false) { manager.connect(ch, config, it) }
                    }
                }
            if (formed == null) {
                runCatching { act("cancelConnect") { manager.cancelConnect(ch, it) } }
                throw DirectWifiException("no group formed within ${attemptMs}ms")
            }
            val name = formed.group.`interface`
            if (ipv4) undoOnCancel(manager, ch) { awaitLease(name) }
            Log.i(
                TAG,
                "joined ${credentials.ssid} (${if (ipv6) "IPv6 link-local" else "IPv4 DHCP"}); " +
                    "owner at ${formed.address.hostAddress} freq=${frequencyOf(formed.group)}",
            )
            JoinedGroup(formed.address, name)
        }

    override suspend fun release() {
        mutex.withLock {
            val manager = p2p
            val ch = channel
            if (manager != null && ch != null) {
                // BUSY here means "no group to remove", which is the state we want.
                runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup") { manager.removeGroup(ch, it) } } }
                runCatching { ch.close() }
            }
            channel = null
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            wakeLock = null
            if (lent) {
                lent = false
                mesh.resume()
            }
        }
    }

    override suspend fun sweep() {
        val manager = p2p ?: return
        if (refusal() != null) return
        mutex.withLock {
            // A live transfer owns the channel and sweeps for itself in prepare(); leave it alone.
            if (channel != null) return@withLock
            val ch = manager.initialize(app, Looper.getMainLooper()) {} ?: return@withLock
            try {
                val leftover = groupInfo(manager, ch)
                if (leftover != null && GroupCredentials.isOurs(leftover.networkName)) {
                    Log.w(TAG, "removing ${leftover.networkName}, left on air by a previous run")
                    runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup(stale)") { manager.removeGroup(ch, it) } } }
                    // No mesh.resume() here: nothing paused the transport this run, so its own attach loop
                    // is already running and picks the radio up now that the group is gone.
                }
            } finally {
                runCatching { ch.close() }
            }
        }
    }

    /**
     * Runs one formation step; a cancellation that lands mid-step (the user hung up, the call ended) takes back
     * what the step may already have asked the framework for — a `connect` still negotiating, a `createGroup` still
     * creating, a group already formed — before it propagates, since [release] can run before the framework answers
     * and close the channel on a group that then forms with no one to remove it. `cancelConnect` stops a creation as
     * well as a negotiation (removeGroup stops neither), so a host's step cancels too.
     */
    private suspend fun <T> undoOnCancel(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        step: suspend () -> T,
    ): T =
        try {
            step()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { withTimeoutOrNull(RELEASE_MS) { act("cancelConnect") { manager.cancelConnect(ch, it) } } }
                runCatching { withTimeoutOrNull(RELEASE_MS) { act("removeGroup(cancelled)") { manager.removeGroup(ch, it) } } }
            }
            throw e
        }

    /**
     * Waits for the DHCP lease on the joined group's interface [name]. Formation is reported when the link is up,
     * which can be before the client holds an address; a caller that asked for IPv4 gets none sooner.
     */
    private suspend fun awaitLease(name: String?) {
        val leased =
            withTimeoutOrNull(LEASE_WAIT_MS) {
                while (!hasIpv4(name)) delay(LEASE_POLL_MS)
                true
            } == true
        if (!leased) throw DirectWifiException("no IPv4 lease on the group within ${LEASE_WAIT_MS}ms")
    }

    private fun hasIpv4(name: String?): Boolean =
        name != null &&
            runCatching {
                NetworkInterface
                    .getByName(name)
                    ?.inetAddresses
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
                .any { it is Inet4Address && !it.isLoopbackAddress }

    // ---- setup and teardown ----

    private fun ready(): WifiP2pManager {
        refusal()?.let { throw DirectWifiException("Wi-Fi Direct not usable: $it", it) }
        return checkNotNull(p2p)
    }

    /** Lends the radio out, opens the per-transfer channel, clears a group a crash of ours left behind. */
    private suspend fun prepare(manager: WifiP2pManager): WifiP2pManager.Channel {
        if (!lent) {
            mesh.pause()
            lent = true
            delay(SETTLE_MS)
        }
        acquireWakeLock()
        val ch =
            channel ?: manager.initialize(app, Looper.getMainLooper()) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected")
                channel = null
            } ?: throw DirectWifiException("Wi-Fi Direct channel unavailable", TransferRefusal.NoWifiDirect)
        channel = ch
        val leftover = groupInfo(manager, ch)
        if (leftover != null && GroupCredentials.isOurs(leftover.networkName)) {
            Log.w(TAG, "removing a leftover group ${leftover.networkName}")
            runCatching { act("removeGroup(leftover)") { manager.removeGroup(ch, it) } }
        }
        return ch
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
    }

    override fun channels(): DirectChannelView {
        // Deprecated on 31+, but its frequency is not location-redacted and needs only ACCESS_WIFI_STATE, declared.
        @Suppress("DEPRECATION")
        val sta = runCatching { wifi?.connectionInfo?.frequency }.getOrNull()?.takeIf { it > 0 }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !hasPermission()) return DirectChannelView(sta = sta)
        return DirectChannelView(
            sta = sta,
            client = usableChannels(WifiAvailableChannel.OP_MODE_WIFI_DIRECT_CLI),
            go = usableChannels(WifiAvailableChannel.OP_MODE_WIFI_DIRECT_GO),
        )
    }

    // Both bands with their DFS channels: the platform refuses 5 GHz without them (IllegalArgumentException, the
    // WifiAwareTransport finding), and throws SecurityException or UnsupportedOperationException where it will not say.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    // Two MediaTek phones on Device Farm (a Galaxy A16 on 34, a Redmi Note 13+ on 35) answered nothing on 2026-11-21:
    // the line says which refusal it was.
    private fun usableChannels(mode: Int): Set<Int>? =
        runCatching { wifi?.getUsableChannels(BAND_24_5_WITH_DFS, mode)?.mapTo(HashSet()) { it.frequencyMhz } }
            .onFailure { Log.i(TAG, "getUsableChannels(mode=$mode) refused: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

    private fun hasPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    }

    // [channel] is the host's (null for a joiner, which finds the group by name). A band and a frequency are exclusive:
    // the builder throws when given both.
    private fun config(
        credentials: GroupCredentials,
        channel: GroupChannel?,
        ipv6: Boolean,
    ): WifiP2pConfig =
        WifiP2pConfig
            .Builder()
            .setNetworkName(credentials.ssid)
            .setPassphrase(credentials.passphrase)
            .apply {
                when (channel) {
                    null -> Unit
                    GroupChannel.Auto -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    GroupChannel.Band24 -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    is GroupChannel.Frequency -> setGroupOperatingFrequency(channel.mhz)
                }
                if (ipv6 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setGroupClientIpProvisioningMode(WifiP2pConfig.GROUP_CLIENT_IP_PROVISIONING_MODE_IPV6_LINK_LOCAL)
                }
            }.enablePersistentMode(false)
            .build()

    /**
     * Whether this device should join with IPv6 link-local provisioning instead of taking a DHCP lease —
     * which skips the DHCP round trip and is the modern path. Android 14 (the public API's own level, even
     * though the plumbing under it landed in 13), and only when the platform *also* promises to hand the
     * group owner's link-local address back in `WIFI_P2P_CONNECTION_CHANGED_ACTION`: otherwise finding it is
     * the app's problem, and there is no out-of-band channel for it here, since the group is up precisely
     * because Wi-Fi Aware has been paused. The promise also implies the owner *has* such an address, which is
     * what the host side binds. The mode is a client-side choice — the host is never told which family the
     * receiver arrived on, and listens on both.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun ipv6LinkLocalSupported(): Boolean = p2p?.isGroupOwnerIPv6LinkLocalAddressProvided == true

    // ---- the platform's callbacks as suspend calls ----

    /** Issues [request] and returns the first formation that matches [ssid]/[owner]; a refused request throws. */
    private suspend fun awaitFormation(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        ssid: String,
        owner: Boolean,
        request: (WifiP2pManager.ActionListener) -> Unit,
    ): Formation =
        coroutineScope {
            // Collect before asking, so a formation that lands between the two is never missed; the flow's
            // own opening poll covers one that had already landed.
            val formed = async { formations(manager, ch).first { it != null && it.matches(ssid, owner) } }
            act(if (owner) "createGroup" else "connect", request)
            checkNotNull(formed.await())
        }

    /** The current formation after every connection-changed broadcast, starting with one poll. */
    private fun formations(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): Flow<Formation?> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) trySend(Unit)
                    }
                }
            val registered =
                runCatching {
                    ContextCompat.registerReceiver(
                        app,
                        receiver,
                        IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.isSuccess
            trySend(Unit)
            awaitClose { if (registered) runCatching { app.unregisterReceiver(receiver) } }
        }.map { readFormation(manager, ch) }

    private suspend fun readFormation(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): Formation? {
        val info = suspendCancellableCoroutine { cont -> manager.requestConnectionInfo(ch) { cont.resume(it) } }
        if (info == null || !info.groupFormed) return null
        val address = info.groupOwnerAddress ?: return null
        val group = groupInfo(manager, ch) ?: return null
        val nif = runCatching { NetworkInterface.getByName(group.`interface`) }.getOrNull()
        return Formation(info, group, scopedTo(address, nif))
    }

    private suspend fun groupInfo(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ): WifiP2pGroup? = suspendCancellableCoroutine { cont -> manager.requestGroupInfo(ch) { cont.resume(it) } }

    private suspend fun act(
        what: String,
        call: (WifiP2pManager.ActionListener) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        call(
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            DirectWifiException("$what failed: ${reasonName(reason)}", refusalFor(reason)),
                        )
                    }
                }
            },
        )
    }

    private fun refusalFor(reason: Int): TransferRefusal =
        when {
            reason == WifiP2pManager.BUSY -> TransferRefusal.Hotspot
            reason == WifiP2pManager.ERROR && backgrounded() -> TransferRefusal.Background
            else -> TransferRefusal.NoWifiDirect
        }

    /**
     * Whether a refused call is most likely the API 29-32 background gap rather than a device that cannot do
     * Wi-Fi Direct at all. Below `NEARBY_WIFI_DEVICES` (33+) the framework gates P2P on the *location* AppOp,
     * which a foreground-only grant does not satisfy off-screen — and the sender hosts when an ACCEPT lands,
     * which may be minutes after the user left. Android 10 gates it too: a Galaxy A51 on 29 refused every group with
     * "UID … has no location permission" while nothing of Knit's was on screen, and formed every one with a screen up
     * (Device Farm, 2026-11-21). Telling them to open Knit is the only fix this cut carries; a foreground service of
     * our own declaring the `location` type would be the other one.
     */
    private fun backgrounded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> reason.toString()
        }

    /**
     * Every usable address on the group interface, owner-address first. Both families are returned: the IPv4
     * one a DHCP client reaches, and the IPv6 link-local one a client that joined with link-local
     * provisioning reaches. Falls back to [fallback] alone when the interface cannot be read.
     */
    private fun addressesOf(
        group: WifiP2pGroup,
        fallback: InetAddress,
    ): List<GroupAddress> {
        val nif = runCatching { NetworkInterface.getByName(group.`interface`) }.getOrNull()
        val found =
            nif
                ?.interfaceAddresses
                ?.mapNotNull { entry ->
                    val address = entry.address ?: return@mapNotNull null
                    if (address.isAnyLocalAddress || address.isLoopbackAddress) return@mapNotNull null
                    GroupAddress(scopedTo(address, nif), entry.networkPrefixLength.toInt())
                }.orEmpty()
                .sortedBy { it.address != fallback }
        return found.ifEmpty { listOf(GroupAddress(fallback, DEFAULT_PREFIX_LENGTH)) }
    }

    /**
     * An IPv6 link-local address is only routable with the interface it belongs to attached to it. The
     * platform may hand back a bare `fe80::…`, and `NetworkInterface` may too, so pin the scope before the
     * address is used to bind or connect.
     */
    private fun scopedTo(
        address: InetAddress,
        nif: NetworkInterface?,
    ): InetAddress {
        val v6 = address as? Inet6Address ?: return address
        if (nif == null || !v6.isLinkLocalAddress || v6.scopeId != 0) return address
        return runCatching { Inet6Address.getByAddress(null, v6.address, nif) as InetAddress }.getOrDefault(address)
    }

    private fun frequencyOf(group: WifiP2pGroup): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) group.frequency else 0

    private companion object {
        /** `WifiScanner.WIFI_BAND_BOTH_WITH_DFS` (2.4 GHz and 5 GHz with DFS), which `getUsableChannels` takes. */
        private const val BAND_24_5_WITH_DFS = 7

        const val TAG = "KnitTransfer"
        const val WAKE_LOCK_TAG = "knit:transfer"

        /**
         * After [MeshTransport.pause]: the Aware interface's own teardown plus a margin. Measured on a Pixel 7
         * and a Pixel 9 the teardown (`pause` → `aware_nmi0 is deactivated`) takes 120-146 ms, so this is
         * about five times the observed cost. It is the floor under how fast a group can come up, and it is
         * paid on both sides, so it was worth measuring rather than guessing: too short and `createGroup`
         * answers BUSY, which surfaces as a Hotspot refusal.
         */
        const val SETTLE_MS = 750L
        const val RELEASE_MS = 5_000L
        const val WAKE_LOCK_MAX_MS = 2 * 60 * 60_000L
        const val DEFAULT_PREFIX_LENGTH = 24

        // A DHCP lease on the group normally lands within a second of formation; past this the join is retried.
        const val LEASE_WAIT_MS = 5_000L
        const val LEASE_POLL_MS = 100L
    }
}
