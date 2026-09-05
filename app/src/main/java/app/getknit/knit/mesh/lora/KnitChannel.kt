package app.getknit.knit.mesh.lora

/**
 * The single, well-known LoRa channel Knit provisions onto a Meshtastic board so any two boards converge
 * with zero coordination. Its [PSK] is *derived* (see `KnitChannelTest`) — deliberately not secret — from
 * Knit's public constants via HKDF-SHA256. Because the Nearby room is itself cleartext (frames are
 * Ed25519-signed, not encrypted), a public, reproducible key is the honest choice: this channel is a
 * *rendezvous* — it keeps Knit's LoRa traffic off the board's own primary channel and lets any two Knit boards
 * interoperate — never a confidentiality boundary. Knit's per-frame signatures remain the integrity boundary.
 *
 * Knit writes it as a SECONDARY channel, so the board's primary channel and its radio config (region, modem
 * preset — a per-board, legally-scoped setting) are never touched.
 */
internal object KnitChannel {
    /** The channel name; Meshtastic folds name + [PSK] into the on-air channel hash both boards must match. */
    const val NAME = "Knit"

    /** HKDF inputs, mirrored by `KnitChannelTest` so the exact derivation recipe is pinned by a test. */
    const val IKM = "nearby" // Conversations.NEARBY — the public id of the broadcast room this channel carries
    const val INFO = "knit/lora/channel/psk/v1"
    const val PSK_BYTES = 16 // AES128 — Meshtastic's own default key length

    /**
     * 16-byte (AES128) PSK == HKDF-SHA256(ikm=[IKM], salt=0³², info=[INFO], len=[PSK_BYTES]). Pinned here so
     * this pure module needs no crypto runtime; the test re-derives and asserts equality, so any change to
     * the inputs OR these bytes — which would silently break interop with already-provisioned boards — fails
     * the build.
     */
    val PSK: ByteArray =
        byteArrayOf(
            0xF2.toByte(),
            0xED.toByte(),
            0x36,
            0xF8.toByte(),
            0x05,
            0x43,
            0xDE.toByte(),
            0x29,
            0x2A,
            0x74,
            0xCB.toByte(),
            0xE0.toByte(),
            0x8A.toByte(),
            0x03,
            0x4F,
            0x3A,
        )

    /** The [ChannelWrite] Knit sends via `set_channel` for a chosen secondary [index]. */
    fun write(index: Int): ChannelWrite = ChannelWrite(index = index, name = NAME, psk = PSK)
}
