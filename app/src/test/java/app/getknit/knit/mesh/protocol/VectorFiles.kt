package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The JSON files in the repo-root `vectors/` directory: the byte-exact fixtures every Knit port is tested
 * against (see `vectors/README.md`). Gradle runs unit tests from the module directory and an IDE may run them
 * from the repo root, so both locations are tried.
 *
 * Setting `KNIT_WRITE_VECTORS=1` puts the vector tests in write mode: each one rewrites its file from the
 * fixtures it builds instead of comparing against it. Only an intended wire change does that, and the diff
 * shows every byte that moved.
 */
internal object VectorFiles {
    val writing: Boolean get() = System.getenv("KNIT_WRITE_VECTORS") == "1"

    fun read(name: String): JsonObject = Json.parseToJsonElement(locate(name).readText()).jsonObject

    fun write(
        name: String,
        content: JsonElement,
        asciiOnly: Boolean = false,
    ) {
        locate(name).writeText(render(content, asciiOnly))
    }

    /**
     * The file's text for [content], byte for byte what [write] puts there; a test comparing a regeneration reads this.
     * [asciiOnly] writes DEL and every non-ASCII UTF-16 unit as a `\uXXXX` escape, for a file whose strings carry invisible
     * code points a reviewer could not otherwise see (`name-key-v1.json`); it parses to the same strings.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun render(
        content: JsonElement,
        asciiOnly: Boolean = false,
    ): String {
        val json =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
        val text = json.encodeToString(JsonElement.serializer(), content) + "\n"
        return if (asciiOnly) text.escapeNonAscii() else text
    }

    /** DEL and the non-ASCII units become `\uXXXX`: safe anywhere in the rendered file, since JSON's syntax is all ASCII. */
    private fun String.escapeNonAscii(): String =
        buildString(length) {
            for (c in this@escapeNonAscii) if (c.code < DEL) append(c) else append("\\u%04X".format(c.code))
        }

    private const val DEL = 0x7F

    /** The file's text as it stands. */
    fun text(name: String): String = locate(name).readText()

    private fun locate(name: String): File {
        val candidates = listOf("../vectors", "vectors").map(::File)
        val directory =
            candidates.firstOrNull { it.isDirectory }
                ?: error("vectors/ not found from ${File(".").absolutePath}")
        return File(directory, name)
    }
}
