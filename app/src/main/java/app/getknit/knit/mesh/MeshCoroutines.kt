package app.getknit.knit.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Last-resort handler for an **uncaught** throw in a top-level mesh coroutine, so a stray exception is
 * logged instead of crashing the process (app scope) or vanishing silently (session scope). It is honored
 * only for a coroutine launched directly on a [kotlinx.coroutines.SupervisorJob] scope — which the mesh's
 * two scopes are: the app-lifetime scope (`di/MeshModule.kt`, which the transports and each `FramedLink`
 * writer run on) and `MeshManager`'s per-session scope (the router/heal/watch collectors).
 *
 * It is a backstop, not the fix for any specific bug — e.g. an oversized frame can no longer reach the
 * writer (KeyExchange chunks its batches and `FramedLink` drops a rejected record), but if some other
 * coroutine ever throws unexpectedly, this keeps the app alive with a diagnosable log line.
 */
val meshExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        Log.w("MeshScope", "uncaught exception in a mesh coroutine", t)
    }
