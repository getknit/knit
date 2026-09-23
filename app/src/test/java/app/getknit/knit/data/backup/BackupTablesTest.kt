package app.getknit.knit.data.backup

import app.getknit.knit.data.KnitDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins [BackupTables] to the exported Room schema: every table is carried, transient, device-local or derived, and no
 * name is listed twice or invented. A new entity fails here until someone decides what a backup does
 * with it — the whole point of listing them by hand.
 */
class BackupTablesTest {
    @Test
    fun everyTableInTheCurrentSchemaIsClassifiedExactlyOnce() {
        val schema = File("schemas/app.getknit.knit.data.KnitDatabase/${KnitDatabase.SCHEMA_VERSION}.json")
        assertTrue("schema export missing at ${schema.absolutePath}", schema.exists())
        val entities =
            Json
                .parseToJsonElement(schema.readText())
                .jsonObject
                .getValue("database")
                .jsonObject
                .getValue("entities")
                .jsonArray
        val inSchema =
            entities
                .map {
                    it.jsonObject
                        .getValue("tableName")
                        .jsonPrimitive.content
                }.toSet()
        val listed = BackupTables.CARRIED + BackupTables.TRANSIENT + BackupTables.DEVICE_LOCAL + BackupTables.DERIVED
        assertEquals("a table is listed twice", listed.size, listed.toSet().size)
        assertEquals(inSchema, listed.toSet())
    }
}
