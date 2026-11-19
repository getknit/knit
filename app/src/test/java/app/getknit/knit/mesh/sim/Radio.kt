package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LinkAir

/**
 * What turns where the walkers stand into links: [MeshSim] steps it every tick and applies what it changed to the lab's
 * transports. [DiscRadio] is distance alone; a radio that models a plane's own range, dial and link budget is another
 * implementation, chosen by name ([SimScenario.radio], `-Pknit.meshSimRadio`).
 */
interface Radio {
    /** The word that names it in a report's summary and in `-Pknit.meshSimRadio`. */
    val name: String

    /** The links up after the last [step]: an immutable copy, safe to read from another thread. */
    val links: Set<Link>

    /** The airtime of every link this radio raises ([LinkAir]), or null for links that deliver as they send. */
    val air: LinkAir? get() = null

    /** [link]'s airtime now: [air], unless the radio carries some links on a plane of their own ([NanRadio]). */
    fun airOf(link: Link): LinkAir? = air

    /** Each node's radio, shared by its air links both ways ([app.getknit.knit.mesh.lab.NodeAir]), or null for none. */
    val nodeAirBytesPerSecond: Long? get() = null

    /** The most links one walker may hold, or null for a radio with no budget. */
    val maxLinks: Int? get() = null

    /**
     * Moves the radio to [atMs] with every walker at its place in [positions]. A walker [present] marks false is out of
     * the field ([Mobility.present]): its links drop and nobody dials it. Null means everyone is there.
     */
    fun step(
        atMs: Long,
        positions: List<Point>,
        present: BooleanArray? = null,
    ): LinkChanges

    /** The numbers it was built with, as [SimSummary] keys: two runs line up by them. */
    fun summaryValues(): List<Pair<String, Any?>>

    companion object {
        /** Every radio [of] builds, by name. */
        val NAMES = listOf(DiscRadio.NAME, BleRadio.NAME, BleRadio.SLOW_NAME, NanRadio.NAME)

        /** The radio [scenario] names, with [initial] links up before its first step (the groups that arrived together). */
        fun of(
            scenario: SimScenario,
            initial: Set<Link>,
        ): Radio =
            when (scenario.radio) {
                DiscRadio.NAME -> {
                    DiscRadio(scenario.connectM, scenario.dropM, scenario.dialMs, initial)
                }

                BleRadio.NAME -> {
                    BleRadio(scenario.seed, scenario.connectM, scenario.dropM, initial)
                }

                BleRadio.SLOW_NAME -> {
                    BleRadio(
                        scenario.seed,
                        scenario.connectM,
                        scenario.dropM,
                        initial,
                        linkBytesPerSecond = BleRadio.SLOW_LINK_BYTES_PER_S,
                        nodeBytesPerSecond = BleRadio.SLOW_NODE_BYTES_PER_S,
                        name = BleRadio.SLOW_NAME,
                    )
                }

                NanRadio.NAME -> {
                    NanRadio(
                        seed = scenario.seed,
                        connectM = scenario.connectM,
                        dropM = scenario.dropM,
                        initial = initial,
                        nanRangeM = scenario.nanRangeM ?: scenario.connectM,
                        failShare = scenario.nanFailShare ?: NanRadio.FAIL_SHARE,
                        discoverMedianMs = scenario.nanDiscoverMs ?: NanRadio.DISCOVER_MEDIAN_MS,
                    )
                }

                else -> {
                    throw IllegalArgumentException("no radio named ${scenario.radio}; there are $NAMES")
                }
            }
    }
}
