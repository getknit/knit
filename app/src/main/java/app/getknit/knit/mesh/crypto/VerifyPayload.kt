package app.getknit.knit.mesh.crypto

/**
 * The payload encoded in a device's identity QR code: its node id and public-key bundle. Scanning a
 * peer's code lets us confirm the key we pinned for them really is theirs (key-change / MITM defense).
 * Format `knit-id:v1:<nodeId>:<bundle>` — the bundle is base64 (no ':' in its alphabet), so the first
 * three ':' unambiguously delimit the fields.
 */
object VerifyPayload {
    private const val PREFIX = "knit-id:v1:"

    fun encode(
        nodeId: String,
        bundle: String,
    ): String = "$PREFIX$nodeId:$bundle"

    data class Parsed(
        val nodeId: String,
        val bundle: String,
    )

    fun parse(payload: String): Parsed? {
        if (!payload.startsWith(PREFIX)) return null
        val rest = payload.removePrefix(PREFIX)
        val nodeId = rest.substringBefore(':', "")
        val bundle = rest.substringAfter(':', "")
        return if (nodeId.isBlank() || bundle.isBlank()) null else Parsed(nodeId, bundle)
    }
}
