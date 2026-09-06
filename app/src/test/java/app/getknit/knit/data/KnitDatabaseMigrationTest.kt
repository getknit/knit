package app.getknit.knit.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration-testing harness. **v1 is the frozen launch baseline:** there is no destructive fallback, and from
 * v1 forward every schema bump ships a tested [KnitMigrations] entry validated here — one per bump, plus the
 * current-schema smoke test that exercises the schema-export pipeline end-to-end: `createDatabase(version)`
 * rebuilds the DB from the checked-in `app/schemas/app.getknit.knit.data.KnitDatabase/<version>.json`, proving
 * `exportSchema`, the Room Gradle plugin's `schemaDirectory` export, the unit-test asset wiring (Robolectric
 * serves `sourceSets["test"]` assets), and the `MigrationTestHelper` harness all line up. The version is read from the
 * `@Database` annotation, so this always targets the current schema and fails loudly if its exported JSON is
 * missing.
 *
 * It uses the driver-based [MigrationTestHelper] constructor with [AndroidSQLiteDriver] — the connection API
 * (`createDatabase`/`runMigrationsAndValidate` returning a `SQLiteConnection`) requires a `SQLiteDriver`, and
 * the framework driver runs on Robolectric's shadowed SQLite (the same engine the DAO tests use;
 * `BundledSQLiteDriver` can't load its Android native lib on the host JVM). When the first post-v1 schema
 * change lands, add a [KnitMigrations] entry and fill in the template below — `runMigrationsAndValidate` then
 * validates both the migrated schema and the carried data.
 */
