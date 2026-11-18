package app.getknit.knit.mesh.sim

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.MeshLab
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The outing's group chats: one for each party of [Workload.GROUP_CHAT_MIN] or more ([Workload.parties]), made at the
 * boot while the party is linked, with every member's first post sent there so each holds every other's chain before
 * the walk, as friends who already chat in a group do. Then the posts the walk sends, how far each got, and the
 * oracle's check of each thread.
 */
class GroupChats(
    private val lab: MeshLab,
    private val parties: List<List<Int>>,
) {
    private val ids = ConcurrentHashMap<Int, String>()
    private val posts = CopyOnWriteArrayList<SimEvent.Group>()

    /**
     * Every group post's frame id (the boot's, the backlog's and the walk's), read off its sender's row as it is sent:
     * what a custody row is matched against.
     */
    val frameIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How many group posts the walk sent. */
    val sent: Int get() = posts.size

    /**
     * Makes party [party]'s chat among [members], the party's nodes in walker order, while they are linked: the first
     * makes it and posts, the rest post once they hold it, and the boot returns once every member holds every other's
     * chain. A party too small for a chat makes none.
     */
    suspend fun open(
        party: Int,
        members: List<LabNode>,
    ) {
        if (members.size < Workload.GROUP_CHAT_MIN) return
        val (first, others) = members.first() to members.drop(1)
        val id = first.createGroup(*others.toTypedArray())
        ids[party] = id
        check(hello(first, id)) { "${first.name} could not post in its party's chat" }
        lab.await(others.size) { others.count { it.group(id) != null } }
        for (member in others) check(hello(member, id)) { "${member.name} could not post" }
        val chains = members.size * (members.size - 1)
        lab.await(chains) { members.sumOf { holder -> members.count { it !== holder && holder.holdsGroupChainFrom(id, it) } } }
    }

    /**
     * Sends [event] from [from] into its party's chat; false when the stack refused it. [record] counts it as the walk's
     * (a backlog post is not); its frame id is noted either way, so custody tells every group post from a room post.
     */
    suspend fun send(
        from: LabNode,
        event: SimEvent.Group,
        record: Boolean = true,
    ): Boolean {
        val id = checkNotNull(ids[event.party]) { "party ${event.party} has no chat" }
        if (!post(from, id, event.body)) return false
        if (record) posts += event
        return true
    }

    private suspend fun hello(
        member: LabNode,
        id: String,
    ): Boolean = post(member, id, "hello from ${member.name}")

    // One post, its frame id noted off the sender's own row.
    private suspend fun post(
        from: LabNode,
        id: String,
        body: String,
    ): Boolean {
        if (!from.sendGroup(id, body)) return false
        rows(from, id, parked = true)[body]?.id?.let(frameIds::add)
        return true
    }

    /**
     * The report's line, with its numbers put in [summary]: the share of each post's other party members holding it at
     * [endedAt] and by now, over [nodes].
     */
    suspend fun line(
        nodes: List<LabNode>,
        endedAt: Long,
        summary: SimSummary,
    ): String? {
        if (ids.isEmpty()) return null
        var inRun = 0
        var byEnd = 0
        var audience = 0
        val held = heldBy(nodes)
        for (post in posts) {
            for (walker in parties[post.party] - post.from) {
                audience++
                val row = held.getValue(walker)[post.body] ?: continue
                byEnd++
                if ((row.arrivedAt ?: Long.MAX_VALUE) <= endedAt) inRun++
            }
        }
        summary["group_chats"] = ids.size
        summary["group_sent"] = posts.size
        summary["group_in_run_pct"] = share(inRun, audience)
        summary["group_by_end_pct"] = share(byEnd, audience)
        return "group chats: ${ids.size}, ${posts.size} posts sent, reached ${percent(inRun, audience)} of their other " +
            "members in the run, ${percent(byEnd, audience)} by the end"
    }

    /** [missing], with its count put in [summary] when the walk posted in a chat at all. */
    suspend fun stranded(
        nodes: List<LabNode>,
        summary: SimSummary,
    ): List<String> {
        if (posts.isEmpty()) return emptyList()
        return missing(nodes).also { summary["gather_group_posts_missing"] = it.size }
    }

    /** Each post some other member of its party still lacks, by body, read over [nodes]. */
    suspend fun missing(nodes: List<LabNode>): List<String> {
        val held = heldBy(nodes)
        return posts
            .filter { post -> (parties[post.party] - post.from).any { post.body !in held.getValue(it) } }
            .map { it.body }
    }

    // Each walker in a party with a chat, to the rows its chat holds by body.
    private suspend fun heldBy(nodes: List<LabNode>): Map<Int, Map<String, MessageEntity>> =
        ids.entries
            .flatMap { (party, id) -> parties[party].map { walker -> walker to rows(nodes[walker], id) } }
            .toMap()

    /**
     * The lab's oracle over each chat a post went in: every member holds the same posts, ticks and custody parity
     * across [nodes]. Returns how the first chat to fail it failed, or null.
     */
    suspend fun oracle(
        nodes: List<LabNode>,
        timeoutMs: Long,
    ): String? {
        for ((party, count) in posts.groupingBy { it.party }.eachCount()) {
            val members = parties[party].map { nodes[it] }
            val id = ids.getValue(party)
            val failure =
                runCatching { lab.assertConverged(members, count, nodes - members.toSet(), timeoutMs = timeoutMs) { id } }
                    .exceptionOrNull() ?: continue
            return "group chat of party $party did not converge: ${failure.message.orEmpty().substringBefore('\n').take(WHY_CHARS)}"
        }
        return null
    }

    private companion object {
        const val WHY_CHARS = 300
    }
}
