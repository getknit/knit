package app.getknit.knit.data.crypto

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomically (re)writes [bytes] to this file: writes to a sibling `.tmp`, fsyncs it, then atomically
 * renames it over the target. After this returns — or after a crash at any point — the target holds
 * either the complete previous contents or the complete new contents, **never** a truncated file.
 *
 * This matters because the callers' read paths treat a malformed or unverifiable file as proof the key is
 * lost and respond *destructively*: [DatabaseKey] wipes the entire encrypted database, and [IdentityKeyStore]
 * mints a fresh identity keypair — a new nodeId that breaks every peer's pinned key. A truncated wrap *is* such
 * proof ([Loss.MALFORMED], or a tag that fails), so a plain [File.writeBytes], which truncates the live file
 * first, could hand a mid-write crash exactly those destructive fallbacks. See ARCHITECTURE_REVIEW.md item #12;
 * a Keystore that merely refuses no longer takes them (ADR 2026-10.47rw).
 *
 * Hardens the [app.getknit.knit.ui.invite.ApkMerger] temp+rename precedent with an fsync and
 * `ATOMIC_MOVE` (vs. [File.renameTo]), since the blast radius here is the DB/identity rather than a
 * re-mergeable APK. The temp sibling lives in the same directory so the rename stays on one filesystem
 * (required for [StandardCopyOption.ATOMIC_MOVE]).
 */
internal fun File.writeBytesAtomically(bytes: ByteArray) {
    val tmp = File(absoluteFile.parentFile, "$name.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync() // durability: bytes hit disk before the rename commits
    }
    try {
        // ATOMIC_MOVE maps to rename(2) on Android/POSIX, which atomically replaces the target.
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        // No-op after a successful move (the temp was renamed away); cleans up a leftover temp on failure.
        tmp.delete()
    }
}
