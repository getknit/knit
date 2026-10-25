package app.getknit.knit.fieldlog

/**
 * Sees each event the moment [FieldLog.record] takes it, whatever the field log's own switch: the developer view's event
 * console (ADR 2026-10.s7wg, its slice-2 amendment). Called on the recording thread, often a radio's, so it must be cheap
 * and never block. It sees the event as recorded, before the queue fills in a relink time. [NONE] sees nothing.
 */
fun interface FieldTap {
    fun saw(
        at: Long,
        event: FieldEvent,
    )

    companion object {
        val NONE = FieldTap { _, _ -> }
    }
}
