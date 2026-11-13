package app.getknit.knit.mesh.lora

/**
 * A Meshtastic board's node number, or null for another kind's — the tests' read of an address typed as the neutral
 * [BoardAddr] (the lab compares a holder's board with its own node's). Main code reads [BoardAddr.Meshtastic.node], or
 * `meshtasticNode` on a Meshtastic-only path, never this (ADR 2026-11.gsqu, Stage 1b.3).
 */
internal val BoardAddr.node: UInt? get() = (this as? BoardAddr.Meshtastic)?.node