@RunWith(AndroidJUnit4::class)
class KnitDatabaseMigrationTest {
    private val dbFile = File.createTempFile("knit-migration", ".db").apply { delete() } // path must be free

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = dbFile,
            driver = AndroidSQLiteDriver(),
            databaseClass = KnitDatabase::class,
        )

    @Test
    fun `the current schema (v10) creates and opens from the exported JSON`() =
        runTest {
            val version = 10 // KnitDatabase @Database(version = 10) — bump alongside the DB (its retention is CLASS,
            // so the version can't be read reflectively). A missing schemas/<db>/<version>.json fails here.
            helper.createDatabase(version).close()
        }

    @Test
    fun `migrate 1 to 2 preserves existing rows and adds the ratchet and group-ratchet schemas`() =
        runTest {
            // Seed a v1 database with the rows a real device would carry into the upgrade: a pinned peer and
            // a message. runMigrationsAndValidate then applies MIGRATION_1_2 and validates the result against
            // the exported v2 schema JSON (so the hand-written SQL can't drift from what Room generates).
            helper.createDatabase(1).use { c ->
                c.execSQL(
                    "INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','around',1,7)",
                )
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, mentions, " +
                        "replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','n1','c1','hello',1,1,'[]',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(2, listOf(KnitMigrations.MIGRATION_1_2)).use { c ->
                c.prepare("SELECT name, verified FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("Ann", s.getText(0))
                    assertEquals(1L, s.getLong(1))
                }
                c.prepare("SELECT body FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("hello", s.getText(0))
                }
                // The new prekey columns exist and default to null for a pre-upgrade peer.
                c.prepare("SELECT prekeyId, prekeyPub FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertTrue(s.isNull(0))
                    assertTrue(s.isNull(1))
                }
                // The ratchet + group-ratchet tables are present and empty (one never-released bump holds both).
                for (table in listOf(
                    "ratchet_sessions",
                    "ratchet_skipped_keys",
                    "group_send_chains",
                    "group_recv_chains",
                    "group_skipped_keys",
                    "group_key_sends",
                )) {
                    c.prepare("SELECT COUNT(*) FROM $table").use { s ->
                        assertTrue(s.step())
                        assertEquals(0L, s.getLong(0))
                    }
                }
            }
        }

    @Test
    fun `migrate 2 to 3 preserves existing rows and adds the group-roots schema`() =
        runTest {
            // Seed a v2 database carrying group-ratchet state, since group roots ride beside it and a real
            // upgrade happens on a device that already has groups.
            helper.createDatabase(2).use { c ->
                c.execSQL(
                    "INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','around',1,7)",
                )
                c.execSQL(
                    "INSERT INTO group_key_sends (groupId, memberId, sentEpoch, sentAt, ackedEpoch, ackedAt) " +
                        "VALUES ('g-1','n1',3,10,3,11)",
                )
            }
            helper.runMigrationsAndValidate(3, listOf(KnitMigrations.MIGRATION_2_3)).use { c ->
                c.prepare("SELECT sentEpoch FROM group_key_sends WHERE groupId = 'g-1' AND memberId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals(3L, s.getLong(0))
                }
                c.prepare("SELECT COUNT(*) FROM group_roots").use { s ->
                    assertTrue(s.step())
                    assertEquals(0L, s.getLong(0))
                }
                // The nullable root/prevRoot columns are what let a row exist purely to hold the mint-grace
                // stamp, so pin that a partially-populated row is actually insertable.
                c.execSQL(
                    "INSERT INTO group_roots (groupId, root, version, minter, prevRoot, prevVersion, prevExpiresAt, " +
                        "firstEligibleAt, remintDueAt) VALUES ('g-1', NULL, 0, '', NULL, 0, 0, 42, 0)",
                )
                c.prepare("SELECT root, firstEligibleAt FROM group_roots WHERE groupId = 'g-1'").use { s ->
                    assertTrue(s.step())
                    assertTrue(s.isNull(0))
                    assertEquals(42L, s.getLong(1))
                }
            }
        }

    @Test
    fun `migrate 3 to 4 keeps messages and leaves their delivery plane unknown`() =
        runTest {
            // The upgrade case that matters: a message already ticked ✓✓ on an older build has no record of
            // which plane acked it, so it reads as DeliveryPlane.Unknown (code 0) — not a globe, not a lie.
            helper.createDatabase(3).use { c ->
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, mentions, " +
                        "replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','me','bob','hello',1,1,'[]',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(4, listOf(KnitMigrations.MIGRATION_3_4)).use { c ->
                c.prepare("SELECT body, received, receivedVia FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("hello", s.getText(0))
                    assertEquals(1L, s.getLong(1))
                    assertEquals(0L, s.getLong(2))
                }
            }
        }

    @Test
    fun `migrate 4 to 5 keeps messages and leaves their voice columns null`() =
        runTest {
            // Voice-note duration and waveform are derived locally from the audio, never carried on the wire, so
            // there is nothing to backfill: an existing row has no voice attachment and both columns read null.
            // The row seeded here carries an image attachment precisely to pin that — an attachment alone does
            // not make a message a voice note, and the migration must not invent metadata for one.
            helper.createDatabase(4).use { c ->
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                        "mentions, attachmentHash, attachmentMime, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','me','bob','hello',1,1,0,'[]','abcd','image/jpeg',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(5, listOf(KnitMigrations.MIGRATION_4_5)).use { c ->
                c.prepare("SELECT body, attachmentMime, voiceDurationMs, voicePeaks FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("hello", s.getText(0))
                    assertEquals("image/jpeg", s.getText(1))
                    assertTrue(s.isNull(2))
                    assertTrue(s.isNull(3))
                }
            }
        }

    @Test
    fun `migrate 5 to 6 adds an empty message_receipts table and keeps an already-ticked message`() =
        runTest {
            // There is nothing to backfill and backfilling would be a lie: an already-received message was
            // acked before this device recorded ackers, so we know somebody got it and cannot say who. The
            // message-details screen reads exactly that — ticked with no rows means "predates the table", and
            // it shows no roster rather than accusing every member of missing it.
            helper.createDatabase(5).use { c ->
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                        "mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','me','g-1','hello',1,1,1,'[]',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(6, listOf(KnitMigrations.MIGRATION_5_6)).use { c ->
                c.prepare("SELECT body, received FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("hello", s.getText(0))
                    assertEquals(1L, s.getLong(1))
                }
                c.prepare("SELECT COUNT(*) FROM message_receipts").use { s ->
                    assertTrue(s.step())
                    assertEquals(0L, s.getLong(0))
                }
                // The table is usable straight away, and its composite key absorbs a duplicate receipt.
                c.execSQL("INSERT INTO message_receipts (messageId, ackerNodeId, notedAt, via) VALUES ('m1','sam',9,1)")
                c.execSQL("INSERT OR IGNORE INTO message_receipts (messageId, ackerNodeId, notedAt, via) VALUES ('m1','sam',99,2)")
                c.prepare("SELECT notedAt, via FROM message_receipts WHERE messageId = 'm1' AND ackerNodeId = 'sam'").use { s ->
                    assertTrue(s.step())
                    assertEquals(9L, s.getLong(0))
                    assertEquals(1L, s.getLong(1))
                }
            }
        }

    @Test
    fun `migrate 6 to 7 keeps messages and leaves their arrival time null`() =
        runTest {
            // Arrival time is an observation, and we never made it for a row already on disk: the frame that
            // carried it was persisted before the column existed, and sentAt is the *author's* clock, so there
            // is nothing here to derive it from. Null is the honest value, and the details screen renders the
            // absence rather than a zero. Two rows to pin that it stays null on both directions — one we
            // received and one we sent, whose arrival time is meaningless by construction.
            helper.createDatabase(6).use { c ->
                c.execSQL(
                    "INSERT INTO messages (id, senderId, recipientId, conversationId, body, sentAt, received, " +
                        "receivedVia, mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','bob','me','bob','hello',1,0,1,'[]',0,0,0,0)",
                )
                c.execSQL(
                    "INSERT INTO messages (id, senderId, recipientId, conversationId, body, sentAt, received, " +
                        "receivedVia, mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m2','me','bob','bob','hi back',2,1,1,'[]',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(7, listOf(KnitMigrations.MIGRATION_6_7)).use { c ->
                c.prepare("SELECT id, body, arrivedAt FROM messages ORDER BY sentAt ASC").use { s ->
                    assertTrue(s.step())
                    assertEquals("m1", s.getText(0))
                    assertEquals("hello", s.getText(1))
                    assertTrue(s.isNull(2))
                    assertTrue(s.step())
                    assertEquals("m2", s.getText(0))
                    assertEquals("hi back", s.getText(1))
                    assertTrue(s.isNull(2))
                }
                // The column is writable straight away, so the next inbound message can be stamped.
                c.execSQL("UPDATE messages SET arrivedAt = 42 WHERE id = 'm1'")
                c.prepare("SELECT arrivedAt FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals(42L, s.getLong(0))
                }
            }
        }

    @Test
    fun `migrate 7 to 8 keeps messages and leaves their file columns null`() =
        runTest {
            // Every attachment that predates v8 is an image or a voice note, and both describe themselves —
            // so null is not a gap here, it is the correct answer, and it is what selects the image bubble
            // for those rows. Two rows to pin it: one plain message and one with an image attachment.
            helper.createDatabase(7).use { c ->
                c.execSQL(
                    "INSERT INTO messages (id, senderId, recipientId, conversationId, body, sentAt, received, " +
                        "receivedVia, mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','bob','me','bob','hello',1,0,1,'[]',0,0,0,0)",
                )
                c.execSQL(
                    "INSERT INTO messages (id, senderId, recipientId, conversationId, body, sentAt, received, " +
                        "receivedVia, mentions, attachmentHash, attachmentMime, replyToHasAttachment, moderation, " +
                        "pendingKey, kind) " +
                        "VALUES ('m2','bob','me','bob','',2,0,1,'[]','abc','image/jpeg',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(8, listOf(KnitMigrations.MIGRATION_7_8)).use { c ->
                c.prepare("SELECT id, attachmentName, attachmentSize FROM messages ORDER BY sentAt ASC").use { s ->
                    assertTrue(s.step())
                    assertEquals("m1", s.getText(0))
                    assertTrue(s.isNull(1))
                    assertTrue(s.isNull(2))
                    assertTrue(s.step())
                    assertEquals("m2", s.getText(0))
                    assertTrue(s.isNull(1))
                    assertTrue(s.isNull(2))
                }
                c.execSQL("UPDATE messages SET attachmentName = 'report.pdf', attachmentSize = 1400 WHERE id = 'm1'")
                c.prepare("SELECT attachmentName, attachmentSize FROM messages WHERE id = 'm1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("report.pdf", s.getText(0))
                    assertEquals(1400L, s.getLong(1))
                }
            }
        }

    @Test
    fun `migrate 8 to 9 keeps peers and reads their open-to-chat flag as off`() =
        runTest {
            // Nobody has asserted the flag on a pre-v9 row, and the wire elides it while off, so 0 is the
            // correct value for every existing peer — no backfill, and the next profile frame sets it.
            helper.createDatabase(8).use { c ->
                c.execSQL("INSERT INTO peers (nodeId, name, status, verified, updatedAt) VALUES ('n1','Ann','around',1,7)")
            }
            helper.runMigrationsAndValidate(9, listOf(KnitMigrations.MIGRATION_8_9)).use { c ->
                c.prepare("SELECT name, openToChat FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("Ann", s.getText(0))
                    assertEquals(0L, s.getLong(1))
                }
                c.execSQL("UPDATE peers SET openToChat = 1 WHERE nodeId = 'n1'")
                c.prepare("SELECT openToChat FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals(1L, s.getLong(0))
                }
            }
        }

    @Test
    @Suppress("LongMethod") // one case per bump, and this bump carries four things — splitting re-runs the same migration
    fun `migrate 9 to 10 adds the bridge's attribution columns empty and re-indexes the thread`() =
        runTest {
            // One bump carrying four things, so this case checks all four against rows a real device would
            // hold at v9: the six bridged-post columns, the board claim and its match, the board key and its
            // verdict, and the index swap. Empty is the one honest value for every new column — no message
            // written before v10 can be a bridged post and no profile had claimed a board or carried a key,
            // so there is nothing to backfill and no ambiguity about what a null means here.
            helper.createDatabase(9).use { c ->
                c.execSQL("INSERT INTO peers (nodeId, name, status, verified, updatedAt, openToChat) VALUES ('n1','Ann','around',1,7,0)")
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                        "mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m1','n1','m-public','hello',1,1,0,'[]',0,0,0,0)",
                )
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                        "mentions, replyToHasAttachment, moderation, pendingKey, kind) " +
                        "VALUES ('m2','n2','m-public','later',9,1,0,'[]',0,0,0,0)",
                )
            }
            helper.runMigrationsAndValidate(10, listOf(KnitMigrations.MIGRATION_9_10)).use { c ->
                c.prepare("SELECT name, loraNode, loraKey FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("Ann", s.getText(0))
                    assertTrue("nobody had claimed a board", s.isNull(1))
                    assertTrue("and no profile had carried a key", s.isNull(2))
                }
                c
                    .prepare(
                        "SELECT body, originNode, originName, originViaMqtt, originPeerId, originSigned " +
                            "FROM messages WHERE id = 'm1'",
                    ).use { s ->
                        assertTrue(s.step())
                        assertEquals("hello", s.getText(0))
                        assertTrue("an existing message is not a bridged post", s.isNull(1))
                        assertTrue(s.isNull(2))
                        assertEquals("the flag defaults off, never null", 0L, s.getLong(3))
                        assertTrue("and no post had been matched", s.isNull(4))
                        assertEquals("or checked", 0L, s.getLong(5))
                    }

                // The re-index moves no rows, and this is the half runMigrationsAndValidate cannot see: that a
                // real device's thread comes through the rebuild intact and still reads newest-first.
                c.prepare("SELECT id FROM messages WHERE conversationId = 'm-public' ORDER BY sentAt DESC, id DESC").use { s ->
                    assertTrue(s.step())
                    assertEquals("the newest message still leads the thread", "m2", s.getText(0))
                    assertTrue(s.step())
                    assertEquals("and nothing was lost to the index rebuild", "m1", s.getText(0))
                }

                val indices = mutableListOf<String>()
                c.prepare("PRAGMA index_list(messages)").use { s ->
                    while (s.step()) indices += s.getText(1)
                }
                assertTrue("the thread-ordering index: $indices", "index_messages_conversationId_sentAt_id" in indices)
                assertTrue("the sender-covering index: $indices", "index_messages_conversationId_kind_senderId" in indices)
                assertFalse("the single-column index it replaces is gone: $indices", "index_messages_conversationId" in indices)

                // Column order, not just existence: (conversationId, sentAt, id) is what lets an equality on
                // the thread leave `sentAt` as the scanned suffix, so the window needs no sort. Reordered, the
                // index would still exist and every other test would still pass — slowly.
                val columns = mutableListOf<String>()
                c.prepare("PRAGMA index_info(index_messages_conversationId_sentAt_id)").use { s ->
                    while (s.step()) columns += s.getText(2)
                }
                assertEquals(listOf("conversationId", "sentAt", "id"), columns)

                // And the new columns take what the bridge writes into them: a fully attributed heard post,
                // resolved to a contact and signature-checked.
                c.execSQL(
                    "INSERT INTO messages (id, senderId, conversationId, body, sentAt, received, receivedVia, " +
                        "mentions, replyToHasAttachment, moderation, pendingKey, kind, " +
                        "originNode, originName, originChannel, originHops, originSnrDeci, originViaMqtt, " +
                        "originPeerId, originSigned) " +
                        "VALUES ('m3','gw','m-public','hi',2,0,5,'[]',0,0,0,0, 305441741,'Bob','LongFast',2,-73,1, 'n1',2)",
                )
                c.execSQL(
                    "UPDATE peers SET loraNode = 3735928559, loraKey = 'oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=' WHERE nodeId = 'n1'",
                )
                c
                    .prepare(
                        "SELECT originNode, originName, originChannel, originHops, originSnrDeci, originViaMqtt, " +
                            "originPeerId, originSigned FROM messages WHERE id = 'm3'",
                    ).use { s ->
                        assertTrue(s.step())
                        assertEquals(305441741L, s.getLong(0))
                        assertEquals("Bob", s.getText(1))
                        assertEquals("LongFast", s.getText(2))
                        assertEquals(2L, s.getLong(3))
                        assertEquals(-73L, s.getLong(4))
                        assertEquals(1L, s.getLong(5))
                        assertEquals("n1", s.getText(6))
                        assertEquals(2L, s.getLong(7))
                    }
                c.prepare("SELECT loraNode, loraKey FROM peers WHERE nodeId = 'n1'").use { s ->
                    assertTrue(s.step())
                    assertEquals("a full 32-bit node number fits", 3735928559L, s.getLong(0))
                    assertEquals("oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=", s.getText(1))
                }
            }
        }
}
