---
id: "2026-09.uzkm"
slug: sqlcipher-is-keyed-with-the-raw-key
title: "SQLCipher is keyed with the raw key, and its pool holds four connections"
date: 2026-09-23
topics: [data, crypto, performance, backup]
---

# ADR 2026-09.uzkm — SQLCipher is keyed with the raw key, and its pool holds four connections

Status: Accepted (2026-09-23; `data/crypto/SqlCipherKey`, `KnitDatabase.build`)

## What was observed

A Firebase Test Lab run on a Galaxy A10 (API 29, 32-bit userspace) failed six seeded UI tests on a 25 s
wait: the chat list sat on its skeleton rows and search answered `No results for ""` to a typed query. It
read as flaky timeouts on a slow phone. It was the database. The Pixel 3 showed the same shape, only smaller:
from `am start`, **eleven** `Database keying operation` lines about 850 ms apart, and the chat list drew
right after the eleventh, about 10 s after launch. The A10 spent about 4.2 s per line, which projects to about
46 s. Lab phones rarely show it, because the foreground service keeps the process alive, so a true cold
start is rare.

Three things compound:

1. `DatabaseKey` hands SQLCipher its 32 random bytes as a **passphrase**, so every connection runs
   PBKDF2-HMAC-SHA512 at 256,000 iterations (SHA-512 is several times slower on a 32-bit core).
2. SQLCipher's WAL pool defaults to **ten** connections (`SQLiteGlobal.sWALConnectionPoolSize`), where the
   framework SQLite's default is four. Since Room 3 moved pooling under `SQLCipherDriver` (2026-08-31,
   `9656957f`), the startup burst of Room flows grows that pool to its ceiling.
3. The pool opens a connection **while holding its own lock** (`tryAcquireNonPrimaryConnectionLocked` calls
   `openConnectionLocked` under `mLock`). Every query waits out every derivation in turn, including the
   ones that could have used an idle connection.

## What changed

- **Raw key.** `KnitDatabase.build` keys the driver with `SqlCipherKey.raw(passphrase)`, the ASCII of
  `x'<64 hex>'`, which SQLCipher uses as the 256-bit key itself with no KDF. The passphrase is already 32
  uniformly random bytes from `SecureRandom`, so the KDF added nothing to it. Security is unchanged: it is the
  same secret, wrapped by the same Keystore key.
- **One-time rekey.** `SqlCipherKey.upgrade`, called by `build` before Room opens the file, tries the raw key
  first; that attempt costs no derivation. Only a file an older build wrote falls through to the
  passphrase, which pays one derivation and one `SQLiteDatabase.changePassword(raw)`, once per install. The
  connection it opens has no `ENABLE_WRITE_AHEAD_LOGGING`, so it puts the file in `delete` mode on open,
  exactly as Room's own driver open already does before Room turns WAL back on. SQLCipher's rekey therefore
  runs as one journalled transaction, and a crash part-way leaves the file on the old key for the next start
  to find. A file neither form opens is left byte-for-byte as found and never wiped: Room's open then fails
  loudly, as it would have before (ADR 008 still forbids a destructive fallback).
- **Pool of four.** `build` sets `SQLiteGlobal.setWALConnectionPoolSize(4)`, one writer plus three readers,
  the framework's default. With cheap opens this is not what makes startup fast any more. It keeps the page
  caches bounded and caps how often the pool opens under its lock.
- **Backups follow the live file.** `DatabaseExport` attaches the live file under the raw form, and the copy
  is built on the raw key (`SqlCipherKey.open`). `BackupWriter.openSqlCipher`, the restore stager's
  verifier, runs `upgrade` on the staged copy first, so a backup a pre-ADR development build wrote still
  restores. `docs/BACKUP_FORMAT.md` says so.

Measured on the Pixel 3 (compiled `speed-profile`, three cold starts): each connection now keys in
milliseconds, and all of them are done within 1.3 s of `am start`. The chat list is filled by about 2 s,
against about 10 s before. The rekey of its 7.7 MB `knit.db` took 2.8 s, once.

**The alternative a reader reaches for first**, deriving once in Kotlin and passing the derived key plus the
file's salt (`x'<key><salt>'`), keeps the on-disk key and needs no rekey. It does not work here.
`PBKDF2WithHmacSHA512` takes a `char[]` and would mangle random bytes. A hand-rolled PBKDF2 goes through JNI
once per HMAC, 256,000 times, which on the A10 is slower than the native derivation it would replace. And it
would still pay one derivation per process, forever.

## What it costs

- `upgrade` adds one plain open and close to every cold start, milliseconds on the raw key. An older build
  cannot open a rekeyed file, so a downgrade across this ADR fails at open, as it already did for schema
  downgrades (see the lab-branch trap in `.agents/memory`).
- The first start after the update pays the old cost once more (one derivation) plus the rekey, which grows
  with the file size.
- A wrong-key open is logged by SQLCipher at `E` with a stack trace (`SQLiteNotADatabaseException`), once, on
  the start that rekeys. That is the probe, not a fault.
- The key never exists as a `String`: `raw` writes the hex straight into a byte array, and the rekey goes
  through `changePassword(byte[])`, not a `PRAGMA rekey` literal. `DatabaseExport` wipes its raw copy only
  after the `ATTACH` steps, because `SQLCipherStatement` holds a bound array by reference until then.

Regression: `SqlCipherKeyTest` (JVM: which branch `upgrade` takes, and the raw form's bytes) and
`SqlCipherRawKeyTest` (androidTest, real SQLCipher: a passphrase-keyed database keeps its rows and afterwards
opens under the raw key only; a new database starts on the raw key; an unreadable file is untouched), plus
the existing `DatabaseExportSqlCipherTest` / `SqlCipherDriverUpgradeTest`. The seeded FTL suite on the A10
(`.private/scripts/ftl.sh`) is the end-to-end check that the cold start fits its 25 s waits.
