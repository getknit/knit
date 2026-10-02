package app.getknit.knit.di

import android.content.Context
import app.getknit.knit.data.crypto.FaultyKeystoreCipher
import app.getknit.knit.data.crypto.KeystoreCipher
import java.io.File

/**
 * Debug-variant Keystore wiring: the real Keystore, made to refuse the next N unwraps when
 * `files/keystore-fault` holds N — how a device trial reproduces the refusal ADR 2026-10.47rw is about without a
 * flaky StrongBox. `src/release` returns the real cipher.
 */
fun keystoreCipher(context: Context): KeystoreCipher = FaultyKeystoreCipher(File(context.filesDir, FaultyKeystoreCipher.MARKER))
