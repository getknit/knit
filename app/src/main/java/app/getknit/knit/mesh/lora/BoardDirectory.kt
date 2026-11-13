package app.getknit.knit.mesh.lora

/**
 * A bonded Bluetooth device the LoRa settings picker may offer as a board. [kind] is the directory's verdict on which
 * kind of board it looks like ([BoardFilter.kindOf]: a MeshCore name first, then a Meshtastic name or the Meshtastic
 * service UUID in the stack's cache), or null when it looks like neither; the picker hides those behind a "show all"
 * toggle rather than dropping them, because the verdict is a heuristic.
 */
internal data class BoardRef(
    val address: String,
    val name: String,
    val kind: BoardKind?,
) {
    /** A device judged only as Meshtastic-like or not, as every directory did before there was a second kind. */
    constructor(address: String, name: String, meshtastic: Boolean = true) :
        this(address, name, if (meshtastic) BoardKind.MESHTASTIC else null)

    /** Whether it looks like a Meshtastic board. A MeshCore one never does (ADR 2026-11.gsqu, Stage 3d). */
    val meshtastic: Boolean get() = kind == BoardKind.MESHTASTIC
}

/** Lists every bonded device for the picker; implemented in `mesh/bluetooth/meshtastic/`. */
internal interface BoardDirectory {
    fun bonded(): List<BoardRef>
}
