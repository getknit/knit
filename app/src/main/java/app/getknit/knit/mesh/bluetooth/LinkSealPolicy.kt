package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.link.LinkHandshake

/**
 * The Bluetooth plane's decisions about sealed links (#286, ADR 2026-11.8mhs), pure so the transport's wiring stays
 * thin: which form a dial opens with, which seal counter a handshake's end moves, and when a plain dial's silence
 * forgets a cached GATT payload. The handshake itself is [LinkHandshake]'s; this only reads its outcomes.
 *
 * **The form is the dialed address's.** A peer heard on 1M and on Coded keeps one cached device per PHY, each from its
 * own advert (`deviceFor` / `codedDeviceFor`), and presence keeps only the latest sighting of either. The flag a dial
 * reads is the one heard at the address it dials (contract §2: "the flags byte of the advert the dial is made from"),
 * last heard, never sticky or OR'd across PHYs, so [dialsSealed] reads it off the target [DialPlan.target] picked.
 *
 * **Counting once.** A handshake's end counts at most one seal counter here, in the transport; a live link's fault is
 * counted by the [app.getknit.knit.mesh.link.SealedLinkSocket] armed at registration. An EOF or a timeout is never a
 * failed seal, at any stage (contract revision 1): only bytes that arrived and were wrong are.
 */
internal object LinkSealPolicy {
    /** Which seal counter a handshake's end moves; each at most once per attempt. */
    enum class Count {
        NONE,

        /** `linkSealFailed`: bytes arrived and were wrong (contract §4). */
        FAILED,

        /** `linkSealRefused`: a plain-form link between two sealing builds (contract §7). */
        REFUSED,
    }

    /**
     * Whether a dial to [target] opens sealed: this build seals ([seals]) and the advert heard at the target's own
     * address carries [BleAdvertPayload.FLAG_LINK_SEAL] ([advertSeals]). The other PHY's advert, the stored profile and
     * any memory of earlier links never decide it (contract §2).
     */
    fun <T : Any> dialsSealed(
        target: Pair<T, Boolean>,
        advertSeals: (T) -> Boolean,
        seals: Boolean = BleAdvertPayload.SEALS_LINKS,
    ): Boolean = seals && advertSeals(target.first)

    /**
     * A sealed dial's first stage folded into the dial's outcome, or null once both key shares crossed: nothing where the
     * responder's `KEY_SHARE` belongs is today's no reply (never a failed seal, revision 1), and another record or a
     * refused key share is a failed seal. [inTime] false (the stage's watchdog closed the socket) is no reply, whatever
     * the read returned: the watchdog's close is what ended it.
     */
    fun unopened(
        opened: LinkHandshake.SealOpened?,
        inTime: Boolean,
    ): LinkHandshake.DialOutcome? =
        when {
            !inTime || opened == null || opened == LinkHandshake.SealOpened.NoReply -> LinkHandshake.DialOutcome.NoReply
            opened is LinkHandshake.SealOpened.Failed -> LinkHandshake.DialOutcome.SealFailed(opened.why)
            else -> null
        }

    /**
     * The seal counter a dial's [outcome] moves. Every outcome but a link takes the transport's one refusal branch (the
     * `HANDSHAKE` backoff, keyed on the dial key, never on a reply's unproven id); this says only what it counts.
     */
    fun dialCount(outcome: LinkHandshake.DialOutcome): Count =
        when (outcome) {
            is LinkHandshake.DialOutcome.SealFailed -> Count.FAILED
            is LinkHandshake.DialOutcome.SealRefused -> Count.REFUSED
            else -> Count.NONE
        }

    /** The seal counter a responder's first record moves: a failed seal only for a bad key share. */
    fun openingCount(opening: LinkHandshake.Opening): Count = if (opening is LinkHandshake.Opening.SealFailed) Count.FAILED else Count.NONE

    /** The seal counter the dialer's sealed HELLO moves: a failed seal for bytes that were wrong, nothing for silence. */
    fun sealedHelloCount(hello: LinkHandshake.SealedHello): Count =
        if (hello is LinkHandshake.SealedHello.SealFailed) Count.FAILED else Count.NONE

    /**
     * The seal counter a responder's answer moves. [LinkHandshake.AnswerOutcome.SealRefused] comes back only to a caller
     * that skipped [LinkHandshake.refusesPlain]; it counts as the refusal it is.
     */
    fun answerCount(outcome: LinkHandshake.AnswerOutcome): Count =
        when (outcome) {
            is LinkHandshake.AnswerOutcome.SealFailed -> Count.FAILED
            LinkHandshake.AnswerOutcome.SealRefused -> Count.REFUSED
            else -> Count.NONE
        }

    /**
     * Whether a dial's end forgets the GATT payload it was made from, so the next dial reads the peer's flag afresh
     * (contract §7, revision 1): a **plain** dial ([sealedDial] false) that drew no reply ([outcome] is
     * [LinkHandshake.DialOutcome.NoReply], the shape a §7 refusal takes on this side), whose HELLO carried
     * `CAP_LINK_SEAL` ([helloSeals]), to a peer whose latest sighting came through a GATT payload ([gattSighted]), which
     * lacks the flag ([payload] non-null without [BleAdvertPayload.Parsed.linkSeal]). That is a peer that may have
     * upgraded since its payload was read. A sealed dial with no reply forgets nothing (revision 2): busy, suspended and
     * already-linked iPhones give no reply all the time. [GattPayloads.forgetUnsealed] makes it once per payload; every
     * other forget rule is [DialPlan.forgetsGattPayload]'s and the contract's three, unchanged.
     */
    fun forgetsUnsealedPayload(
        sealedDial: Boolean,
        outcome: LinkHandshake.DialOutcome,
        gattSighted: Boolean,
        payload: BleAdvertPayload.Parsed?,
        helloSeals: Boolean = BleAdvertPayload.SEALS_LINKS,
    ): Boolean =
        !sealedDial &&
            outcome == LinkHandshake.DialOutcome.NoReply &&
            helloSeals &&
            gattSighted &&
            payload != null &&
            !payload.linkSeal

    /**
     * Drops [key]'s entry in [cache] while it is still the one heard at [address], once [forgetsUnsealedPayload]'s payload
     * went: the device it was heard from carries that payload's stale flag, and a dial made from it would open plain again
     * and be refused under §7 with no payload left to forget. Until the re-read lands, the peer has no address to dial.
     * An entry a newer sighting put at another address stays. Answers whether one went.
     */
    fun <H : Any> forgetHeardAt(
        cache: MutableMap<String, H>,
        key: String,
        address: String,
        addressOf: (H) -> String,
    ): Boolean {
        var dropped = false
        cache.computeIfPresent(key) { _, held ->
            if (addressOf(held) == address) {
                dropped = true
                null
            } else {
                held
            }
        }
        return dropped
    }
}
