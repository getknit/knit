package app.getknit.knit.data.backup

/**
 * Every table in `knit.db`, classified for the backup. Pinned to the exported schema by
 * `BackupTablesTest`, so a new table has to be placed here before the build is green.
 *
 * **Carried** tables are the user's things and the trust behind them: message history and its
 * bytes, the people and groups it is with, what this phone has decided about them. **Transient**
 * tables are re-derived by the mesh from a standing start and are left out on purpose: custody
 * refills from the digest pull (`ForwardSync.onDigest` re-serves a node its own frames), and a
 * ratchet or sender-key chain carried forward from a snapshot would re-seal indices its peers have
 * already consumed — every such frame drops as a `DUPLICATE`, which the reset heuristic never counts
 * (ADR 024), so the loss is silent. The replacement/reset path was built for "wipe / reinstall"
 * (`docs/FORWARD_SECRECY_RATCHET.md` §7, `docs/GROUP_FORWARD_SECRECY.md` advance rule 5), and a restore
 * is one. Leaving them out also keeps forward-secrecy material out of the file.
 */
object BackupTables {
    /** Copied into the backup, in this order: what a row points at comes before the row. */
    val CARRIED: List<String> =
        listOf(
            "blobs",
            "blob_verdicts",
            // A photo refused over its ceiling (ADR 2026-11.4ckq): a fact about a hash, carried as its verdict is, so a
            // restored phone does not fetch it again from every holder.
            "photo_refusals",
            "attachment_holders",
            "peers",
            "peer_profiles",
            "peer_badges",
            "met_peers",
            "groups",
            "group_member_clocks",
            "group_roots",
            "commons",
            "commons_members",
            "commons_outbox",
            "messages",
            "reactions",
            "pins",
            // The stickers saved to the composer's tray (ADR 2026-11.rk6d): the user's own collection, naming blobs this
            // list carries first.
            "stickers",
            "message_receipts",
            "drafts",
            "scope_configs",
            "spool_declared",
            // Each DM scope's anchor (ADR 2026-11.dgj2, #324): the scope a restore's reset is pushed into, so a contact
            // the relay is the only path to hears it. Ids and outer seal keys only, never a root or a chain.
            "dm_scope_anchors",
        )

    /** Left empty in the backup; the mesh rebuilds each from nothing. */
    val TRANSIENT: List<String> =
        listOf(
            "forward_store",
            "ratchet_sessions",
            "ratchet_local_epochs",
            "ratchet_recv_epochs",
            "ratchet_skipped_keys",
            "group_send_chains",
            "group_recv_chains",
            "group_skipped_keys",
            "group_key_sends",
            // Which DMs a compact LoRa copy delivered and whether the signed copy's ratchet step ran (ADR 2026-10.x9fa):
            // ratchet state, which a restore re-derives from nothing like the sessions it describes.
            "lora_direct_seen",
            // Presence keys (ADR 2026-11.xp6d): our own are minted afresh after a restore (the carried generation counter
            // keeps their numbers above the old ones), and a contact's would let a backup file recognise every contact's
            // phone; a restore re-roots every session, so contacts hand theirs again over the next link.
            "own_presence_keys",
            "peer_presence_keys",
            "link_owed_resets",
            // The reads owed to their authors (ADR 2026-11.z6jw): a restore re-roots every session, so a read
            // owed or told under the old ones would buy only duplicates.
            "read_marks",
        )

    /**
     * Left empty in the backup because it describes this phone and nothing else: `saved_files` holds document
     * URIs into this phone's storage, readable only through grants this install holds (ADR 2026-09.7ad3). On
     * another phone they name nothing; a restored file bubble asks where to save, as it did the first time.
     */
    val DEVICE_LOCAL: List<String> =
        listOf(
            "saved_files",
            // A MeshCore setup's record (ADR 2026-11.gsqu's Stage 5b-2 amendment): the user's own board's prior name and
            // settings, and this phone's consent to touch it. About that board and this phone alone (`BoardKeys`' rule for
            // the setup records, ADR 2026-09.6mj7): a restored phone sets its board up again.
            "meshcore_setup",
        )

    /** Never copied: an index over `messages.body` that Room's content-sync triggers refill as the rows land. */
    val DERIVED: List<String> = listOf("messages_fts")
}
