package app.getknit.knit.ui.redact

/** Where the face search stands while the review screen is up. */
enum class FaceSearch {
    /** The detector is still running; the photo is shown but cannot be edited yet. */
    Looking,

    /** The detector ran; its faces are the covers shown. */
    Done,

    /** No detector could run here (`FaceDetector.detect` answered null): every face is the person's to cover. */
    Unavailable,
}
