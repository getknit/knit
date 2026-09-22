package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.BuildConfig

/** What the attach budget looks like right now, for the debug bridge's reply. */
internal data class NanAttachSnapshot(
    val attached: Boolean,
    val streak: Int,
    val total: Int,
    val abandoned: Boolean,
    val retryInMs: Long,
)

/** What the responder re-file budget looks like right now (ADR 2026-09.bgk3), for the debug bridge's reply. */
internal data class NanResponderSnapshot(
    val filed: Boolean,
    val refusals: Int,
    val cycles: Int,
    val armed: Int,
)

/** What `…debug.NANMSG` injects into the coordination-plane callbacks: nothing, no callback, or a failure. */
internal enum class NanMsgFault { NONE, SWALLOW, FAIL }

/** The ack bookkeeping behind [NanMessagePlanePolicy] right now (ADR 2026-09.jjhg), for `…debug.NANMSG`'s reply. */
internal data class NanMsgPlaneSnapshot(
    val unanswered: Int,
    val oldestUnansweredMs: Long,
    val sinceAckMs: Long,
    val failsSinceAck: Int,
    val episodeMs: Long,
    val cycles: Int,
    val fault: NanMsgFault,
)

/**
 * The initiator failsafe's hooks (`…debug.NANINIT`, work item #78): [blip] runs the transport's own Wi-Fi
 * lost-then-available handlers so a strike can be earned on a phone whose Wi-Fi never drops, [reset] is the
 * user's "Try again", [forceProbe] makes a held role's daily probe due now (false when not held), [status] is
 * the policy's snapshot.
 */
internal data class NanInitiatorHooks(
    val blip: () -> Unit,
    val reset: () -> Unit,
    val forceProbe: () -> Boolean,
    val status: () -> NanInitiatorSnapshot,
)

/**
 * Debug-only fault injection for [WifiAwareTransport]'s attach path, so getknit/Knit#9 can be reproduced on
 * hardware that does not have the bug — and, since work item #77, for its responder's `onUnavailable`, so the
 * re-file pacing of [NanResponderPolicy] can be driven on a device whose framework fulfils the request just fine.
 *
 * The failure it reproduces is not something a lab Pixel can be talked into. It needs a vendor HAL with no
 * STA+NAN interface combination, so that `attach` fails at `bestIfaceCreationProposal is null` while
 * `WifiAwareManager.isAvailable` still reports `true` — and, for ADR 055's half of it, a chipset that
 * re-broadcasts Aware state after each refusal, which is what let a refusal drive its own retry budget
 * refund. Neither is reachable from outside the app: another app holding an Aware session does not block
 * ours (the framework multiplexes clients onto one interface), and `ACTION_WIFI_AWARE_STATE_CHANGED` is a
 * protected broadcast that would not reach our `RECEIVER_NOT_EXPORTED` receiver anyway.
 *
 * So both halves are injected at the seam instead, driven by `…debug.NANFAIL` and `…debug.NANSTORM`:
 * [shouldFailAttach] forces an attach down its failure path, and [notifyAvailability] calls exactly what the
 * availability receiver calls, including the `lastAvailable` edge check that ADR 055 added.
 *
 * **Inert unless [BuildConfig.DEBUG]**, checked in every method rather than at the call sites, and the
 * transport binds nothing in a release build — the `ModelLoadGuard.injectDebugFaultIfArmed` pattern, minus
 * its build-time flag, because a lab harness wants to arm and re-arm this while the app is running.
 *
 * **What it does not reproduce: the leak itself.** A forced failure returns before `mgr.attach`, so no binder
 * objects are stranded in `system_server` and `dumpsys activity binder-proxies` stays flat. What it measures
 * is how many attaches the bounds *allow*, which is that number divided by two — the right assertion for
 * ADR 055, and not a demonstration that ADR 052's leak is gone.
 */
@Suppress("TooManyFunctions") // one function per lab knob (arm / status / inject); folding them would only hide the knobs
internal object NanFaultInjector {
    @Volatile private var failuresLeft = 0

