package app.getknit.knit.identity

/**
 * The read-only slice of [Identity] the inbound frame pipeline needs: this node's self-certifying
 * [nodeId] and the [publicKeyBundle] its own frames verify against (a self-frame carried by a neighbor
 * and re-served after our SeenSet window lapsed). Extracted as a seam so
 * [app.getknit.knit.mesh.InboundPipeline] can be exercised in a plain JVM test against a fake, rather
 * than the real AndroidKeyStore-backed [Identity]. [Identity] implements it verbatim.
 *
 * `deviceTag()` is deliberately absent: it rides only on **outbound** profile origination
 * (`currentProfileEnvelope`), which stays in `MeshManager`.
 */
interface IdentitySource {
    /** This device's node id — the self-certifying hash of its [publicKeyBundle]. */
    suspend fun nodeId(): String

    /** The base64 public-key bundle this device advertises in its profile (`ProfileContent.pubKey`). */
    fun publicKeyBundle(): String
}
