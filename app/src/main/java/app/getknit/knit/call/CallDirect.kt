package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.transfer.GroupAddress
import app.getknit.knit.transfer.GroupCredentials
import app.getknit.knit.transfer.inPrefix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.net.InetAddress
import java.util.Base64

/**
 * When a call between neighbours raises its Wi-Fi Direct group (docs/VOICE_CALLS.md §3.10); a debug-bridge toggle.
 * "Never" is not a mode: it is Diagnostics' persisted switch (`SettingsStore.debugDirectCallsOff`), which
 * [DirectCallPath] reads, and which the bridge's `mode off` writes.
 */
enum class DirectMode {
    /** The callee joins while it rings, so an answer connects over the group at once. */
    PREWARM,

    /** The callee joins only once it answers: nothing on the radio changes for a call that is never picked up. */
    ANSWER,
}

/** The group's name and passphrase, when [DirectInvite.s]/[DirectInvite.p] are ones the platform would accept. */
fun DirectInvite.credentials(): GroupCredentials? =
    if (GroupCredentials.isValid(s, p)) GroupCredentials(checkNotNull(s), checkNotNull(p)) else null

/** The lane's 32-byte secret, when [DirectInvite.k] decodes to one. */
fun DirectInvite.secret(): ByteArray? =
    k?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == DIRECT_SECRET_BYTES }

/** Whether every part of this invite is usable: a group the platform accepts, an unprivileged port, a whole secret. */
fun DirectInvite.isUsable(): Boolean = credentials() != null && (o ?: 0) in DIRECT_PORT_MIN..DIRECT_PORT_MAX && secret() != null

const val DIRECT_SECRET_BYTES = 32
private const val DIRECT_PORT_MIN = 1024
private const val DIRECT_PORT_MAX = 65_535

/**
 * The Wi-Fi Direct group a call's media can ride: its interface (`p2p-wlan0-0`) when known, and the subnets it
 * carries. What the media layer gathers on and what tells a group candidate from any other.
 */
class DirectLink(
    val interfaceName: String?,
    val subnets: List<GroupAddress>,
) {
    /** Whether [ip] — a candidate's address, as an IP literal — sits on this group. A hostname is never on it. */
    fun carries(ip: String): Boolean {
        if (!IP_LITERAL.matches(ip)) return false
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        return subnets.any { address.inPrefix(it.address, it.prefixLength) }
    }

    private companion object {
        // Digits and dots (IPv4) or hex, colons and dots (IPv6) only, so getByName never resolves a name.
        val IP_LITERAL = Regex("""^(\d{1,3}(\.\d{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)$""")
    }
}

/** What a call's lane hands up: a signal from the peer, or the group candidates it gathered. */
sealed interface LaneEvent {
    val peerId: String

    class Signal(
        override val peerId: String,
        val payload: CallPayload,
    ) : LaneEvent

    class Candidates(
        override val peerId: String,
        val callId: String,
        val lines: List<String>,
    ) : LaneEvent
}

/**
 * The direct leg of a call between neighbours (§3.10): a Wi-Fi Direct group the two phones raise, and a sealed lane
 * over it that carries the call's signals beside the mesh. Pure seam: `DirectCallPath` over the shared radio in the
 * app, a fake in `CallManagerTest`. Nothing here blocks: every radio and socket step runs on the leg's own job, so
 * [CallManager.onSignal] never waits on it.
 */
interface CallDirect {
    /**
     * The group a caller would host for a call [callId] to [peerId], or null when this call should not go direct.
     * Minting raises nothing; the callee's "joining" is what makes the caller [host].
     */
    suspend fun invite(
        callId: String,
        peerId: String,
    ): DirectInvite?

    /** Whether this phone could join [peerId]'s group for a call now; [prewarm] asks for a join while it only rings. */
    fun canJoin(
        peerId: String,
        prewarm: Boolean,
    ): Boolean

    /** The callee's side: joins [invite]'s group after a grace, then dials the lane. One attempt per call. */
    fun join(
        callId: String,
        peerId: String,
        invite: DirectInvite,
    )

    /** The caller's side: hosts [invite]'s group and listens for the lane. One attempt per call. */
    fun host(
        callId: String,
        peerId: String,
        invite: DirectInvite,
    )

    /** Sends [payload] over [callId]'s lane too, queued until the lane is up; nothing when the call has no leg. */
    fun mirror(
        callId: String,
        payload: CallPayload,
    )

    /** Our group candidates for [callId], lane-only (they mean nothing to a peer outside the group). */
    fun candidates(
        callId: String,
        lines: List<String>,
    )

    /**
     * Ends [callId]'s leg: [terminal] (the frame that tells the peer) goes down the lane first when it is up, then the
     * lane closes and the radio goes back to the mesh. Idempotent, and safe whatever stage the leg reached.
     */
    fun end(
        callId: String,
        terminal: CallPayload?,
    )

    /** Whether [callId]'s leg is still on its way up: its group or its lane not there yet, and not given up. */
    fun pending(callId: String): Boolean

    /** Signals and candidates the lanes carried in. */
    val inbound: Flow<LaneEvent>

    /** The group the live leg rides, once it is up; what the media layer gathers on. */
    val link: StateFlow<DirectLink?>

    /** No direct leg, ever: a build or a test without the radio. */
    object None : CallDirect {
        private val noLink = MutableStateFlow<DirectLink?>(null)

        override suspend fun invite(
            callId: String,
            peerId: String,
        ): DirectInvite? = null

        override fun canJoin(
            peerId: String,
            prewarm: Boolean,
        ): Boolean = false

        override fun join(
            callId: String,
            peerId: String,
            invite: DirectInvite,
        ) = Unit

        override fun host(
            callId: String,
            peerId: String,
            invite: DirectInvite,
        ) = Unit

        override fun mirror(
            callId: String,
            payload: CallPayload,
        ) = Unit

        override fun candidates(
            callId: String,
            lines: List<String>,
        ) = Unit

        override fun end(
            callId: String,
            terminal: CallPayload?,
        ) = Unit

        override fun pending(callId: String): Boolean = false

        override val inbound: Flow<LaneEvent> = emptyFlow()

        override val link: StateFlow<DirectLink?> = noLink.asStateFlow()
    }
}
