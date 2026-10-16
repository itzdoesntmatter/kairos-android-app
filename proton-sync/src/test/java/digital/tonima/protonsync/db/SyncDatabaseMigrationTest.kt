package digital.tonima.protonsync.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration strategy's executed proof (storage.md §3, SyncDatabase docs): v1 → v2 adds
 * the nullable `lastError` column to `calendar_sync_state` data-preservingly. Uses the KSP
 * exported schema fixtures (proton-sync/schemas/, fed to the test assets by the module's
 * build script) via MigrationTestHelper under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class SyncDatabaseMigrationTest {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SyncDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    private val testDb = "migration-test.db"

    @Test
    fun `v1 to v2 preserves rows and adds the lastError column`() {
        // Create the exported v1 schema and insert a representative row.
        helper.createDatabase(testDb, 1).use { db ->
            db.execSQL(
                "INSERT INTO calendar_sync_state " +
                    "(accountName, protonCalendarId, androidCalendarId, displayName, color, " +
                    "lastSyncEpochMs, errorCount) VALUES ('a@proton.me', 'cal-1', 3, 'Personal', 42, 1000, 2)",
            )
        }

        // Run the real migration and validate the schema against the exported v2.
        val migrated = helper.runMigrationsAndValidate(testDb, 2, true, SyncDatabase.MIGRATION_1_2)

        migrated.query("SELECT * FROM calendar_sync_state").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("a@proton.me", cursor.getString(cursor.getColumnIndexOrThrow("accountName")))
            assertEquals("cal-1", cursor.getString(cursor.getColumnIndexOrThrow("protonCalendarId")))
            assertEquals(3, cursor.getLong(cursor.getColumnIndexOrThrow("androidCalendarId")))
            assertEquals("Personal", cursor.getString(cursor.getColumnIndexOrThrow("displayName")))
            assertEquals(42, cursor.getInt(cursor.getColumnIndexOrThrow("color")))
            assertEquals(1000, cursor.getLong(cursor.getColumnIndexOrThrow("lastSyncEpochMs")))
            assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("errorCount")))
            // The new column exists and starts NULL.
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("lastError")))
        }
    }

    @Test
    fun `v2 database opens with the shipped migrations`() {
        val db =
            Room.databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                SyncDatabase::class.java,
                testDb,
            )
                .addMigrations(SyncDatabase.MIGRATION_1_2)
                .build()
        try {
            // Opening at v2 is the shipped configuration; just prove it opens.
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
