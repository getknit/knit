package app.getknit.knit.ui.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #284 (ADR 2026-11.vdx4): a URI another app hands Knit is read only when it is a `content://` URI on someone else's
 * provider. Anything else would be opened with Knit's own permissions. Parts are given as `Uri.getScheme` and
 * `Uri.getAuthority` return them for the URI in each case's name.
 */
class OutsideUrisTest {
    private val rule = OutsideUris(setOf("app.getknit.knit.fileprovider", "app.getknit.knit.androidx-startup"))

    @Test
    fun aFilePathIsRefusedWhereverItPoints() {
        // file:///data/data/app.getknit.knit/files/datastore/knit_settings.preferences_pb
        assertFalse(rule.admits("file", null))
        assertFalse(rule.admits("file", ""))
        // file://localhost/sdcard/Download/x.pdf: even a public path is opened as Knit, so the scheme alone refuses it.
        assertFalse(rule.admits("file", "localhost"))
    }

    @Test
    fun anyOtherSchemeIsRefused() {
        assertFalse("android.resource://app.getknit.knit/raw/x", rule.admits("android.resource", "app.getknit.knit"))
        assertFalse("CONTENT:// is not what the resolver opens as content", rule.admits("CONTENT", "com.example.provider"))
        assertFalse("https://example.com/x", rule.admits("https", "example.com"))
        assertFalse("a relative URI has no scheme", rule.admits(null, "com.example.provider"))
    }

    @Test
    fun knitsOwnProvidersAreRefused() {
        assertFalse(rule.admits("content", "app.getknit.knit.fileprovider"))
        assertFalse(rule.admits("content", "app.getknit.knit.androidx-startup"))
        assertFalse("a case variant", rule.admits("content", "App.GetKnit.Knit.FileProvider"))
    }

    @Test
    fun aUserIdPrefixDoesNotHideKnitsOwnProvider() {
        // ContentResolver resolves content://0@app.getknit.knit.fileprovider/… past the last '@', to our provider.
        assertFalse(rule.admits("content", "0@app.getknit.knit.fileprovider"))
        assertFalse(rule.admits("content", "10@app.getknit.knit.fileprovider"))
        assertFalse(rule.admits("content", "x@0@app.getknit.knit.fileprovider"))
    }

    @Test
    fun aContentUriWithNoAuthorityIsRefused() {
        // content:///x and content://0@/x name no provider at all.
        assertFalse(rule.admits("content", null))
        assertFalse(rule.admits("content", ""))
        assertFalse(rule.admits("content", "0@"))
    }

    @Test
    fun someoneElsesProviderIsAdmitted() {
        // content://com.android.providers.media.documents/document/image%3A1, a Files pick or share.
        assertTrue(rule.admits("content", "com.android.providers.media.documents"))
        // content://media/picker/0/com.android.providers.media.photopicker/media/1000, the photo picker.
        assertTrue(rule.admits("content", "media"))
        // A third-party app's FileProvider, the common share.
        assertTrue(rule.admits("content", "com.example.app.fileprovider"))
        // Another app's provider addressed with a user id still reaches that app, not Knit.
        assertTrue(rule.admits("content", "0@com.example.app.fileprovider"))
        // An authority that only starts with ours is someone else's.
        assertTrue(rule.admits("content", "app.getknit.knit.fileprovider.evil"))
    }
}
