package app.getknit.knit.ui.addcontact

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Add contact screen's Paste takes the clip's text, never a stream (#284, ADR 2026-11.vdx4). Through
 * `ClipData.Item.coerceToText`, any app's clip naming a `content://` item had Knit read the whole stream as itself:
 * its own field log into the field, or an endless one into memory. 2.8.0's crash (a null context in `coerceToText` on
 * Android 12/13) cannot recur either, since nothing here takes a context.
 */
@RunWith(RobolectricTestRunner::class)
class ClipTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aStreamItemIsNeverReadThoughThePlatformWouldReadIt() {
        // Knit's own provider serving a private text file, the shape #284's paste would reach.
        Robolectric.setupContentProvider(PrivateTextProvider::class.java, "${context.packageName}.fileprovider")
        val item = ClipData.Item(Uri.parse("content://${context.packageName}.fileprovider/fieldlog/knit-fieldlog.txt"))
        assertEquals("the platform's coercion reads it, as Knit", PRIVATE_TEXT, item.coerceToText(context).toString())

        assertNull(clipText(ClipData(ClipDescription("copied", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), item)))
    }

    @Test
    fun aStreamItemDoesNotHideTheTextItemAfterIt() {
        val clip =
            ClipData(
                ClipDescription("copied", arrayOf("image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(Uri.parse("content://media/external/images/media/42")),
            )
        clip.addItem(ClipData.Item("https://getknit.app/c#abc"))

        assertEquals("https://getknit.app/c#abc", clipText(clip))
    }

    @Test
    fun aLinkCopiedAsARawUriIsStillFound() {
        assertEquals("https://getknit.app/c#abc", clipText(ClipData.newRawUri("copied", Uri.parse("https://getknit.app/c#abc"))))
        assertNull(clipText(ClipData.newRawUri("copied", Uri.parse("file:///data/data/app.getknit.knit/files/x"))))
        assertNull(clipText(ClipData.newRawUri("copied", Uri.parse("android.resource://app.getknit.knit/raw/x"))))
    }

    /** Serves [PRIVATE_TEXT] as `text/plain` for any URI, as FileProvider serves a field log. */
    class PrivateTextProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = ClipDescription.MIMETYPE_TEXT_PLAIN

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
        ): AssetFileDescriptor {
            val file = File.createTempFile("private", ".txt", requireNotNull(context).cacheDir).apply { writeText(PRIVATE_TEXT) }
            return AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH,
            )
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val PRIVATE_TEXT = "a private field log line"
    }
}
