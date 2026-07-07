package app.getknit.knit.data

import androidx.room.migration.Migration

/**
 * The registry of tested schema migrations applied in [KnitDatabase.build].
 *
 * **v22 is the frozen launch baseline.** Pre-launch schemas (≤ v21) are handled by the *destructive*
 * fallback in the builder (`fallbackToDestructiveMigrationFrom(dropAllTables = true, 1..21)`); from v22
 * onward every `@Database` version bump MUST add a [Migration] here — a missing one makes Room throw at
 * open time (caught by `KnitDatabaseMigrationTest`) instead of silently wiping user data. So this is the
 * single place production migrations live: keep it in lockstep with `@Database(version = …)` and the
 * checked-in `app/schemas/**/<version>.json`.
 *
 * Empty at launch — there is no in-production schema older than v22 yet. The first real migration
 * (`MIGRATION_22_23`) lands with the first post-launch schema change; use the driver-based
 * `migrate(SQLiteConnection)` override (matching the `KnitDatabaseMigrationTest` harness), e.g.:
 *
 * ```
 * val MIGRATION_22_23 = object : Migration(22, 23) {
 *     override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
 *         connection.execSQL("ALTER TABLE peers ADD COLUMN nickname TEXT")
 *     }
 * }
 * ```
 * then add it to [ALL] and fill in the migration test template.
 */
object KnitMigrations {
    /** All migrations, applied by Room in order. Empty until the first post-v22 schema change. */
    val ALL: Array<Migration> = arrayOf()
}
