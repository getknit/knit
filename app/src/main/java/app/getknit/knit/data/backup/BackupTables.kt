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
            "peers",
            "met_peers",
            "groups",
            "group_roots",
            "commons",
            "commons_members",
            "commons_outbox",
            "messages",
            "reactions",
            "message_receipts",
            "drafts",
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
        )

    /**
     * Left empty in the backup because it describes this phone and nothing else: `saved_files` holds document
     * URIs into this phone's storage, readable only through grants this install holds (ADR 2026-09.7ad3). On
     * another phone they name nothing; a restored file bubble asks where to save, as it did the first time.
     */
    val DEVICE_LOCAL: List<String> = listOf("saved_files")

    /** Never copied: an index over `messages.body` that Room's content-sync triggers refill as the rows land. */
    val DERIVED: List<String> = listOf("messages_fts")
}
