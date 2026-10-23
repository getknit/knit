package app.getknit.knit.ui.translate

/**
 * The translation spike's native library (ADR 2026-10.t2h6): slimt and CLD2 behind `translate-native`'s JNI, packaged
 * only in a debug build made with `-Ptranslate=true`. Declared here, in main, so main compiles without the module; a
 * build without it never reaches this class (`TRANSLATE_WIRED`), and [available] says false if one somehow did.
 *
 * Text crosses as UTF-8 bytes both ways: JNI's own string conversion is modified UTF-8, which writes an emoji as two
 * surrogates no tokenizer reads.
 */
internal object TranslateJni {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("knittranslate") }.isSuccess }

    /** Whether the library loaded. */
    fun available(): Boolean = loaded

    /** One direction's model; 0 when slimt refused the files. Free with [nativeFree]. */
    @JvmStatic
    external fun nativeLoad(
        model: String,
        vocabulary: String,
        shortlist: String,
        encoderLayers: Int,
        decoderLayers: Int,
    ): Long

    /** [text] (UTF-8) translated by the model behind [handle], as UTF-8. One call at a time per handle. */
    @JvmStatic
    external fun nativeTranslate(
        handle: Long,
        text: ByteArray,
    ): ByteArray?

    @JvmStatic
    external fun nativeFree(handle: Long)

    /** CLD2's answer for [text] (UTF-8), as `Detection.parse` reads it, boosting [hint] (a language code, or empty). */
    @JvmStatic
    external fun nativeDetect(
        text: ByteArray,
        hint: String,
    ): String
}
