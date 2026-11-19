package app.getknit.knit.mesh.lora.meshcore

/**
 * A MeshCore board's advert name as its owner's handle (ADR 2026-11.f4d3): the raw bytes `SELF_INFO` reported, which the
 * firmware writes in front of every Public post (`"%s: "`, `BaseChatMesh.cpp:523`). Compared as bytes, never through a
 * `String`: a name another client cut mid-character must still match what receivers read. It may be a person's name, so
 * [toString] never prints it, and nothing logs it.
 *
 * Public, not internal, because the composer's consent decision carries one down `MeshController.sendPublicPost` to the
 * plane (`MeshcorePublicChannel.post`), which refuses a post whose consented handle is not the board's name now.
 */
class MeshcoreHandle(
    bytes: ByteArray,
) {
    private val raw = bytes.copyOf()

    /** A copy of the name's bytes, as the board reported them. */
    val bytes: ByteArray get() = raw.copyOf()

    /** How many bytes the name takes: what the firmware's prefix costs past `": "`. */
    val size: Int get() = raw.size

    /** The name decoded leniently (U+FFFD for what is not UTF-8), raw and uncleaned: clean it before drawing it. */
    val text: String get() = raw.decodeToString()

    /** Whether [name] is exactly these bytes. */
    fun matches(name: ByteArray): Boolean = raw.contentEquals(name)

    override fun equals(other: Any?): Boolean = other is MeshcoreHandle && other.raw.contentEquals(raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "MeshcoreHandle"
}
