package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.redact.FaceDetector
import app.getknit.knit.redact.YuNetFaceDetector
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Covering faces in a photo being sent (ADR 2026-10.whkk, its 2026-11-19 amendment): on in every build type, off in the
 * slim APK or with `-PfaceRedaction=false`; on, the build packages the YuNet model (`src/faceRedaction/assets`). The one
 * read of `BuildConfig.FACE_REDACTION`. Off, [faceRedactionModule] is null, Settings › Chats shows no Cover faces row, every photo
 * door skips the review whatever the store holds, and R8 drops every class only this graph reaches.
 */
const val FACE_REDACTION_WIRED: Boolean = BuildConfig.FACE_REDACTION

/** The face detector, or null in a build without face redaction ([FACE_REDACTION_WIRED]). */
fun faceRedactionModule(): Module? = if (FACE_REDACTION_WIRED) faceRedactionGraph() else null

private fun faceRedactionGraph(): Module =
    module {
        // Leased on the app scope like the classifiers, under the same poison-pill, and loaded only when a photo is reviewed.
        single<FaceDetector> { YuNetFaceDetector(androidContext(), guard = get(), scope = get<CoroutineScope>()) }
    }
