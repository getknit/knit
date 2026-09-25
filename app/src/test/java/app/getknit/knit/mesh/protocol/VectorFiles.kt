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

    @OptIn(ExperimentalSerializationApi::class)
    fun write(
        name: String,
        content: JsonElement,
    ) {
        val json =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
        locate(name).writeText(json.encodeToString(JsonElement.serializer(), content) + "\n")
    }

    private fun locate(name: String): File {
        val candidates = listOf("../vectors", "vectors").map(::File)
        val directory =
            candidates.firstOrNull { it.isDirectory }
                ?: error("vectors/ not found from ${File(".").absolutePath}")
        return File(directory, name)
    }
}
