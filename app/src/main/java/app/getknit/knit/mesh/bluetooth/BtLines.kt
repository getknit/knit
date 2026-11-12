package app.getknit.knit.mesh.bluetooth

/**
 * The `bt …` lines that name a dial's peer (ADR 2026-11.xp6d's reader, obligation 22), built here so a unit test formats
 * each with a tag's key and finds no tag in it. A per-peer key is a resolved id or an unresolved form's handle
 * ([FormKeys]), which shows no tag; a dial key ([DialKeys.dialKey]) is one of those, or a handle and an address. So
 * these lines carry the proven or resolved id where there is one, a handle (`~zzzz…<n>`) otherwise, and never pair a
 * tag with an id. Clear ids stay whole: `interop.py` reads `bt initiating to <id>`, `bt connect ok <id>` and
 * `promote=[…]`, and `ble-link-trial.py` keys its pairs on them. Harness oracles: reword none, add after the fields.
 */
internal object BtLines {
    /** The field log's peer for [key] (a user shares it): an unresolved form is one fixed token, so no pseudonym per tag. */
    fun fieldPeer(key: String): String = if (FormKeys.isForm(key)) FIELD_FORM else key

    @Suppress("LongParameterList") // one line's fields
    fun initiating(
        key: String,
        psm: Int,
        rssi: Int,
        a2dp: Any,
        links: Int,
        via: String,
    ): String = "bt initiating to $key (psm $psm rssi=$rssi a2dp=$a2dp links=$links$via)"

    fun lonelyDial(
        key: String,
        aloneMs: Long,
        rssi: Int,
        dwellMs: Long,
    ): String = "bt lonely dial $key (alone=${aloneMs}ms rssi=$rssi dwell=${dwellMs}ms)"

    /** `sealed=` (#286) comes after `durMs=`, which `scripts/ble-link-trial.py`'s `connect_ok` reads up to. */
    fun connectOk(
        provenId: String,
        durMs: Long,
        sealed: Boolean = false,
    ): String = "bt connect ok $provenId durMs=$durMs sealed=$sealed"

    @Suppress("LongParameterList") // one line's fields
    fun connectFailed(
        key: String,
        reason: Any,
        durMs: Long,
        a2dp: Any,
        links: Int,
        streak: Int,
        retryMs: Long,
    ): String = "bt connect $key failed reason=$reason durMs=$durMs a2dp=$a2dp links=$links streak=$streak retryMs=$retryMs"

    /** A timed-out dial to [key] forgot [address] as its dial target (#309), straight after its `bt connect … failed` line. */
    fun targetForgotten(
        key: String,
        address: String,
    ): String = "bt dial target forgotten $key $address (timeout)"

    fun promote(
        promote: Collection<String>,
        evict: Collection<String>,
        backoff: Collection<String>,
        a2dp: Any,
    ): String = "promote=$promote evict=$evict backoff=$backoff a2dp=$a2dp"

    fun coldHuntOver(
        key: String,
        why: Any,
    ): String = "bt hunt $key: cold hunt over ($why)"

    fun coldHunt(
        key: String,
        grantMs: Long,
        rssi: Int,
    ): String = "bt hunt $key: cold sighting on Coded, for ${grantMs}ms (rssi=$rssi)"

    fun coldFast(
        key: String,
        grantMs: Long,
        rssi: Int,
    ): String = "bt coded advert fast (cold $key, for ${grantMs}ms rssi=$rssi)"

    // --- A blinded GATT payload (ADR 2026-11.89gg). None of these prints a tag, nor pairs one with an id; each avoids
    // `interop.py`'s clear-read (`GATT_READ_END`, `bt gatt read (\S+) → <id>`) and refund (`GATT_CONFIRMED`) shapes.

    /** `bt gatt forget`'s reason for rule 4: a blinded payload past its slot's edge. */
    const val GATT_FORGET_EDGE = "edge"

    /** A GATT payload forgotten at [address] for [reason] (`dial`, `hello <id>`, `quiet`, `seal`, [GATT_FORGET_EDGE]). */
    fun gattForget(
        address: String,
        reason: String,
    ): String = "bt gatt forget $address ($reason)"

    /** A read at [address] ended with a blinded payload: the address and PSM only, the tag never. */
    fun gattReadBlinded(
        address: String,
        psm: Int,
    ): String = "bt gatt read $address blinded (psm $psm)"

    /**
     * How a read's blinded sighting at [address] resolved, on the worker, once per read: [key] is the resolved id or the
     * form's handle, [how] one of [gattResolved]'s words.
     */
    fun gattBlinded(
        address: String,
        key: String,
        how: String,
    ): String = "bt gatt blinded $address → $key ($how)"

    /** [gattBlinded]'s word for [resolution]: `key`, `claim`, `hint`, `handle`, `own`, `replayed` (a key's, voided) or `malformed`. */
    fun gattResolved(
        resolution: AdvertAliases.Resolution,
        replayed: Boolean,
    ): String =
        when (resolution) {
            is AdvertAliases.Resolution.Resolved -> {
                when (resolution.source) {
                    AdvertAliases.Source.Key -> if (replayed) "replayed" else "key"
                    AdvertAliases.Source.Claim -> "claim"
                    AdvertAliases.Source.Hint -> "hint"
                    AdvertAliases.Source.Clear -> "clear"
                }
            }

            AdvertAliases.Resolution.Unresolved -> {
                "handle"
            }

            AdvertAliases.Resolution.Own -> {
                "own"
            }

            AdvertAliases.Resolution.Malformed -> {
                "malformed"
            }
        }

    /** A blinded read's charge at [address] refunded by a proven link to [provenId] that showed its tag. */
    fun gattConfirmedTag(
        address: String,
        provenId: String,
    ): String = "bt gatt confirmed $address (tag) → $provenId"

    private const val FIELD_FORM = "~tag"
}
