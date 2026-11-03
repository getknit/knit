package app.getknit.knit.transfer

/**
 * The model seam of a direct transfer (ADR 2026-11.ejn8): a bundle of pinned files, named on the wire only by the
 * pins of the receiver's own table (`TranslateDirection.pin`), so the manager learns nothing about translation. The
 * bundle is a deterministic tar of every chosen direction's files (`translate/ModelArchive`), in the order the ACCEPT
 * lists them. The receiver writes the archive it expects from its own pins and checks every byte against it before
 * anything is installed, so the sending phone is trusted with nothing, as getknit.app is.
 */
interface TransferModels {
    /** What this phone would take of [pins] (every one already checked to be a distinct unsigned 32-bit number). */
    fun wanted(pins: List<Long>): ModelsWanted

    /**
     * The sender's bundle of [pins] as one archive, each installed here; null when one is not. Named and typed as the
     * archive it is, since a receiver that cannot install models takes it as that file.
     */
    fun bundle(pins: List<Long>): TransferSource?

    /** A staging sink for [pins], every one in this phone's table; null when none could be made. */
    suspend fun sink(pins: List<Long>): TransferSink?
}

/**
 * The receiver's reading of a models offer: [take] the pins this table knows and this phone lacks, what they cost
 * ([bytes], from its own pins), and a [label] in this phone's words naming [take], or what it knows of the offer when
 * it would take nothing.
 */
data class ModelsWanted(
    val take: List<Long>,
    val bytes: Long,
    val label: String,
)
