package app.getknit.knit.crash

/** A fixed environment so header assertions state only what they care about. */
internal fun testEnvironment(
    versionName: String = "2.3.0",
    obfuscated: Boolean = false,
) = CrashEnvironment(
    versionName = versionName,
    versionCode = 13,
    buildType = "debug",
    obfuscated = obfuscated,
    manufacturer = "Google",
    model = "Pixel 8",
    device = "shiba",
    board = "shiba",
    hardware = "shiba",
    soc = "Google Tensor G3",
    sdkInt = 36,
    release = "16",
    abis = "arm64-v8a, armeabi-v7a",
    fingerprint = "google/shiba/shiba:16/BP41.250822.001/13456789:user/release-keys",
)

/** The text [TraceText] carried in a printed [trace], read back the way `scripts/trace-text.py` reads a Play report. */
internal fun carriedText(trace: String): String {
    val chunks = Regex("""at knit\.TraceText\.t(\w+)\(TraceText:\d+\)""").findAll(trace).joinToString("") { it.groupValues[1] }
    val bytes = java.io.ByteArrayOutputStream()
    var at = 0
    while (at < chunks.length) {
        if (chunks[at] == '_') {
            bytes.write(chunks.substring(at + 1, at + 3).toInt(16))
            at += 3
        } else {
            bytes.write(chunks[at].code)
            at++
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

/** A throwable whose message is [message] and whose frames are stable across runs. */
internal fun throwableWith(message: String): Throwable =
    IllegalStateException(message).apply {
        stackTrace =
            arrayOf(
                StackTraceElement("app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport", "hello", "BluetoothMeshTransport.kt", 552),
                StackTraceElement("app.getknit.knit.mesh.MeshRouter", "route", "MeshRouter.kt", 91),
            )
    }
