package app.getknit.knit.mesh.lora

/**
 * A [LoraTrace] that keeps every fact the plane notes, in order, for the trace tests. [enabled] is what [on] reads; a fact
 * noted while it is false is recorded in [notedWhileOff] (the plane must never build one). [failing] makes every note throw,
 * as a broken trace would: the plane must swallow it and carry on.
 */
internal class RecordingTrace(
    @Volatile var enabled: Boolean = true,
    private val failing: Boolean = false,
) : LoraTrace {
    val byId = mutableListOf<Pair<String, LoraTraceEvent>>()
    val byHandle = mutableListOf<Pair<Long, LoraTraceEvent>>()
    var notedWhileOff = 0
    var throws = 0

    override val on: Boolean get() = enabled

    override fun note(
        id: String,
        event: LoraTraceEvent,
    ) {
        if (!enabled) notedWhileOff++
        if (failing) {
            throws++
            error("a broken trace")
        }
        synchronized(byId) { byId += id to event }
    }

    override fun noteByHandle(
        handle: Long,
        event: LoraTraceEvent,
    ) {
        if (!enabled) notedWhileOff++
        if (failing) {
            throws++
            error("a broken trace")
        }
        synchronized(byHandle) { byHandle += handle to event }
    }

    /** Every event noted under [id], in order. */
    fun of(id: String): List<LoraTraceEvent> = synchronized(byId) { byId.filter { it.first == id }.map { it.second } }

    /** Every event noted under [handle], in order. */
    fun ofHandle(handle: Long): List<LoraTraceEvent> = synchronized(byHandle) { byHandle.filter { it.first == handle }.map { it.second } }

    /** The events of [T] noted under [id]. */
    inline fun <reified T : LoraTraceEvent> typed(id: String): List<T> = of(id).filterIsInstance<T>()
}
