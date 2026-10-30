package app.getknit.knit.data.settings

/**
 * The bound Meshtastic board as the profile advertises it (`ProfileContent.loraNode` / `loraKey`): its node
 * number and its Curve25519 public key as base64 of 32 bytes. [key] is null unless the node number is the key's
 * CRC32 — the binding firmware 2.8 makes at keygen — so a pre-2.8 board (a MAC-derived number) or one with no key
 * advertises none. Whether the board signs does not matter (#243): the key also lets a contact's board PKI-encrypt
 * a compact DM to ours (ADR 2026-10.aajj).
 *
 * One value on purpose, read from one settings snapshot ([SettingsStore.loraBoard]). The two fields are
 * written and cleared together, and a reader that projected them through two flows could see one edit as
 * two — or, conflated, miss a bind that an unbind followed — and republish a profile naming a board this
 * phone no longer holds.
 */
data class LoraBoard(
    val node: Long,
    val key: String?,
)
