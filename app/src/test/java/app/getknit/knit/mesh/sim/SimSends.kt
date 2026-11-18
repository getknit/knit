package app.getknit.knit.mesh.sim

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.lab.LabNode

/**
 * Sends one message of the history a walker brings to the outing ([Workload.backlog]) from its node among [nodes], a
 * group post through [chats] without recording it as the walk's. False when the stack refused it.
 */
internal suspend fun sendBacklog(
    nodes: List<LabNode>,
    chats: GroupChats,
    event: SimEvent,
): Boolean {
    val from = nodes[event.from]
    return when (event) {
        is SimEvent.Dm -> from.sendDm(nodes[event.to], event.body)
        is SimEvent.Room -> from.sendRoom(event.body)
        is SimEvent.Group -> chats.send(from, event, record = false)
    }
}

/**
 * How far the walk's [rooms] posts got among [nodes]: the share of the other walkers holding each at [endedAt] and by
 * now, with the numbers put in [summary].
 */
internal suspend fun roomLine(
    nodes: List<LabNode>,
    rooms: List<SimEvent.Room>,
    endedAt: Long,
    summary: SimSummary,
): String {
    val held = nodes.map { rows(it, Conversations.NEARBY) }
    var inRun = 0
    var byEnd = 0
    for (post in rooms) {
        for ((walker, rows) in held.withIndex()) {
            val arrivedAt = rows[post.body]?.arrivedAt?.takeIf { walker != post.from } ?: continue
            byEnd++
            if (arrivedAt <= endedAt) inRun++
        }
    }
    val audience = rooms.size * (nodes.size - 1)
    summary["room_sent"] = rooms.size
    summary["room_in_run_pct"] = share(inRun, audience)
    summary["room_by_end_pct"] = share(byEnd, audience)
    return "room posts: ${rooms.size} sent, reached ${percent(inRun, audience)} of the other walkers in the run, " +
        "${percent(byEnd, audience)} by the end"
}
