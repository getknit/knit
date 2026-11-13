package app.getknit.knit.mesh.lora

/**
 * Which bonded devices the LoRa picker offers. The phone's bonded list is mostly headsets and watches; the
 * picker shows the ones that look like a board of either kind and counts the rest behind a "show all paired
 * devices" toggle — hidden, never dropped, because [looksLikeBoard] is a heuristic. Pure, so the rules are
 * JVM-tested; the Android side ([BoardRef.kind]) only supplies the per-device verdict, through [kindOf].
 *
 * The MeshCore rules are tested first (ADR 2026-11.gsqu, Stage 3d): a companion advertises `MeshCore-<name>`
 * (`BLE_NAME_PREFIX`, `companion_radio/MyMesh.h`), which [looksLikeBoard]'s "mesh" prefix used to list as a Meshtastic
 * board, and binding it redialled it forever. A board is MeshCore on that prefix alone, never on the Nordic UART service
 * UUID, which is the generic NUS that many other peripherals serve.
 */
internal object BoardFilter {
    /**
     * The boards to list: every Meshtastic-looking device plus the board bound at [boundAddress] (a board the
     * user renamed must not vanish from the list while it is the selected one), or everything when [showAll].
     */
    fun visible(
        boards: List<BoardRef>,
        boundAddress: String?,
        showAll: Boolean,
    ): List<BoardRef> = if (showAll) boards else boards.filter { it.kind != null || it.address == boundAddress }

    /** How many bonded devices [visible] hides without [showAll] — what the toggle would reveal. */
    fun hidden(
        boards: List<BoardRef>,
        boundAddress: String?,
    ): Int = boards.size - visible(boards, boundAddress, showAll = false).size

    /**
     * Whether an advertised [name] looks like a Meshtastic board: the stock `Meshtastic_a1b2`, or a custom short
     * name the firmware suffixes with the same four MAC hex digits (`WALT_a1b2` — `getDeviceName()` in the
     * firmware, so a renamed board is not lost on the name alone). Never a MeshCore name ([looksLikeMeshcore]).
     */
    fun looksLikeBoard(name: String): Boolean =
        !looksLikeMeshcore(name) && (name.startsWith("mesh", ignoreCase = true) || RENAMED_SUFFIX.containsMatchIn(name))

    /**
     * Whether an advertised [name] is a MeshCore companion's: the firmware names itself `MeshCore-` and its node name
     * (`SerialBLEInterface::begin`), so a renamed board keeps the prefix. The prefix is the whole test.
     */
    fun looksLikeMeshcore(name: String): Boolean = name.startsWith(MESHCORE_PREFIX, ignoreCase = true)

    /**
     * Which kind a bonded device looks like, or null for neither. Never a [classicOnly] device. The MeshCore rule comes
     * first and settles it on the name; otherwise [isMeshtastic] decides, reading [listsMeshtasticService] only when the
     * name does not settle it.
     */
    fun kindOf(
        name: String,
        classicOnly: Boolean,
        listsMeshtasticService: () -> Boolean,
    ): BoardKind? =
        when {
            classicOnly -> null
            looksLikeMeshcore(name) -> BoardKind.MESHCORE
            isMeshtastic(name, classicOnly = false, listsMeshtasticService) -> BoardKind.MESHTASTIC
            else -> null
        }

    /**
     * Whether a bonded device looks like a Meshtastic board: LE-capable (a board is never [classicOnly], which rules out
     * most headsets outright), not named like a MeshCore board, and either a board-like [name] ([looksLikeBoard]) or the
     * Meshtastic service among the stack's cached services ([listsService], read only then: a positive-only signal, since
     * the cache is empty for most LE bonds until a first connection).
     */
    fun isMeshtastic(
        name: String,
        classicOnly: Boolean,
        listsService: () -> Boolean,
    ): Boolean = !classicOnly && !looksLikeMeshcore(name) && (looksLikeBoard(name) || listsService())

    private const val MESHCORE_PREFIX = "MeshCore-"
    private val RENAMED_SUFFIX = Regex("_[0-9a-f]{4}$", RegexOption.IGNORE_CASE)
}
