package app.getknit.knit.mesh.sim

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

    /** Moves the radio to [atMs] with every walker at its place in [positions]. */
    fun step(
        atMs: Long,
        positions: List<Point>,
    ): LinkChanges

    /** The numbers it was built with, as [SimSummary] keys: two runs line up by them. */
    fun summaryValues(): List<Pair<String, Any?>>

    companion object {
        /** Every radio [of] builds, by name. */
        val NAMES = listOf(DiscRadio.NAME)

        /** The radio [scenario] names, with [initial] links up before its first step (the groups that arrived together). */
        fun of(
            scenario: SimScenario,
            initial: Set<Link>,
        ): Radio =
            when (scenario.radio) {
                DiscRadio.NAME -> DiscRadio(scenario.connectM, scenario.dropM, scenario.dialMs, initial)
                else -> throw IllegalArgumentException("no radio named ${scenario.radio}; there are $NAMES")
            }
    }
}