    @Volatile private var availability: ((Boolean) -> Unit)? = null

    @Volatile private var snapshot: (() -> NanAttachSnapshot)? = null

    // The responder half (`…debug.NANREFUSE`): how many verdicts are still armed, the transport's hook that
    // delivers one to the live responder callback now, and its snapshot of the re-file budget.
    @Volatile private var refusalsLeft = 0

    @Volatile private var refuseResponder: (() -> Boolean)? = null

    @Volatile private var responderSnapshot: (() -> NanResponderSnapshot)? = null

    // `…debug.NANICM`: flips Instant Communication Mode on the live transport and cycles the session so the
    // new publish/subscribe configs take. Returns what the mode now is (false when the hardware lacks it).
    @Volatile private var instantMode: ((Boolean) -> Boolean)? = null

    // `…debug.NANDIAL`: initiate an NDP to a discovered peer regardless of the id tie-break. Returns a verdict.
    @Volatile private var dial: ((String) -> String)? = null

    // `…debug.NANINIT`: the initiator failsafe (work item #78) — one bundle, so `bind` stays under detekt's arity.
    @Volatile private var initiator: NanInitiatorHooks? = null

    // `…debug.NANMSG`: the coordination-plane watchdog (work item #81). The fault is read on every send callback,
    // so a lab phone can be walked into either freeze signature without a burst; bound beside [bind].
    @Volatile private var msgFault = NanMsgFault.NONE

    @Volatile private var msgPlane: (() -> NanMsgPlaneSnapshot)? = null

    /** Whether a transport is running and has bound its hooks — false in release, and before `start()`. */
    val bound: Boolean get() = BuildConfig.DEBUG && availability != null

    /** Called by [WifiAwareTransport.start]; `null`s clear it on `stop()`. */
    fun bind(
        onAvailability: ((Boolean) -> Unit)?,
        status: (() -> NanAttachSnapshot)?,
        onRefuseResponder: (() -> Boolean)? = null,
        responderStatus: (() -> NanResponderSnapshot)? = null,
        onInstantMode: ((Boolean) -> Boolean)? = null,
        onDial: ((String) -> String)? = null,
        initiatorHooks: NanInitiatorHooks? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        availability = onAvailability
        snapshot = status
        refuseResponder = onRefuseResponder
        responderSnapshot = responderStatus
        instantMode = onInstantMode
        dial = onDial
        initiator = initiatorHooks
        if (onAvailability == null) {
            failuresLeft = 0
            refusalsLeft = 0
        }
    }

    /** Arms the next [count] attaches to take their failure path; 0 disarms. Returns what is now armed. */
    fun armFailures(count: Int): Int {
        if (!BuildConfig.DEBUG) return 0
        failuresLeft = count.coerceAtLeast(0)
        return failuresLeft
    }

    /** Whether this attach should fail without ever reaching `mgr.attach` — consumes one of the armed count. */
    fun shouldFailAttach(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val left = failuresLeft
        if (left <= 0) return false
        failuresLeft = left - 1
        return true
    }

    /**
     * Delivers a synthetic Aware availability notification, exactly as the broadcast receiver would. `true`
     * repeated is the getknit/Knit#9 storm (which must refund nothing); alternating `false`/`true` is the
     * negative control (a genuine radio recovery, which must still refund and reattach promptly).
     */
    fun notifyAvailability(available: Boolean): Boolean {
        val hook = if (BuildConfig.DEBUG) availability else null
        hook?.invoke(available)
        return hook != null
    }

    fun status(): NanAttachSnapshot? = if (BuildConfig.DEBUG) snapshot?.invoke() else null

    /**
     * Arms the next [count] responder requests to be declared unfulfillable ~10 ms after they are filed — the
     * field shape of work item #77, where the framework answered every fresh request in about that — and, if a
     * responder is filed right now, delivers the first verdict to it at once. 0 disarms. Returns what is armed.
     * The verdict is our own callback's `onUnavailable`, so the request really is unregistered and re-filed;
     * what is not reproduced is the framework's reason, which is the one thing the capture did not hold.
     */
    fun armResponderRefusals(count: Int): Int {
        if (!BuildConfig.DEBUG) return 0
        refusalsLeft = count.coerceAtLeast(0)
        if (refusalsLeft > 0 && refuseResponder?.invoke() == true) refusalsLeft--
        return refusalsLeft
    }

