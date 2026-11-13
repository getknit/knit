package app.getknit.knit.mesh.lora.meshcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * One frame of a simulator run (`src/test/resources/meshcore/<run>.frames.jsonl`, see its `NOTICE.txt`): the node, the
 * direction, the bytes, and the fields the bench's own decoder wrote beside them, which the golden tests check ours
 * against.
 */
internal class SimFrame(
    val node: String,
    val tx: Boolean,
    val ms: Long,
    val bytes: ByteArray,
    private val fields: JsonObject,
) {
    val code: Int get() = bytes[0].toInt() and 0xFF

    fun int(name: String): Int? = (fields[name] as? JsonPrimitive)?.intOrNull

    fun long(name: String): Long? = (fields[name] as? JsonPrimitive)?.longOrNull

    fun string(name: String): String? = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun toString(): String = "$node ${if (tx) "tx" else "rx"} ${bytes.size} B code $code"
}

internal object SimFrames {
    /** Every run copied into the test resources. */
    val RUNS = listOf("q11", "q12", "q13-receiver_unknown", "q13-sender_unknown", "q14-same_attempt", "q14-next_attempt", "q15")

    fun load(run: String): List<SimFrame> {
        val text =
            requireNotNull(javaClass.classLoader?.getResource("meshcore/$run.frames.jsonl")) { "missing meshcore/$run.frames.jsonl" }
                .readText()
        return text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val o = Json.parseToJsonElement(line).jsonObject
                SimFrame(
                    node = (o["node"] as JsonPrimitive).content,
                    tx = (o["dir"] as JsonPrimitive).content == "tx",
                    ms = (o["ms"] as JsonPrimitive).longOrNull ?: 0L,
                    bytes = hex((o["hex"] as JsonPrimitive).content),
                    fields = o,
                )
            }.toList()
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
