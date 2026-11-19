package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.MeshLab

/**
 * One radio step's [changes] applied to the lab as a radio applies them: a dropped link is cut at once
 * (`transport.disconnect`, what is on its air lost with it), a raised one comes up at its rate ([Radio.airOf]), one that
 * moved plane changes rate where it stands, and each end learns how a file to the other would now go, with nobody
 * waiting for the mesh. [nodeOf] maps a radio's walker to its node.
 */
internal fun MeshLab.applyLinks(
    changes: LinkChanges,
    nodeOf: (Int) -> LabNode,
    radio: Radio,
) {
    changes.dropped.forEach { nodeOf(it.a).transport.disconnect(nodeOf(it.b).transport) }
    changes.raised.forEach { link(nodeOf(it.a), nodeOf(it.b), air = radio.airOf(it)) }
    changes.rerated.forEach { link -> radio.airOf(link)?.let { nodeOf(link.a).transport.setAir(nodeOf(link.b).transport, it) } }
    for ((link, route) in changes.routes) {
        val (a, b) = nodeOf(link.a) to nodeOf(link.b)
        a.transport.bulkRoutes[b.nodeId] = route
        b.transport.bulkRoutes[a.nodeId] = route
    }
}
