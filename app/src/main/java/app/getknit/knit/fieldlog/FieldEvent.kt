package app.getknit.knit.fieldlog

/**
 * Where an event writes its fields. There is deliberately **no string field**: a value is a number, a
 * boolean, an enum constant's name (compiled into the APK, the same on every install), a peer or a relay, and
 * each of those two is written as its per-log pseudonym ([Pseudonyms]), never its node id or its URL. So nothing a user typed, no name,
 * no node id, no hash and no address can reach the journal — redaction holds by construction (ADR
 * 2026-10.a239, `docs/FIELD_LOG.md`), and `FieldEventSchemaTest` pins every event's line.
 *
 * Field names are the caller's compile-time constants; they are the schema.
 */
interface FieldWriter {
    fun num(
        name: String,
        value: Long,
    )

    fun flag(
        name: String,
        value: Boolean,
    )

    fun enum(
        name: String,
        value: Enum<*>,
    )

    /** A peer, by node id: the writer emits the log's pseudonym for it. */
    fun peer(
        name: String,
        nodeId: String,
    )

    /** A relay (a spool), by its redacted URL (`SpoolUrl.redact`): the writer emits the log's pseudonym for it. */
    fun relay(
        name: String,
        url: String,
    )
}

/**
 * One thing the phone's radios did, kept in the field log. Each kind has a short wire name ([kind], the
 * line's `e`) and writes its own fields; the time (`t`) is stamped by [FieldLog] at record time.
 *
 * Adding a kind: give it a new [kind], write only numbers / flags / enums / peers, add its golden line to
 * `FieldEventSchemaTest`, and document it in `docs/FIELD_LOG.md`.
 */
sealed interface FieldEvent {
    val kind: String

    fun write(out: FieldWriter)

    /** The mesh session started ([MeshManager.start]); [versionCode] dates a log that spans an upgrade. */
    data class MeshOn(
        val versionCode: Long,
    ) : FieldEvent {
        override val kind get() = "mesh_on"

        override fun write(out: FieldWriter) = out.num("vc", versionCode)
    }

    /** The mesh session stopped (Stop, a pause, or a restart's first half). */
    data object MeshOff : FieldEvent {
        override val kind get() = "mesh_off"

        override fun write(out: FieldWriter) = Unit
    }

    /** The phone's power state changed — the input every scan cadence and pace reads. */
    data class Power(
        val screenOn: Boolean,
        val charging: Boolean,
        val batteryLow: Boolean,
    ) : FieldEvent {
        override val kind get() = "power"

        override fun write(out: FieldWriter) {
            out.flag("screen", screenOn)
            out.flag("charging", charging)
            out.flag("low", batteryLow)
        }
    }

    /**
     * How Android treats the app's background battery use ([batteryEdges]): the App battery usage position
     * (`BackgroundBattery`, where Restricted wins, ADR 2026-09.gc3m), whether the battery-optimization exemption is
     * held ([exempt], which can read true beneath Restricted), and the app standby bucket. An OEM's own battery
     * manager is visible to none of them.
     */
    data class Battery(
        val usage: Enum<*>,
        val exempt: Boolean,
        val bucket: StandbyBucket,
    ) : FieldEvent {
        override val kind get() = "battery"

        override fun write(out: FieldWriter) {
            out.enum("usage", usage)
            out.flag("exempt", exempt)
            out.enum("bucket", bucket)
        }
    }

    /**
     * One radio's status changed: health, live links, or a flag. [nearby] rides along but does not by itself
     * make an event (it is smoothed and moves constantly); [FieldLog] samples it with the counters instead.
     */
    data class Radio(
        val radio: Enum<*>,
        val health: Enum<*>,
        val linked: Int,
        val nearby: Int,
        val contended: Boolean,
        val initiatorHeld: Boolean,
    ) : FieldEvent {
        override val kind get() = "radio"

        override fun write(out: FieldWriter) {
            out.enum("radio", radio)
            out.enum("health", health)
            out.num("linked", linked.toLong())
            out.num("nearby", nearby.toLong())
            out.flag("contended", contended)
            out.flag("held", initiatorHeld)
        }
    }

    /**
     * What the mesh's counters moved by since the previous [Counters] (or since the process started, for the
     * first of a process). Only the counters that moved are written; [deltas] keys are [FieldCounters]'
     * compile-time names.
     */
    data class Counters(
        val deltas: Map<String, Long>,
    ) : FieldEvent {
        override val kind get() = "counters"

        override fun write(out: FieldWriter) = deltas.forEach { (name, value) -> out.num(name, value) }
    }

