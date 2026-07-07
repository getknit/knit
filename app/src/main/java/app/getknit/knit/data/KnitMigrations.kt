package app.getknit.knit.data

import androidx.room.migration.Migration

/**
 * The registry of tested schema migrations applied in [KnitDatabase.build].
 *
 * **v1 is the frozen launch baseline.** There is no destructive fallback: from v1 onward every `@Database`
 * version bump MUST add a [Migration] here — a missing one makes Room throw at open time (caught by
 * `KnitDatabaseMigrationTest`) instead of silently wiping user data. So this is the single place production
 * migrations live: keep it in lockstep with `@Database(version = …)` and the checked-in
 * `app/schemas/**/<version>.json`.
 *
 * Empty at launch. The first migration (`MIGRATION_1_2`) lands with the first post-launch schema change; use
 * the driver-based `migrate(SQLiteConnection)` override (matching the `KnitDatabaseMigrationTest` harness), e.g.:
 *
 * ```
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
 *         connection.execSQL("ALTER TABLE peers ADD COLUMN nickname TEXT")
 *     }
 * }
 * ```
 * then add it to [ALL] and fill in the migration test template.
 */
object KnitMigrations {
    /** All migrations, applied by Room in order. Empty until the first post-v1 schema change. */
    val ALL: Array<Migration> = arrayOf()
}
