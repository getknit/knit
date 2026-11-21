package app.getknit.knit.transfer

import java.net.InetAddress
import java.security.SecureRandom

/**
 * The one-shot Wi-Fi Direct group a transfer rides. The sender hosts it, the receiver joins it by these
 * credentials alone (no discovery, no dialog), and both forget it afterwards.
 */
data class GroupCredentials(
    val ssid: String,
    val passphrase: String,
) {
    companion object {
        /** Every group name starts with this — the platform requires `DIRECT-xy`, two alphanumerics, then anything. */
        const val SSID_PREFIX = "DIRECT-"
        const val SSID_MAX_BYTES = 32
        const val PASSPHRASE_MIN = 8
        const val PASSPHRASE_MAX = 63

        /** The two characters after `DIRECT-` on every group we host — what a leftover of ours is recognised by. */
        private const val SSID_HEAD = "kn"
        private const val SSID_TAIL_CHARS = 8
        private const val PASSPHRASE_CHARS = 24
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** Fresh random credentials: `DIRECT-kn-abcdefgh` and a 24-character alphanumeric passphrase (~143 bits). */
        fun random(rng: SecureRandom = SecureRandom()): GroupCredentials =
            GroupCredentials(
                ssid = "$SSID_PREFIX$SSID_HEAD-${alnum(rng, SSID_TAIL_CHARS)}",
                passphrase = alnum(rng, PASSPHRASE_CHARS),
            )

        /** Whether [ssid] names a group this app hosted (a crash can leave one up; a foreign one is never touched). */
        fun isOurs(ssid: String?): Boolean = ssid?.startsWith("$SSID_PREFIX$SSID_HEAD-") == true

        /**
         * Whether [ssid]/[passphrase] are something the platform would accept — what a READY or a call's invite is
         * checked against. The name is held to `WifiP2pConfig.Builder.setNetworkName`'s own pattern, not just the
         * prefix: one it refuses throws from the builder on the joining side, past every `DirectWifiException` catch, so
         * a transfer never reaches Failed. The passphrase is WPA's 8 to 63 printable ASCII characters.
         */
        fun isValid(
            ssid: String?,
            passphrase: String?,
        ): Boolean =
            ssid != null &&
                PLATFORM_SSID.matches(ssid) &&
                ssid.toByteArray(Charsets.UTF_8).size <= SSID_MAX_BYTES &&
                passphrase != null &&
                passphrase.length in PASSPHRASE_MIN..PASSPHRASE_MAX &&
                passphrase.all { it in PRINTABLE_ASCII }

        /** `WifiP2pConfig.Builder.setNetworkName`'s check, verbatim (AOSP): `DIRECT-`, two alphanumerics, then anything. */
        private val PLATFORM_SSID = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

        private val PRINTABLE_ASCII = ' '..'~'

        private fun alnum(
            rng: SecureRandom,
            length: Int,
        ): String = buildString(length) { repeat(length) { append(ALPHANUMERIC[rng.nextInt(ALPHANUMERIC.length)]) } }
    }
}

/** One address the group interface carries, and the subnet it defines. */
data class GroupAddress(
    val address: InetAddress,
    val prefixLength: Int,
)

/**
 * A group this device is hosting. [addresses] is every address its own group interface carries — an IPv4
 * group-owner address, and the IPv6 link-local the kernel puts on any interface. The listener binds all of
 * them and admits only clients arriving from one of their subnets, because which family a client turns up
 * on is the *client's* choice: a receiver on Android 13+ may join with IPv6 link-local provisioning and
 * never take a DHCP lease at all. [interfaceName] is the group interface (`p2p-wlan0-0`), when it could be read
 * — what a call hands libwebrtc as the group's network.
 */
class HostedGroup(
    val addresses: List<GroupAddress>,
    val frequencyMhz: Int,
    val interfaceName: String? = null,
)

/**
 * A group this device has joined: where the host listens (IPv4, or a scoped IPv6 link-local), and the group
 * interface it joined on, when it could be read.
 */
class JoinedGroup(
    val ownerAddress: InetAddress,
    val interfaceName: String? = null,
)

/** A host/join attempt that did not produce a group; [refusal] names a user-facing cause when there is one. */
class DirectWifiException(
    message: String,
    val refusal: TransferRefusal? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The radio seam of a direct transfer — the only thing between the pure [TransferManager] and
 * `android.net.wifi.p2p`. The Android implementation (`AndroidDirectWifi`) also owns the Wi-Fi Aware
 * pause/resume around the group and the wake lock, so the manager never learns about either.
 */
interface DirectWifi {
    /** Why a transfer cannot start on this device right now, or null when it can. A snapshot, re-read per attempt. */
    fun refusal(): TransferRefusal?

    /**
     * Whether this device may host a group at all: false on a phone where hosting is known to take the phone down
     * (ADR 2026-10.w3dw). A device fact, not a moment's state; joining is never refused for it.
     */
    fun mayHost(): Boolean = true

    /**
     * Why this device cannot *host* a group right now, or null when it can: [TransferRefusal.CannotHost] where it may
     * never host, ahead of anything passing, else [refusal]. A sender or a caller asks this; a receiver or a callee asks
     * [refusal].
     */
    fun hostRefusal(): TransferRefusal? = if (mayHost()) refusal() else TransferRefusal.CannotHost

    /** The channels this device's Wi-Fi sits on and can host or join a group on, as far as the platform says. */
    fun channels(): DirectChannelView = DirectChannelView()

    /**
     * Stands up the group and returns once it is formed (within [timeoutMs]); throws [DirectWifiException] otherwise.
     * [peerChannels] are the frequencies the joiner said it can join on (ADR 2026-11.8zm5), null when it did not say:
     * the group goes where it can find it ([DirectChannels.choose]).
     */
    suspend fun host(
        credentials: GroupCredentials,
        timeoutMs: Long,
        peerChannels: Set<Int>? = null,
    ): HostedGroup

    /**
     * One attempt to join [credentials] (within [attemptMs]); throws [DirectWifiException] when it does not form.
     * [ipv4] takes a DHCP lease even where IPv6 link-local provisioning is offered, and returns only once the lease
     * is on the interface: libwebrtc never gathers on an IPv6 link-local address, so a call joins this way.
     */
    suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
        ipv4: Boolean = false,
    ): JoinedGroup

    /** Leaves/removes whatever group is up and hands the radio back. Idempotent; called from every terminal path. */
    suspend fun release()

    /**
     * Removes a group *this app* left on air — what a crash or a force-stop mid-transfer leaves behind, since
     * nothing runs [release] then. Safe at any time and cheap when there is nothing to do: it never lends the
     * radio out, never touches a group it did not name, and stands aside while a transfer holds the radio.
     */
    suspend fun sweep() {}
}

/** Whether this address shares the first [prefixLength] bits with [network] — the "came in over the group" gate. */
fun InetAddress.inPrefix(
    network: InetAddress,
    prefixLength: Int,
): Boolean {
    val a = address
    val b = network.address
    if (a.size != b.size) return false
    val fullBytes = prefixLength / Byte.SIZE_BITS
    val restBits = prefixLength % Byte.SIZE_BITS
    for (i in 0 until minOf(fullBytes, a.size)) if (a[i] != b[i]) return false
    if (restBits == 0 || fullBytes >= a.size) return true
    val mask = (0xFF shl (Byte.SIZE_BITS - restBits)) and 0xFF
    return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
}
