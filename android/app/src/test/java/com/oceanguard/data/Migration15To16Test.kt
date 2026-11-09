package com.oceanguard.ai.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/** Runs the real [MIGRATION_15_16] over a real SQLite built from the exported v15 schema. */
class Migration15To16Test {

    private lateinit var sqlite: Connection

    @Before fun openDatabase() {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun closeDatabase() {
        sqlite.close()
    }

    private fun schema(version: Int): JsonObject =
        JsonParser.parseString(File(SCHEMA_DIR, "$version.json").readText()).asJsonObject.getAsJsonObject("database")

    private fun exec(sql: String) = sqlite.createStatement().use { it.execute(sql) }

    private fun createFromSchema(schema: JsonObject) {
        schema.getAsJsonArray("entities").map { it.asJsonObject }.forEach { e ->
            exec(e.get("createSql").asString.replace("\${TABLE_NAME}", e.get("tableName").asString))
        }
    }

    private fun migrate() {
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer { exec(it.getArgument(0)); null }.`when`(db).execSQL(anyString())
        MIGRATION_15_16.migrate(db)
    }

    @Test fun migrationDeclaresTheVersions() {
        assertEquals(15, MIGRATION_15_16.startVersion)
        assertEquals(16, MIGRATION_15_16.endVersion)
        assertNotEquals(schema(15).get("identityHash").asString, schema(16).get("identityHash").asString)
    }

    @Test fun queuedItemsKeepTheirDataAndHaveNoOutputs() {
        createFromSchema(schema(15))
        exec(
            """INSERT INTO contribution_queue (id, sessionId, imageUri, annotationsJson, status, retryCount, createdAt)
               VALUES (4, 1, 'file:///a.jpg', '{}', 'PENDING', 0, 1700000000000)""",
        )
        migrate()
        val columns = mutableListOf<String>()
        sqlite.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(contribution_queue)").use { rs -> while (rs.next()) columns += rs.getString("name") }
        }
        assertTrue(columns.toString(), "outputsJson" in columns)
        sqlite.createStatement().use { st ->
            st.executeQuery("SELECT imageUri, outputsJson FROM contribution_queue WHERE id = 4").use { rs ->
                assertTrue(rs.next())
                assertEquals("file:///a.jpg", rs.getString(1))
                assertNull(rs.getString(2))
            }
        }
    }

    private companion object {
        const val SCHEMA_DIR = "schemas/com.oceanguard.ai.data.OceanGuardDatabase"
    }
}
