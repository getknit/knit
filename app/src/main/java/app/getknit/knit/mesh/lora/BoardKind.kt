package app.getknit.knit.mesh.lora

/**
 * Which kind of board a LoRa plane instance drives (ADR 2026-11.gsqu §1, Stage 2): one plane instance per kind over the
 * one neutral core, each its own `CompositeMeshTransport` child with its own ledger, its own Koin qualifier, its own
 * settings keys, its own persisted limiters and its own field-log stream. v1 binds one board at a time, so "the bound
 * kind" ([boundOf]) is the one every per-kind surface reads; with none bound it is [MESHTASTIC], today's answer.
 *
 * Only [MESHTASTIC] has a plane in Stage 2. [MESHCORE] is declared so the per-kind surfaces exist before its plane does
 * (Stage 5; built in debug builds since 5a-2). An enum, never an identity: it names a firmware family, never a board, so it may
 * ride a field-log line ([fieldTag]) and a Koin qualifier.
 *
 * Read as `boardKind` on a transport or a status, never `kind`: that name is `MeshTransport.kind`, the radio family
 * (`TransportKind.LoRa`), which both kinds share.
 */
enum class BoardKind {
    /** A Meshtastic board over BLE: the plane since ADR 038. */
    MESHTASTIC,

    /** A MeshCore companion over BLE (ADR 2026-11.gsqu). Its plane is built in debug builds since Stage 5a-2 (`MESHCORE_PLANE`). */
    MESHCORE,
    ;

    /**
     * What a field-log line writes for this kind: nothing for [MESHTASTIC], whose `lora*` and `radio` lines predate the
     * kind and stay byte-identical (`FieldEventSchemaTest`'s golden lines), and the kind itself for any other.
     */
    val fieldTag: BoardKind? get() = takeIf { it != MESHTASTIC }

    /**
     * This kind's field-log sampler and follower name: `lora` stays Meshtastic's, as it was before there was a second
     * kind. A constant per kind, never derived from a board.
     */
    val fieldStream: String
        get() =
            when (this) {
                MESHTASTIC -> "lora"
                MESHCORE -> "meshcore"
            }

    companion object {
        /**
         * The bound kind, v1's one-board rule: the kind whose device address is set, or null with none. A Meshtastic
         * address wins a tie, since it is today's only binding and a cross-kind bind (`BoardBindPolicy`) clears the other
         * kind's address in the same edit, so a tie means an older build's leftovers, never a choice.
         */
        fun boundOf(
            meshtasticBound: Boolean,
            meshcoreBound: Boolean,
        ): BoardKind? =
            when {
                meshtasticBound -> MESHTASTIC
                meshcoreBound -> MESHCORE
                else -> null
            }
    }
}

/**
 * A plane that knows which [BoardKind] it is: the LoRa transport and its UI face ([LoraPlaneStatus]). The composite reads
 * it to key a LoRa child's `TransportStatus.boardKind`, since `MeshTransport.kind` is the same `TransportKind.LoRa` for
 * every kind.
 */
interface BoardKindSource {
    val boardKind: BoardKind
}
