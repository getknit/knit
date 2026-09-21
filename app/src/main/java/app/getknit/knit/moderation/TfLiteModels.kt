package app.getknit.knit.moderation

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The two things every bundled model shares: how its bytes reach the [Interpreter], and how the interpreter
 * is configured. Both moderators go through here so a change to either is one edit.
 */
internal object TfLiteModels {
    /**
     * Interpreter threads, and so XNNPACK's pool. Two halves the wall time of an ALBERT pass over 128 tokens
     * for about the same energy; more only makes the slowest core the wall time on a big.LITTLE part. Pinned
     * rather than left to the runtime's default so an inference is a bounded burst on every SoC.
     */
    const val INFERENCE_THREADS = 2

    /**
     * Map a **stored** (uncompressed) asset straight out of the APK. `noCompress "tflite"` in the app's
     * `androidResources` block is what makes `openFd` succeed; a compressed or missing asset throws
     * `FileNotFoundException`, which the caller's asset-failure catch turns into allow-all.
     *
     * Why not `readBytes()` into `allocateDirect`: on ART a direct buffer is a `byte[]` on the Java heap, and
     * the read holds a second copy while it fills — a ~30 MB heap spike per model, and the model's whole size
     * charged to the heap for as long as it stays loaded. A mapping is file-backed and clean: the kernel can
     * drop and re-read its pages under pressure, and nothing is copied. TFLite reads the flatbuffer in place,
     * so the returned buffer must outlive the interpreter built on it — keep it beside the interpreter, not
     * as a local.
     */
    fun mapAsset(
        context: Context,
        asset: String,
    ): MappedByteBuffer =
        context.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /**
     * Explicit options for both models. XNNPACK is most likely the runtime's own default on Android already,
     * so naming it is documentation more than behaviour — but it can change the kernel path on an unfamiliar
     * SoC, which is exactly the fault [ModelLoadGuard] exists for, and the version-code half of its stamp
     * gives every device a fresh attempt on the release that ships this.
     */
    fun interpreterOptions(): Interpreter.Options = Interpreter.Options().setNumThreads(INFERENCE_THREADS).setUseXNNPACK(true)
}
