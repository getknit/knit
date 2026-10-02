package app.getknit.knit.di

import android.content.Context
import app.getknit.knit.data.crypto.AndroidKeystoreCipher
import app.getknit.knit.data.crypto.KeystoreCipher

/** Release-variant Keystore wiring: the real Keystore, nothing injected (the debug variant can fail it on cue). */
@Suppress("UNUSED_PARAMETER")
fun keystoreCipher(context: Context): KeystoreCipher = AndroidKeystoreCipher