    /** Whether the responder request just filed should be declared unfulfillable — consumes one armed verdict. */
    fun shouldRefuseResponder(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val left = refusalsLeft
        if (left <= 0) return false
        refusalsLeft = left - 1
        return true
    }

    /**
     * Turns Instant Communication Mode on or off for the running transport (a lab knob: an ICM-camped Pixel
     * fleet against a non-ICM peer is one hypothesis for the Pixel 3's unicast blindness). `null` when no
     * transport is bound; otherwise the mode now in force, which is `false` on hardware without ICM.
     */
    fun setInstantMode(on: Boolean): Boolean? = if (BuildConfig.DEBUG) instantMode?.invoke(on) else null

    /**
     * Initiates an NDP to [peerNodeId] from the running transport **ignoring the id tie-break** (a lab knob:
     * the only way to make the smaller node knock on a larger node's responder, which is how a one-directional
     * data-path failure is told from a dead pair). `null` when no transport is bound; else a short verdict.
     */
    fun dial(peerNodeId: String): String? = if (BuildConfig.DEBUG) dial?.invoke(peerNodeId) else null

    fun responderStatus(): NanResponderSnapshot? = if (BuildConfig.DEBUG) responderSnapshot?.invoke()?.copy(armed = refusalsLeft) else null

    /**
     * Injects one Wi-Fi blip — the STA lost and back — through the transport's own handlers, so a strike is
     * earned exactly as the field earns it (an initiate of ours within the window, no link since). `null` when no
     * transport is bound.
     */
    fun blipWifi(): Boolean? =
        if (BuildConfig.DEBUG) {
            initiator?.let {
                it.blip()
                true
            }
        } else {
            null
        }

    /** The user's "Try again" for the initiator hold, from the shell. `null` when no transport is bound. */
    fun resetInitiator(): Boolean? =
        if (BuildConfig.DEBUG) {
            initiator?.let {
                it.reset()
                true
            }
        } else {
            null
        }

    /** Makes a held role's daily probe due on the next tick. False when the role is not held; `null` when unbound. */
    fun forceInitiatorProbe(): Boolean? = if (BuildConfig.DEBUG) initiator?.forceProbe?.invoke() else null

    fun initiatorStatus(): NanInitiatorSnapshot? = if (BuildConfig.DEBUG) initiator?.status?.invoke() else null

    /** Called by [WifiAwareTransport.start] with its snapshot; `null` on `stop()` also disarms the fault. */
    fun bindMsgPlane(status: (() -> NanMsgPlaneSnapshot)?) {
        if (!BuildConfig.DEBUG) return
        msgPlane = status
        if (status == null) msgFault = NanMsgFault.NONE
    }

    /** What the transport's send callbacks do with the framework's answer right now. [NanMsgFault.NONE] in release. */
    fun msgFault(): NanMsgFault = if (BuildConfig.DEBUG) msgFault else NanMsgFault.NONE

    /**
     * Arms one of the two freeze signatures of work item #81 on the running transport: [NanMsgFault.SWALLOW] drops
     * every send callback (the blocked framework queue), [NanMsgFault.FAIL] turns every ack into a failure (dead
     * unicast). The watchdog's verdict and its session cycle then run for real; the fault stays armed until
     * [NanMsgFault.NONE], so a cured cycle is only visible once it is disarmed. Returns what is now armed.
     */
    fun setMsgFault(fault: NanMsgFault): NanMsgFault {
        if (!BuildConfig.DEBUG) return NanMsgFault.NONE
        msgFault = fault
        return fault
    }

    fun msgPlaneStatus(): NanMsgPlaneSnapshot? = if (BuildConfig.DEBUG) msgPlane?.invoke()?.copy(fault = msgFault) else null
}
