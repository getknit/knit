package app.getknit.knit.redact

import android.graphics.Bitmap

/** Finds the faces in a picture, on this phone. */
interface FaceDetector {
    /**
     * The faces in [bitmap], highest score first, in its pixels; null when no detector could run (the model is missing,
     * failed to load, or was latched off), which a caller must tell apart from an empty list: a picture nobody looked
     * at is not a picture with no faces.
     */
    suspend fun detect(bitmap: Bitmap): List<FaceBox>?
}
