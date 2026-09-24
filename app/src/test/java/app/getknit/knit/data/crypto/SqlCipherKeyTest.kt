package app.getknit.knit.data.crypto

import app.getknit.knit.data.crypto.SqlCipherKey.Form
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The decisions [SqlCipherKey.upgrade] makes, on the plain JVM through its [SqlCipherKey.KeyedOpen] seam; the
 * real SQLCipher behaviour behind them (a raw key opens a rekeyed file, the passphrase no longer does) is
 * `SqlCipherRawKeyTest` in androidTest.
 */
class SqlCipherKeyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = ByteArray(DatabaseKey.PASSPHRASE_BYTES) { (it * 7 + 1).toByte() }

    @Test
    fun `the raw form is x-quote, 64 lowercase hex digits of the passphrase, quote`() {
        val raw = SqlCipherKey.raw(passphrase)

        val hex = passphrase.joinToString("") { "%02x".format(it) }
        assertEquals("x'$hex'", raw.toString(Charsets.US_ASCII))
        assertEquals(67, raw.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a passphrase that is not 32 bytes has no raw form`() {
        SqlCipherKey.raw(ByteArray(16))
    }

    @Test
    fun `no file yet opens nothing, so Room creates it under the raw key`() {
        val opener = FakeOpen(opens = emptySet())

        assertEquals(Form.ABSENT, SqlCipherKey.upgrade(File(tmp.root, "absent.db"), passphrase, opener))
        assertTrue(opener.tried.isEmpty())
    }

    @Test
    fun `a file already on the raw key is left alone and the passphrase is never tried`() {
        val opener = FakeOpen(opens = setOf(Key.RAW))

        assertEquals(Form.RAW, SqlCipherKey.upgrade(file(), passphrase, opener))
        assertEquals(listOf(Key.RAW), opener.tried)
        assertTrue(opener.rekeys.isEmpty())
        assertEquals(1, opener.closes)
    }

    @Test
    fun `a file on the passphrase key is rekeyed onto the raw form and closed`() {
        val opener = FakeOpen(opens = setOf(Key.PASSPHRASE))

        assertEquals(Form.REKEYED, SqlCipherKey.upgrade(file(), passphrase, opener))
        assertEquals(listOf(Key.RAW, Key.PASSPHRASE), opener.tried)
        assertEquals(1, opener.rekeys.size)
        assertArrayEquals(SqlCipherKey.raw(passphrase), opener.rekeys.single())
        assertEquals(1, opener.closes)
    }

    @Test
    fun `a file neither key opens is left untouched for Room to refuse`() {
        val f = file()
        val before = f.readBytes()
        val opener = FakeOpen(opens = emptySet())

        assertEquals(Form.UNREADABLE, SqlCipherKey.upgrade(f, passphrase, opener))
        assertTrue(opener.rekeys.isEmpty())
        assertTrue(f.exists())
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun `the caller's passphrase is not wiped by the upgrade`() {
        val copy = passphrase.copyOf()

        SqlCipherKey.upgrade(file(), passphrase, FakeOpen(opens = setOf(Key.PASSPHRASE)))

        assertArrayEquals(copy, passphrase)
    }

    private fun file(): File = File(tmp.root, "knit.db").apply { writeBytes(ByteArray(4096) { 0x5a }) }

    private enum class Key { RAW, PASSPHRASE }

    /** Opens under the key forms in [opens] and throws for the rest, recording what [SqlCipherKey.upgrade] did. */
    private inner class FakeOpen(
        private val opens: Set<Key>,
    ) : SqlCipherKey.KeyedOpen {
        val tried = mutableListOf<Key>()
        val rekeys = mutableListOf<ByteArray>()
        var closes = 0

        override fun open(
            file: File,
            key: ByteArray,
        ): SqlCipherKey.KeyedFile {
            val form = if (key.contentEquals(passphrase)) Key.PASSPHRASE else Key.RAW
            tried += form
            if (form !in opens) throw IllegalStateException("file is not a database")
            return object : SqlCipherKey.KeyedFile {
                // A copy: upgrade wipes its raw-key array once the rekey returns.
                override fun rekey(key: ByteArray) {
                    rekeys += key.copyOf()
                }

                override fun close() {
                    closes++
                }
            }
        }
    }
}
