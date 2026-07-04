package app.getknit.knit.mesh.wifiaware

/**
 * P0 experiment toggles for the NAN lifecycle redesign (`docs/NAN_CONCURRENCY_REAUDIT.md` §4), flipped at
 * runtime by the debug bridge (`app.getknit.knit.debug.NANEXP`; debug builds only — nothing in a release
 * build writes these). All default **off**, so an untouched process behaves exactly like production.
 * In-memory only: a process restart resets them. P2/P4 promote the validated paths and delete this object.
 */
object NanExperiments {
    /**
     * E4b: on an EOF-driven server-link end, recycle the accept-any responder request (unregister **while
     * the served NDP is still alive** — the framework's clean teardown path — then re-file) instead of the
     * after-serve session reattach; initiator teardowns hold their `requestNetwork` open for a short
     * release-grace so the FIN lands and the responder's recycle window exists.
     */
    @Volatile var e4bRecycle = false

    /** E5: relight ICM via an in-place `updatePublish` keepalive instead of the churn-prone subscribe re-arm. */
    @Volatile var e5Keepalive = false

    /** E5: run the keepalive on cadence regardless of sync demand, so a continuously-lit window is measurable. */
    @Volatile var e5Force = false

    /** E5: append an incrementing `|p<n>` SSI segment per keepalive — probes peer re-discovery on SSI change. */
    @Volatile var e5SsiProbe = false
}
