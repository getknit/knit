package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode

/**
 * A test's hand on the ring gather ([GatherBy.RING]). [linked] is given the links the gather raises once they are
 * connected and before any node is told of them, so nothing has crossed them yet: a test can hold their pipes there.
 * [read] runs once the gather's report has read what the gather left missing, before the settle: where a test lets
 * them go. The walking gather raises its links through the radio and calls neither.
 */
interface GatherSeam {
    fun linked(links: List<Pair<LabNode, LabNode>>) {}

    suspend fun read() {}

    companion object {
        /** The outing's own gather, untouched. */
        val NONE = object : GatherSeam {}
    }
}
