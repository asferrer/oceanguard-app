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

/**
 * Runs the real [MIGRATION_13_14] over a real SQLite built from the exported v13 schema and checks
 * the outcome against the exported v14 schema.
 */
class Migration13To14Test {

    private lateinit var sqlite: Connection

    @Before fun openDatabase() {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun closeDatabase() {
        sqlite.close()
    }

    private fun schema(version: Int): JsonObject {
        val file = File(SCHEMA_DIR, "$version.json")
        return JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("database")
    }

    private fun entity(schema: JsonObject, table: String): JsonObject =
        schema.getAsJsonArray("entities").map { it.asJsonObject }
            .first { it.get("tableName").asString == table }

    private fun exec(sql: String) = sqlite.createStatement().use { it.execute(sql) }

    private fun createFromSchema(schema: JsonObject) {
        schema.getAsJsonArray("entities").map { it.asJsonObject }.forEach { e ->
            val table = e.get("tableName").asString
            exec(e.get("createSql").asString.replace("\${TABLE_NAME}", table))
        }
    }

    private fun migrate() {
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer { exec(it.getArgument(0)); null }.`when`(db).execSQL(anyString())
        MIGRATION_13_14.migrate(db)
    }

    private fun assertMatchesExportedSchema(table: String) {
        val expected = entity(schema(TO), table).getAsJsonArray("fields").map { it.asJsonObject }
            .associate {
                it.get("columnName").asString to
                    (it.get("affinity").asString to (it.get("notNull")?.asBoolean ?: false))
            }
        val actual = linkedMapOf<String, Pair<String, Boolean>>()
        sqlite.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) actual[rs.getString("name")] = rs.getString("type") to (rs.getInt("notnull") == 1)
            }
        }
        assertEquals(expected, actual)
    }

    @Test fun migrationDeclaresTheVersions() {
        assertEquals(FROM, MIGRATION_13_14.startVersion)
        assertEquals(TO, MIGRATION_13_14.endVersion)
    }

    @Test fun exportedSchemasChanged() {
        assertEquals(TO, schema(TO).get("version").asInt)
        assertNotEquals(schema(FROM).get("identityHash").asString, schema(TO).get("identityHash").asString)
    }

    @Test fun migratedTableMatchesTheExportedV14Schema() {
        createFromSchema(schema(FROM))
        migrate()
        assertMatchesExportedSchema("generated_reports")
    }

    @Test fun oldReportsKeepTheirTextAndHaveNoFigures() {
        createFromSchema(schema(FROM))
        exec(
            """INSERT INTO generated_reports (id, text, language, sessionCount, usedAi, timestamp, sessionIds)
               VALUES (3, 'informe', 'es', 15, 1, 1700000000000, '1,2')""",
        )
        migrate()
        sqlite.createStatement().use { st ->
            st.executeQuery("SELECT text, sessionCount, figPhotos, figObjects, figHealth FROM generated_reports WHERE id = 3")
                .use { rs ->
                    assertTrue(rs.next())
                    assertEquals("informe", rs.getString(1))
                    assertEquals(15, rs.getInt(2))
                    assertNull(rs.getString(3))
                    assertNull(rs.getString(4))
                    assertNull(rs.getString(5))
                }
        }
    }

    @Test fun newReportsKeepTheirFigures() {
        createFromSchema(schema(FROM))
        migrate()
        exec(
            """INSERT INTO generated_reports (text, language, sessionCount, usedAi, timestamp, figPhotos, figObjects, figHealth)
               VALUES ('t', 'es', 15, 1, 1, 15, 23, 54)""",
        )
        sqlite.createStatement().use { st ->
            st.executeQuery("SELECT figPhotos, figObjects, figHealth FROM generated_reports").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf(15, 23, 54), listOf(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
            }
        }
    }

    private companion object {
        const val FROM = 13
        const val TO = 14
        const val SCHEMA_DIR = "schemas/com.oceanguard.ai.data.OceanGuardDatabase"
    }
}