    /**
     * A Bluetooth link came up. [dialMs] is how long our dial took, connect to HELLO — null for a link the peer
     * dialed; [sighted] is false for a peer admitted without a sighting (an iPhone, ADR 2026-09.shzv). [relinkMs] —
     * filled by [FieldLog], not the caller — is how long this peer was without a link since its last [BtDown] in this
     * log, null for a first link.
     */
    data class BtUp(
        val peer: String,
        val dialMs: Long?,
        val sighted: Boolean,
        val links: Int,
        val relinkMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "bt_up"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("dialed", dialMs != null)
            dialMs?.let { out.num("dial", it) }
            out.flag("sighted", sighted)
            out.num("links", links.toLong())
            relinkMs?.let { out.num("relink", it) }
        }
    }

    /**
     * A Bluetooth link ended after [lifetimeMs]. [phy] and [rssi] are the link's last PHY and link RSSI, known only
     * while the link had a PHY handle (the Coded experiment, ADR 2026-10.yvn6); null otherwise.
     */
    data class BtDown(
        val peer: String,
        val reason: BtDownReason,
        val lifetimeMs: Long,
        val links: Int,
        val phy: Enum<*>? = null,
        val rssi: Int? = null,
    ) : FieldEvent {
        override val kind get() = "bt_down"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.enum("reason", reason)
            out.num("life", lifetimeMs)
            out.num("links", links.toLong())
            phy?.let { out.enum("phy", it) }
            rssi?.let { out.num("rssi", it.toLong()) }
        }
    }

    /** A Bluetooth dial failed after [durationMs]; [streak] is the peer's consecutive failures, this one included. */
    data class BtFail(
        val peer: String,
        val reason: Enum<*>,
        val durationMs: Long,
        val streak: Int,
    ) : FieldEvent {
        override val kind get() = "bt_fail"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.enum("reason", reason)
            out.num("dur", durationMs)
            out.num("streak", streak.toLong())
        }
    }

    /** A Bluetooth link stepped PHY (ADR 2026-10.yvn6): to Coded, or back up to 1M/2M, at link RSSI [rssi]. */
    data class BtPhy(
        val peer: String,
        val toCoded: Boolean,
        val rssi: Int?,
    ) : FieldEvent {
        override val kind get() = "bt_phy"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("coded", toCoded)
            rssi?.let { out.num("rssi", it.toLong()) }
        }
    }

    /**
     * One live Bluetooth link at a sample ([FieldLog.SAMPLE_EVERY_MS]): its age, and what its handles last heard —
     * PHY and link RSSI from a PHY handle (the Coded experiment), interval and MTU from a tuner (a file crossed it),
     * and whether it [earned] the boosted file budget (ADR 2026-10.2da4). Null where no handle heard it.
     */
    data class BtLink(
        val peer: String,
        val ageMs: Long,
        val feeding: Boolean,
        val receiving: Boolean,
        val phy: Enum<*>? = null,
        val rssi: Int? = null,
        val intervalUs: Int? = null,
        val mtu: Int? = null,
        val earned: Boolean? = null,
    ) : FieldEvent {
        override val kind get() = "bt_link"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.num("age", ageMs)
            out.flag("tx", feeding)
            out.flag("rx", receiving)
            phy?.let { out.enum("phy", it) }
            rssi?.let { out.num("rssi", it.toLong()) }
            intervalUs?.let { out.num("interval", it.toLong()) }
            mtu?.let { out.num("mtu", it.toLong()) }
            earned?.let { out.flag("earned", it) }
        }
    }

    /**
     * One sighted Bluetooth peer at a sample: its smoothed advert RSSI on the 1M scale, each PHY's own reading, and
     * the 1M listening its Coded hits have outlasted its 1M ones by ([codedLagMs], ADR 2026-10.yvn6).
     */
    data class BtPeer(
        val peer: String,
        val linked: Boolean,
        val rssi: Int,
        val rssi1m: Int? = null,
        val rssiCoded: Int? = null,
        val codedLagMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "bt_peer"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("linked", linked)
            out.num("rssi", rssi.toLong())
            rssi1m?.let { out.num("rssi1m", it.toLong()) }
            rssiCoded?.let { out.num("rssiCoded", it.toLong()) }
            codedLagMs?.let { out.num("codedLag", it) }
        }
    }

    /** The Bluetooth plane at a sample: links, the LE connections the phone holds, sightings, and the scan's mode. */
    data class BtScan(
        val links: Int,
        val leLinks: Int,
        val sighted: Int,
        val hunting: Boolean,
        val codedMode: Enum<*>,
    ) : FieldEvent {
        override val kind get() = "bt_scan"

        override fun write(out: FieldWriter) {
            out.num("links", links.toLong())
            out.num("le", leLinks.toLong())
            out.num("sighted", sighted.toLong())
            out.flag("hunting", hunting)
            out.enum("coded", codedMode)
        }
    }

    /**
     * A Bluetooth channel's LE connection outlived the channel past the grace (#142, ADR 2026-10.b8yf): written once per
     * such connection. [linked]: the channel was a link; else it never became one (a refused dialer, a failed HELLO or
     * dial). [armed]: its arm attached (the link-up arm, an iPhone's doorbell, or the arm at the failed channel's end),
     * so the stack ignored the arm rather than the arm failing. [tries]: the releases it got (0 with the arm switched
     * off, and for one handed over as the transport stopped). [cleared]: it went down; else it was still up after the
     * last try. [ageMs]: how long it outlived its channel, to its disconnect or to this record.
     */
    data class BtOrphan(
        val peer: String,
        val linked: Boolean,
        val armed: Boolean,
        val tries: Int,
        val cleared: Boolean,
        val ageMs: Long,
    ) : FieldEvent {
        override val kind get() = "bt_orphan"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("linked", linked)
            out.flag("armed", armed)
            out.num("tries", tries.toLong())
            out.flag("cleared", cleared)
            out.num("age", ageMs)
        }
    }

    /** A Wi-Fi Aware data path came up; [initiator] when this phone requested it, else it served the peer's. */
    data class NanUp(
        val peer: String,
        val initiator: Boolean,
        val links: Int,
    ) : FieldEvent {
        override val kind get() = "nan_up"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("initiator", initiator)
            out.num("links", links.toLong())
        }
    }

    /** A Wi-Fi Aware data path ended after [lifetimeMs]; [clean] when it ended on a quiet sync rather than a reset. */
    data class NanDown(
        val peer: String,
        val initiator: Boolean,
        val lifetimeMs: Long,
        val clean: Boolean,
    ) : FieldEvent {
        override val kind get() = "nan_down"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("initiator", initiator)
            out.num("life", lifetimeMs)
            out.flag("clean", clean)
        }
    }

    /**
     * Low-power mode's posture changed (ADR 2026-10.d44b): [reason] is what holds it on (`LowPowerReason`), null when it
     * went off. While it is on, Wi-Fi Aware is switched off and a move does not heal, so the Aware lines stop on purpose.
     */
    data class LowPower(
        val reason: Enum<*>?,
    ) : FieldEvent {
        override val kind get() = "low_power"

        override fun write(out: FieldWriter) {
            out.flag("on", reason != null)
            reason?.let { out.enum("why", it) }
        }
    }

    /** The Wi-Fi Aware session was torn down and re-attached — the plane's own cure for a wedge (ADR 2026-09.jjhg). */
    data object NanCycle : FieldEvent {
        override val kind get() = "nan_cycle"

        override fun write(out: FieldWriter) = Unit
    }

    /**
     * What Wi-Fi Aware's configs may ask of Instant Communication Mode changed (`NanIcmPolicy`, GitHub #44): [on] whether
     * an ICM-enabled config may be built, [band] the band it asks for (only when on; asked, not granted — `dumpsys
     * wifiaware`'s `instantModeChannel` is the grant), [why] the rule that decided (`NanIcmPolicy.Reason`), and [staMhz]
     * the phone's own Wi-Fi frequency when one is known. Written on a change of on, band or why only: a STA that moves
     * channel under an unchanged verdict writes nothing. Nothing is written on a phone without ICM.
     */
    data class NanIcm(
        val on: Boolean,
        val band: Enum<*>?,
        val why: Enum<*>,
        val staMhz: Int?,
    ) : FieldEvent {
        override val kind get() = "nan_icm"

        override fun write(out: FieldWriter) {
            out.flag("on", on)
            band?.takeIf { on }?.let { out.enum("band", it) }
            out.enum("why", why)
            staMhz?.let { out.num("sta", it.toLong()) }
        }
    }

    /**
     * Wi-Fi Aware at a sample (`FieldLog.SAMPLE_EVERY_MS`), on a phone with ICM: [litMs] is our estimate of how long
     * Instant Communication Mode was lit by this phone's own configs since the plane started — the union of the 30 s
     * each ICM-enabled config opens — over [upMs], the time since the plane started, after [configs] such configs.
     * Cumulative, so two lines' difference is the duty between them; a plane restart starts both over.
     */
    data class NanIcmLit(
        val litMs: Long,
        val upMs: Long,
        val configs: Int,
    ) : FieldEvent {
        override val kind get() = "nan_icm_lit"

        override fun write(out: FieldWriter) {
            out.num("lit", litMs)
            out.num("up", upMs)
            out.num("configs", configs.toLong())
        }
    }

    /**
     * Wi-Fi Aware went dormant ([on]) or woke (`NanDormancyPolicy`, GitHub #44): with nothing to do it closes its
     * session, and NAN goes down with it, until a phone that can use it is near. [why] is `NanDormancyPolicy.Why`: IDLE
     * (went dormant), DUTY_EMPTY (dormant again after a duty attach found nobody), SIGHTING / BULK / VISIBLE / DUTY /
     * BLIND (woke), FORCED (the debug bridge). Written on each transition; a duty attach that wakes nothing writes one
     * DUTY_EMPTY line at its end.
     */
    data class NanDormant(
        val on: Boolean,
        val why: Enum<*>,
    ) : FieldEvent {
        override val kind get() = "nan_dormant"

        override fun write(out: FieldWriter) {
            out.flag("on", on)
            out.enum("why", why)
        }
    }

    /**
     * The person's Settings › Wi-Fi Aware switch (`AwareSwitch`, GitHub #44), at each session's start and on each change:
     * [on] false means Knit keeps the plane off and Bluetooth carries the mesh, so the Aware lines stop on purpose.
     */
    data class AwareSwitch(
        val on: Boolean,
    ) : FieldEvent {
        override val kind get() = "aware_switch"

        override fun write(out: FieldWriter) {
            out.flag("on", on)
        }
    }

    /**
     * The paired LoRa board at a sample, while its link is ready: the plane's role, the last packet's signal, the
     * queue, what it has heard, and the live share of airtime used against its budget. Beside them, what the role
     * and a quiet plane are explained by: the gateways heard, the pocket's live links and sightings, the peers a
     * spool covers; and what the board says of itself: its free queue slots, its battery, and its own duty-cycle
     * readings (channel use and its own transmit share, in tenths of a percent). Never the board's name, address
     * or node number.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class Lora(
        val role: Enum<*>,
        val snrTenths: Int?,
        val rssi: Int?,
        val queued: Int,
        val heard: Int,
        val boardsHeard: Int,
        val airUsedMs: Long?,
        val airBudgetMs: Long?,
        val gatewaysHeard: Int? = null,
        val pocketLinks: Int? = null,
        val pocketSightings: Int? = null,
        val internetCovered: Int? = null,
        val queueFree: Int? = null,
        val batteryPercent: Int? = null,
        val powered: Boolean? = null,
        val channelUtilTenths: Int? = null,
        val airUtilTxTenths: Int? = null,
    ) : FieldEvent {
        override val kind get() = "lora"

        @Suppress("CyclomaticComplexMethod") // one optional column per line, each its own null check
        override fun write(out: FieldWriter) {
            out.enum("role", role)
            snrTenths?.let { out.num("snr10", it.toLong()) }
            rssi?.let { out.num("rssi", it.toLong()) }
            out.num("queued", queued.toLong())
            out.num("heard", heard.toLong())
            out.num("boards", boardsHeard.toLong())
            airUsedMs?.let { out.num("airUsed", it) }
            airBudgetMs?.let { out.num("airBudget", it) }
            gatewaysHeard?.let { out.num("gateways", it.toLong()) }
            pocketLinks?.let { out.num("pocket", it.toLong()) }
            pocketSightings?.let { out.num("sighted", it.toLong()) }
            internetCovered?.let { out.num("covered", it.toLong()) }
            queueFree?.let { out.num("free", it.toLong()) }
            batteryPercent?.let { out.num("battery", it.toLong()) }
            powered?.let { out.flag("powered", it) }
            channelUtilTenths?.let { out.num("chUtil10", it.toLong()) }
            airUtilTxTenths?.let { out.num("airTx10", it.toLong()) }
        }
    }

    /**
     * The LoRa board's link became ready: [dialMs] from the last attempt's start (its direct dial, or its background
     * wait's arm) to ready — null when the log began with the link ready — after [tries] failed attempts since the last
     * up or down; the GATT [mtu]; and the board's radio as its config reported it — `LoraRegion`, `ModemPreset` (only
     * when it uses one), whether it is pinned to a slot of its own, its hop limit — and whether its firmware signs for
     * us (XEdDSA), each null where the board did not say. [relinkMs], filled by [FieldLog] like
     * [BtUp.relinkMs], is the time without a ready link since the last [LoraDown] in this log.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class LoraUp(
        val dialMs: Long?,
        val tries: Int,
        val mtu: Int,
        val region: Enum<*>? = null,
        val preset: Enum<*>? = null,
        val dedicatedSlot: Boolean? = null,
        val hopLimit: Int? = null,
        val xeddsa: Boolean? = null,
        val relinkMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "lora_up"

        override fun write(out: FieldWriter) {
            dialMs?.let { out.num("dial", it) }
            out.num("tries", tries.toLong())
            out.num("mtu", mtu.toLong())
            region?.let { out.enum("region", it) }
            preset?.let { out.enum("preset", it) }
            dedicatedSlot?.let { out.flag("slot", it) }
            hopLimit?.let { out.num("hops", it.toLong()) }
            xeddsa?.let { out.flag("xeddsa", it) }
            relinkMs?.let { out.num("relink", it) }
        }
    }

    /**
     * The LoRa board's ready link ended after [lifeMs]: [reason] is the plane's own classification of why, [status]
     * the GATT status it carried, where it carried one.
     */
    data class LoraDown(
        val reason: Enum<*>,
        val lifeMs: Long,
        val status: Int? = null,
    ) : FieldEvent {
        override val kind get() = "lora_down"

        override fun write(out: FieldWriter) {
            out.enum("reason", reason)
            status?.let { out.num("status", it.toLong()) }
            out.num("life", lifeMs)
        }
    }

    /**
     * An attempt at the LoRa board's link ended before it was ready: [reason] and [status] as in [LoraDown], [streak]
     * the attempts failed in a row, this one included, and [waitMs] the backoff before the next, where one was set.
     */
    data class LoraFail(
        val reason: Enum<*>,
        val streak: Int,
        val status: Int? = null,
        val waitMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "lora_fail"

        override fun write(out: FieldWriter) {
            out.enum("reason", reason)
            status?.let { out.num("status", it.toLong()) }
            out.num("streak", streak.toLong())
            waitMs?.let { out.num("wait", it) }
        }
    }

    /**
     * The LoRa plane's gateway role changed (ADR 044): [role] now, with the election's inputs as they stood — the
     * pocket's live short-range links and the gateways heard.
     */
    data class LoraRole(
        val role: Enum<*>,
        val pocketLinks: Int,
        val gatewaysHeard: Int,
    ) : FieldEvent {
        override val kind get() = "lora_role"

        override fun write(out: FieldWriter) {
            out.enum("role", role)
            out.num("pocket", pocketLinks.toLong())
            out.num("gateways", gatewaysHeard.toLong())
        }
    }

    /**
     * The phone's Internet route changed (`InternetGate`): its kind (`RouteKind`, NONE when there is no validated
     * route) and whether Data Saver restricts the app on it. Every spool line reads against it.
     */
    data class Net(
        val route: Enum<*>,
        val restricted: Boolean,
    ) : FieldEvent {
        override val kind get() = "net"

        override fun write(out: FieldWriter) {
            out.enum("route", route)
            out.flag("restricted", restricted)
        }
    }

    /**
     * A spool's hello completed (`SpoolStatus.connected`, ADR 2026-09.vej5): [helloMs] from the dial, after [fails]
     * dials in a row that never reached one, and what the hello offered — PoW [powBits], attachments, tickets (`ent`),
     * TURN, casts.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class SpoolUp(
        val relay: String,
        val helloMs: Long,
        val fails: Int,
        val powBits: Int,
        val attachments: Boolean,
        val ent: Boolean,
        val turn: Boolean,
        val cast: Boolean,
    ) : FieldEvent {
        override val kind get() = "spool_up"

        override fun write(out: FieldWriter) {
            out.relay("relay", relay)
            out.num("hello", helloMs)
            out.num("fails", fails.toLong())
            out.num("pow", powBits.toLong())
            out.flag("attach", attachments)
            out.flag("ent", ent)
            out.flag("turn", turn)
            out.flag("cast", cast)
        }
    }

    /**
     * A spool session that completed its hello ended after [lifeMs] (from the hello): [reason] the plane's verdict
     * (`SpoolVerdict`), [code] the close code or HTTP status behind it, [ours] when this phone ended it on a fault of
     * its own.
     */
    data class SpoolDown(
        val relay: String,
        val reason: Enum<*>,
        val lifeMs: Long,
        val ours: Boolean,
        val code: Int? = null,
    ) : FieldEvent {
        override val kind get() = "spool_down"

        override fun write(out: FieldWriter) {
            out.relay("relay", relay)
            out.enum("reason", reason)
            code?.let { out.num("code", it.toLong()) }
            out.num("life", lifeMs)
            out.flag("ours", ours)
        }
    }

    /**
     * A spool dial ended without a hello after [durationMs]: [reason] and [code] as in [SpoolDown], [streak] the dials
     * failed in a row, this one included, and [retryAfterMs] a `Retry-After` the spool sent with its refusal.
     */
    data class SpoolFail(
        val relay: String,
        val reason: Enum<*>,
        val durationMs: Long,
        val streak: Int,
        val code: Int? = null,
        val retryAfterMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "spool_fail"

        override fun write(out: FieldWriter) {
            out.relay("relay", relay)
            out.enum("reason", reason)
            code?.let { out.num("code", it.toLong()) }
            out.num("dur", durationMs)
            out.num("streak", streak.toLong())
            retryAfterMs?.let { out.num("retryAfter", it) }
        }
    }

    /**
     * A connected spool answered with an `err` code other than the last it answered on this connection (`SpoolVerdict`):
     * an edge, not every refusal, which the `spoolErrors` counter counts.
     */
    data class SpoolErr(
        val relay: String,
        val code: Enum<*>,
    ) : FieldEvent {
        override val kind get() = "spool_err"

        override fun write(out: FieldWriter) {
            out.relay("relay", relay)
            out.enum("code", code)
        }
    }

    /**
     * One configured spool at a sample: whether its hello is live, its scopes and how many agree with ours, the records
     * each side holds for them, the ones it holds that failed validation, the dials failed in a row, its PoW, and where
     * this connection stands with a ticket and with uploads (`SpoolTicketState`, `SpoolUploads`, as constants). Never a
     * scope's id or label.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class Spool(
        val relay: String,
        val connected: Boolean,
        val scopes: Int,
        val converged: Int,
        val local: Int,
        val remote: Int,
        val invalid: Int,
        val fails: Int,
        val powBits: Int,
        val ticket: Enum<*>? = null,
        val uploads: Enum<*>? = null,
        val reason: Enum<*>? = null,
    ) : FieldEvent {
        override val kind get() = "spool"

        override fun write(out: FieldWriter) {
            out.relay("relay", relay)
            out.flag("up", connected)
            out.num("scopes", scopes.toLong())
            out.num("converged", converged.toLong())
            out.num("local", local.toLong())
            out.num("remote", remote.toLong())
            out.num("invalid", invalid.toLong())
            out.num("fails", fails.toLong())
            out.num("pow", powBits.toLong())
            ticket?.let { out.enum("ticket", it) }
            uploads?.let { out.enum("uploads", it) }
            reason?.let { out.enum("reason", it) }
        }
    }

    /**
     * A voice call began on this phone (docs/VOICE_CALLS.md §3): dialled, or an offer that alerts (or a glare offer
     * answered at once). With no [CallEnd] after it, the process died in the call.
     */
    data class CallStart(
        val peer: String,
        val outgoing: Boolean,
        val walkie: Boolean,
    ) : FieldEvent {
        override val kind get() = "call_start"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("out", outgoing)
            out.flag("walkie", walkie)
        }
    }

    /**
     * A voice call ended on this phone, one line for every call it saw — an offer refused on arrival or one that never
     * rang included, with [lastPhase] null since it never had a phase here. [reason] is a `CallEndReason`, [lastPhase] the
     * `CallPhase` it ended from. The times run from the call's start on this phone: [ringMs] to the caller's first
     * proof the peer heard it (a RINGING, else the ANSWER), [answerMs] to the answer (heard by the caller, tapped by the
     * callee), [setupMs] to connected, and [talkMs] from connected to the end. [restarts] counts §3.8's ICE restarts,
     * [drops] the times a connected call lost its path. [turn] and [stun] are the TURN servers and STUN URLs its media
     * gathered from, null for a walkie.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class CallEnd(
        val peer: String,
        val outgoing: Boolean,
        val walkie: Boolean,
        val reason: Enum<*>,
        val lastPhase: Enum<*>?,
        val lifeMs: Long,
        val byPeer: Boolean = false,
        val missed: Boolean = false,
        val degraded: Boolean = false,
        val ringMs: Long? = null,
        val answerMs: Long? = null,
        val setupMs: Long? = null,
        val talkMs: Long? = null,
        val restarts: Int = 0,
        val drops: Int = 0,
        val relayOnly: Boolean = false,
        val directOffered: Boolean = false,
        val directTaken: Boolean = false,
        val turn: Int? = null,
        val stun: Int? = null,
        val unreached: Boolean = false,
        val unproven: Boolean = false,
    ) : FieldEvent {
        override val kind get() = "call_end"

        override fun write(out: FieldWriter) {
            out.peer("peer", peer)
            out.flag("out", outgoing)
            out.flag("walkie", walkie)
            out.enum("reason", reason)
            lastPhase?.let { out.enum("last", it) }
            out.num("life", lifeMs)
            out.flag("byPeer", byPeer)
            out.flag("missed", missed)
            out.flag("degraded", degraded)
            ringMs?.let { out.num("ring", it) }
            answerMs?.let { out.num("answer", it) }
            setupMs?.let { out.num("setup", it) }
            talkMs?.let { out.num("talk", it) }
            out.num("restarts", restarts.toLong())
            out.num("drops", drops.toLong())
            out.flag("relay", relayOnly)
            out.flag("direct", directOffered)
            out.flag("directUp", directTaken)
            turn?.let { out.num("turn", it.toLong()) }
            stun?.let { out.num("stun", it.toLong()) }
            out.flag("unreached", unreached)
            out.flag("unproven", unproven)
        }
    }

    /**
     * A live call's media chose a candidate pair (§3.7), the [seq]th of its session: each end's `IceType`, the local
     * candidate's transport and network (`IceProtocol`, `IceNetwork`, null where the candidate does not say), and
     * whether the pair rides the call's Wi-Fi Direct group (§3.10). Types only, never an address.
     */
    data class CallRoute(
        val local: Enum<*>,
        val remote: Enum<*>,
        val protocol: Enum<*>?,
        val network: Enum<*>?,
        val direct: Boolean,
        val seq: Int,
    ) : FieldEvent {
        override val kind get() = "call_route"

        override fun write(out: FieldWriter) {
            out.enum("local", local)
            out.enum("remote", remote)
            protocol?.let { out.enum("proto", it) }
            network?.let { out.enum("net", it) }
            out.flag("direct", direct)
            out.num("seq", seq.toLong())
        }
    }

    /**
     * One reading of a live call's media, from libwebrtc's stats, while it is up (`CallMediaSampler`): [reconnecting]
     * when §3.8's window is open. Times in ms; loss in tenths of a percent — [lossIn10] what this phone failed to hear
     * since the last reading, [lossOut10] what the peer last reported failing to hear from it, [lossCall10] the whole
     * call's receive loss; [packets] heard since the last reading (zero with the media up is a peer no longer heard);
     * the two audio levels in ‰ of full scale. The selected pair as in [CallRoute], plus the relay's leg to its TURN
     * server, and the answered and unanswered pairs. [micPath] is the `CaptureProfile` the call records on and
     * [micHealth] the watch's `CaptureWatch.Health` (C-3.13-13). Null where the report does not carry it yet.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class CallSample(
        val reconnecting: Boolean,
        val rttMs: Long? = null,
        val jitterMs: Long? = null,
        val bufferMs: Long? = null,
        val lossIn10: Long? = null,
        val lossOut10: Long? = null,
        val lossCall10: Long? = null,
        val sendKbps: Long? = null,
        val receiveKbps: Long? = null,
        val packets: Long? = null,
        val micPermille: Long? = null,
        val heardPermille: Long? = null,
        val local: Enum<*>? = null,
        val remote: Enum<*>? = null,
        val protocol: Enum<*>? = null,
        val relayProtocol: Enum<*>? = null,
        val network: Enum<*>? = null,
        val direct: Boolean = false,
        val pairs: Int = 0,
        val pending: Int = 0,
        val micPath: Enum<*>? = null,
        val micHealth: Enum<*>? = null,
    ) : FieldEvent {
        override val kind get() = "call_media"

        @Suppress("CyclomaticComplexMethod") // one optional column per line, each its own null check
        override fun write(out: FieldWriter) {
            out.flag("lost", reconnecting)
            rttMs?.let { out.num("rtt", it) }
            jitterMs?.let { out.num("jitter", it) }
            bufferMs?.let { out.num("buffer", it) }
            lossIn10?.let { out.num("lossIn10", it) }
            lossOut10?.let { out.num("lossOut10", it) }
            lossCall10?.let { out.num("lossCall10", it) }
            sendKbps?.let { out.num("send", it) }
            receiveKbps?.let { out.num("recv", it) }
            packets?.let { out.num("pkts", it) }
            micPermille?.let { out.num("mic", it) }
            heardPermille?.let { out.num("heard", it) }
            local?.let { out.enum("local", it) }
            remote?.let { out.enum("remote", it) }
            protocol?.let { out.enum("proto", it) }
            relayProtocol?.let { out.enum("relayProto", it) }
            network?.let { out.enum("net", it) }
            out.flag("direct", direct)
            out.num("pairs", pairs.toLong())
            out.num("pending", pending.toLong())
            micPath?.let { out.enum("micPath", it) }
            micHealth?.let { out.enum("micHealth", it) }
        }
    }

    /**
     * A call's Wi-Fi Direct leg ended (§3.10): this phone [hosting] or joining, how it ended (`DirectLegEnd`), and from
     * the leg's start the group up ([groupMs]) and the lane up ([laneMs]) — null for a step it never reached — and the
     * whole leg ([lifeMs]). [joinTries] are the joiner's attempts (0 on the host).
     */
    data class CallDirectLeg(
        val hosting: Boolean,
        val end: Enum<*>,
        val groupMs: Long?,
        val laneMs: Long?,
        val lifeMs: Long,
        val joinTries: Int,
    ) : FieldEvent {
        override val kind get() = "call_direct"

        override fun write(out: FieldWriter) {
            out.flag("host", hosting)
            out.enum("end", end)
            groupMs?.let { out.num("group", it) }
            laneMs?.let { out.num("lane", it) }
            out.num("life", lifeMs)
            out.num("tries", joinTries.toLong())
        }
    }

    /**
     * One walkie burst (§3.11). [TalkDirection.OUT]: [audioMs] of speech in [chunks], [sent] of them sealed onto a link,
     * the way to the peer at its end (`TalkPath`) and whether the call held its Wi-Fi Aware link. [TalkDirection.IN],
     * played to its end: [chunks] heard, [lost] skipped as never come, [waitMs] from the first chunk's arrival to its
     * playing, and [spreadMs] from the first chunk's arrival to the last's.
     */
    data class Talk(
        val direction: TalkDirection,
        val chunks: Int,
        val audioMs: Long? = null,
        val sent: Int? = null,
        val path: Enum<*>? = null,
        val held: Boolean? = null,
        val lost: Int? = null,
        val waitMs: Long? = null,
        val spreadMs: Long? = null,
    ) : FieldEvent {
        override val kind get() = "talk"

        override fun write(out: FieldWriter) {
            out.enum("dir", direction)
            out.num("chunks", chunks.toLong())
            audioMs?.let { out.num("ms", it) }
            sent?.let { out.num("sent", it.toLong()) }
            path?.let { out.enum("path", it) }
            held?.let { out.flag("held", it) }
            lost?.let { out.num("lost", it.toLong()) }
            waitMs?.let { out.num("wait", it) }
            spreadMs?.let { out.num("spread", it) }
        }
    }

    /**
     * A walkie session's totals as it ends (§3.11, `WalkieMeter`): bursts and chunks each way, the chunks no link took
     * and the ones skipped as never come, and the medians of the latest chunks — [transitMs] by the two phones' clocks
     * (their skew is in it), [jitterMs] from the sender's stamps (no skew), [handMs] from a chunk's cut to the radio
     * taking it — and the average `talk` frame each way.
     */
    @Suppress("LongParameterList") // one field per column of the line; a holder would only rename the list
    data class WalkieEnd(
        val burstsOut: Int,
        val burstsIn: Int,
        val chunksOut: Int,
        val chunksFailed: Int,
        val chunksIn: Int,
        val chunksLost: Int,
        val transitMs: Long? = null,
        val jitterMs: Long? = null,
        val handMs: Long? = null,
        val frameOut: Int? = null,
        val frameIn: Int? = null,
    ) : FieldEvent {
        override val kind get() = "walkie_end"

        override fun write(out: FieldWriter) {
            out.num("burstsOut", burstsOut.toLong())
            out.num("burstsIn", burstsIn.toLong())
            out.num("chunksOut", chunksOut.toLong())
            out.num("failed", chunksFailed.toLong())
            out.num("chunksIn", chunksIn.toLong())
            out.num("lost", chunksLost.toLong())
            transitMs?.let { out.num("transit", it) }
            jitterMs?.let { out.num("jitter", it) }
            handMs?.let { out.num("hand", it) }
            frameOut?.let { out.num("frameOut", it.toLong()) }
            frameIn?.let { out.num("frameIn", it.toLong()) }
        }
    }

    /**
     * How long messages took, worked out at export from the database (never journaled). [direction] is
     * `IN` (a message reaching this phone: our arrival clock minus the author's `sentAt`, so the two
     * clocks' skew is in it) or `ACK` (a receipt for one of ours reaching us: both ends on our clock).
     */
    data class Latency(
        val direction: LatencyDirection,
        val plane: Enum<*>,
        val count: Int,
        val p50Ms: Long,
        val p90Ms: Long,
        val maxMs: Long,
    ) : FieldEvent {
        override val kind get() = "latency"

        override fun write(out: FieldWriter) {
            out.enum("dir", direction)
            out.enum("plane", plane)
            out.num("n", count.toLong())
            out.num("p50", p50Ms)
            out.num("p90", p90Ms)
            out.num("max", maxMs)
        }
    }
}

/** Why a Bluetooth link ended, from the transport's teardown reasons. */
enum class BtDownReason {
    /** The peer's end closed, or the link broke under us. */
    EOF,

    /** Our mesh stopped. */
    STOP,

    /** We dropped it to make room for a better link. */
    EVICTED,

    /** Bluetooth was switched off. */
    ADAPTER_OFF,

    /** A new link to the same peer took this one's place. */
    REPLACED,

    /** We closed it: it was shown dead by its silence, its own controller or a write that never returned (#203). */
    SILENT,
    OTHER,
    ;

    companion object {
        fun of(reason: String): BtDownReason =
            when (reason) {
                "eof" -> EOF
                "stop" -> STOP
                "evicted" -> EVICTED
                "adapter off" -> ADAPTER_OFF
                "silent" -> SILENT
                else -> OTHER
            }
    }
}

/** Which way a [FieldEvent.Latency] summary measures. */
enum class LatencyDirection { IN, ACK }

/** Which way a [FieldEvent.Talk] burst went: spoken here, or heard from the peer. */
enum class TalkDirection { OUT, IN }
