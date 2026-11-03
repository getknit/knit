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

    fun connectOk(
        provenId: String,
        durMs: Long,
    ): String = "bt connect ok $provenId durMs=$durMs"

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

    private const val FIELD_FORM = "~tag"
}
