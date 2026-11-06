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
 * Runs the real [MIGRATION_12_13] over a real SQLite built from the exported v12 schema, so it
 * needs no device, and checks the outcome against the exported v13 schema.
 */
class Migration12To13Test {

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

    private fun seedV12() {
        exec(
            """INSERT INTO detection_sessions
               (id, imageUri, debrisList, totalCount, healthScore, timestamp, imageQuality, processingTimeMs)
               VALUES (7, 'file://a.jpg', '[]', 2, 80, 1700000000000, 'FAIR', 4100)""",
        )
        exec(
            """INSERT INTO generated_reports
               (id, text, language, sessionCount, usedAi, timestamp, sessionIds)
               VALUES (3, 'informe', 'es', 1, 1, 1700000000000, '7')""",
        )
    }

    private fun migrate() {
        val db = mock(SupportSQLiteDatabase::class.java)
        doAnswer { exec(it.getArgument(0)); null }.`when`(db).execSQL(anyString())
        MIGRATION_12_13.migrate(db)
    }

    private fun tableColumns(table: String): Map<String, Pair<String, Boolean>> {
        val out = linkedMapOf<String, Pair<String, Boolean>>()
        sqlite.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    out[rs.getString("name")] = rs.getString("type") to (rs.getInt("notnull") == 1)
                }
            }
        }
        return out
    }

    private fun assertMatchesExportedSchema(table: String) {
        val expected = entity(schema(TO), table).getAsJsonArray("fields").map { it.asJsonObject }
            .associate {
                it.get("columnName").asString to
                    (it.get("affinity").asString to (it.get("notNull")?.asBoolean ?: false))
            }
        assertEquals(expected, tableColumns(table))
    }

    @Test fun migrationDeclaresTheVersions() {
        assertEquals(FROM, MIGRATION_12_13.startVersion)
        assertEquals(TO, MIGRATION_12_13.endVersion)
    }

    @Test fun exportedSchemasChanged() {
        assertEquals(FROM, schema(FROM).get("version").asInt)
        assertEquals(TO, schema(TO).get("version").asInt)
        assertNotEquals(
            schema(FROM).get("identityHash").asString,
            schema(TO).get("identityHash").asString,
        )
    }

    @Test fun migratedTablesMatchTheExportedV13Schema() {
        createFromSchema(schema(FROM))
        migrate()
        assertMatchesExportedSchema("detection_sessions")
        assertMatchesExportedSchema("generated_reports")
    }

    @Test fun onlyTheTwoTracedTablesChange() {
        val before = schema(FROM).getAsJsonArray("entities").map { it.asJsonObject }
        val after = schema(TO).getAsJsonArray("entities").map { it.asJsonObject }
        assertEquals(before.map { it.get("tableName") }, after.map { it.get("tableName") })
        val changed = after.filter { e ->
            val old = before.first { it.get("tableName") == e.get("tableName") }
            old.get("createSql") != e.get("createSql")
        }.map { it.get("tableName").asString }
        assertEquals(listOf("detection_sessions", "generated_reports"), changed.sorted())
    }

    @Test fun existingRowsSurviveAndAdmitNoInventedModel() {
        createFromSchema(schema(FROM))
        seedV12()
        migrate()

        sqlite.createStatement().use { st ->
            st.executeQuery("SELECT imageUri, processingTimeMs, modelName FROM detection_sessions WHERE id = 7")
                .use { rs ->
                    assertTrue(rs.next())
                    assertEquals("file://a.jpg", rs.getString(1))
                    assertEquals(4100L, rs.getLong(2))
                    assertNull(rs.getString(3))
                }
            st.executeQuery("SELECT text, sessionIds, modelName FROM generated_reports WHERE id = 3")
                .use { rs ->
                    assertTrue(rs.next())
                    assertEquals("informe", rs.getString(1))
                    assertEquals("7", rs.getString(2))
                    assertNull(rs.getString(3))
                }
        }
    }

    @Test fun newRowsKeepTheModel() {
        createFromSchema(schema(FROM))
        migrate()
        exec(
            """INSERT INTO generated_reports (text, language, sessionCount, usedAi, timestamp, modelName)
               VALUES ('t', 'es', 1, 1, 1, '${AnalysisModels.GEMMA4_BASE}')""",
        )
        sqlite.createStatement().use { st ->
            st.executeQuery("SELECT modelName FROM generated_reports").use { rs ->
                assertTrue(rs.next())
                assertEquals(AnalysisModels.GEMMA4_BASE, rs.getString(1))
            }
        }
    }

    private companion object {
        const val FROM = 12
        const val TO = 13
        const val SCHEMA_DIR = "schemas/com.oceanguard.ai.data.OceanGuardDatabase"
    }
}
