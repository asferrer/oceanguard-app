package com.oceanguard.ai.inference

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tools, bundle, validator and assembler of the species and combined reports. */
class SpeciesReportTest {

    private fun ms(date: String) = LocalDateTime.parse("${date}T12:00:00").toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun entry(key: String, name: String, en: String, es: String, group: String, iucn: String?) = SpeciesCatalogEntry(
        speciesKey = key, aphiaId = key.hashCode().toLong(), scientificName = name,
        commonNames = mapOf("en" to en, "es" to es),
        spritePath = null, ecoregions = emptyList(), iucnStatus = iucn, group = group,
    )

    private val catalog = SpeciesCatalog(File("unused.json")).apply {
        seedInMemory(
            listOf(
                entry("caretta", "Caretta caretta", "Loggerhead turtle", "Tortuga boba", "turtles", "VU"),
                entry("mola", "Mola mola", "Ocean sunfish", "Pez luna", "bony_fish", "LC"),
                entry("tursiops", "Tursiops truncatus", "Bottlenose dolphin", "Delfín mular", "mammals", "LC"),
                entry("octopus", "Octopus vulgaris", "Common octopus", "Pulpo común", "cephalopods", "LC"),
            ),
        )
    }

    private fun obs(id: Long, name: String, image: String, lat: Double?, lon: Double?, out: Boolean = false, conf: Float = 0.9f) =
        SpeciesObservation(
            id = id, imageUri = image, scientificName = name, cosineScore = conf, confidence = conf,
            idSource = "RAG_CORE", outOfRange = out, timestamp = Date(ms("2026-11-05")),
            location = if (lat != null && lon != null) Location(lat, lon) else null,
        )

    private val observations = listOf(
        obs(1, "Caretta caretta", "content://a", 36.0, -6.0),
        obs(2, "Caretta caretta", "content://b", 36.001, -6.0, out = true),
        obs(3, "Mola mola", "content://a", null, null),
    )

    private fun session(id: Long, count: Int, lat: Double, lon: Double) = DetectionSession(
        id = id, imageUri = "file://$id.jpg",
        debrisList = List(count) { Debris(BoundingBox(0f, 0f, 1f, 1f), DebrisMaterial.PLASTIC, DebrisType.BOTTLE, 0.9f) },
        totalCount = count, healthScore = 60, timestamp = Date(ms("2026-11-06")), imageQuality = ImageQuality.GOOD,
        processingTimeMs = 10, location = Location(lat, lon),
    )

    private val sessions = listOf(session(10, 5, 36.0005, -6.0), session(11, 2, 37.0, -5.0))

    private fun ctx(type: ReportType = ReportType.SPECIES, lang: String = "es", obs: List<SpeciesObservation> = observations) =
        SpeciesReportContext(
            type, obs, if (type.hasDebris) sessions else emptyList(), catalog, lang, ReportAudience.SCIENTIFIC,
            ms("2026-11-01"), ms("2026-11-30"),
        )

    // ---- tools: the data they return

    @Test
    fun `summary tool counts observations photos species and flags`() {
        val s = OceanGuardSpeciesTools(ctx()).getSpeciesSummary()
        assertEquals(3, s["totalObservations"])
        assertEquals(2, s["photos"])
        assertEquals(2, s["distinctSpecies"])
        assertEquals(2, s["taxonomicGroups"])
        assertEquals(1, s["threatenedSpecies"])
        assertEquals(1, s["outOfRangeSpecies"])
        assertEquals(2, s["locatedObservations"])
        assertEquals("2026-11-01", s["periodStart"])
    }

    @Test
    fun `list tool returns exactly the observed species with their counts`() {
        @Suppress("UNCHECKED_CAST")
        val items = OceanGuardSpeciesTools(ctx()).getSpeciesList()["items"] as List<Map<String, Any>>
        assertEquals(listOf("Caretta caretta", "Mola mola"), items.map { it["scientificName"] })
        val caretta = items.first()
        assertEquals("Tortuga boba", caretta["commonName"])
        assertEquals(2, caretta["observations"])
        assertEquals(2, caretta["photos"])
        assertEquals(1, caretta["outOfRangeObservations"])
        assertEquals("VU", caretta["iucn"])
        assertEquals("turtles", caretta["group"])
        assertEquals(90, caretta["meanConfidencePercent"])
    }

    @Test
    fun `threatened and out of range tools only return matching species`() {
        val tools = OceanGuardSpeciesTools(ctx())
        @Suppress("UNCHECKED_CAST")
        val threatened = tools.getThreatenedSpecies()["items"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val out = tools.getOutOfRangeSpecies()["items"] as List<Map<String, Any>>
        assertEquals(listOf("Caretta caretta"), threatened.map { it["scientificName"] })
        assertEquals(listOf("Caretta caretta"), out.map { it["scientificName"] })
        val none = OceanGuardSpeciesTools(ctx(obs = listOf(observations[2])))
        assertEquals(0, none.getThreatenedSpecies()["count"])
        assertEquals(0, none.getOutOfRangeSpecies()["count"])
    }

    @Test
    fun `group and location tools`() {
        val tools = OceanGuardSpeciesTools(ctx())
        @Suppress("UNCHECKED_CAST")
        val groups = tools.getGroupBreakdown()["items"] as List<Map<String, Any>>
        assertEquals(listOf("turtles", "bony_fish"), groups.map { it["group"] })
        assertEquals(2, tools.getSpeciesLocations()["count"])
    }

    @Test
    fun `combined tools return the debris facts and the cooccurrence as facts`() {
        val ctx = ctx(ReportType.COMBINED)
        val tools = OceanGuardCombinedTools(ctx)
        assertEquals(7, tools.getDebrisSummary()["totalDebrisItems"])
        assertEquals(3, tools.getSpeciesSummary()["totalObservations"])
        @Suppress("UNCHECKED_CAST")
        val items = tools.getDebrisSpeciesCooccurrence()["items"] as List<Map<String, Any>>
        assertEquals(listOf(10L, 11L), items.map { it["sessionId"] })
        @Suppress("UNCHECKED_CAST")
        val nearFirst = items.first()["speciesNearby"] as List<Map<String, Any>>
        assertEquals(listOf("Caretta caretta"), nearFirst.map { it["scientificName"] })
        assertEquals(2, nearFirst.first()["observations"])
        assertTrue((items.last()["speciesNearby"] as List<*>).isEmpty())
    }

    // ---- bundle

    @Test
    fun `bundle renders localised tables and states none for empty sections`() {
        val bundle = SpeciesReportBundle.build(ctx(obs = listOf(observations[2])))
        assertTrue(bundle.tables.getValue("sp_list").contains("Nombre científico"))
        assertEquals("- ninguna", bundle.tables.getValue("sp_threatened"))
        assertTrue(bundle.tables.getValue("sp_totals").contains("Observaciones de especies: **1**"))
        val en = SpeciesReportBundle.build(ctx(lang = "en"))
        assertTrue(en.tables.getValue("sp_list").contains("Scientific name"))
    }

    @Test
    fun `every word of the report text is translated into the six languages`() {
        SpeciesReportText.allWords().forEach { (key, tr) ->
            assertEquals(key, SpeciesReportText.LANGUAGES.toSet(), tr.keys)
            assertTrue(key, tr.values.none { it.isBlank() })
        }
    }

    // ---- validator

    private val bundle = SpeciesReportBundle.build(ctx())
    private val honest = "## Resumen\n\nLa tortuga boba es la especie dominante, vista en varias fotos.\n\n" +
        "## Especies Observadas\n\n[TABLE: sp_list]"

    @Test
    fun `a text that stays within the data passes`() {
        val text = SpeciesReportAssembler.assemble(honest, ctx(), bundle)
        val result = SpeciesReportValidator.validate(text, ctx(), bundle)
        assertEquals(result.toSummaryString(), 0, result.failCount)
        assertTrue(text.contains("Caretta caretta"))
    }

    @Test
    fun `a text that invents a species is rejected`() {
        val invented = "El delfín mular también estuvo presente en la zona."
        val result = SpeciesReportValidator.validate(honest + "\n\n" + invented, ctx(), bundle)
        assertTrue(result.failCount > 0)
        assertTrue(result.checks.any { it.field == "species_closed_world" && it.status == CheckStatus.FAIL })
        assertEquals(listOf("delfín mular"), SpeciesReportValidator.inventedSpecies(invented, ctx()))
    }

    @Test
    fun `an italic binomial that was not observed is rejected`() {
        assertEquals(
            listOf("sphyrna lewini"),
            SpeciesReportValidator.inventedSpecies("Se vio *Sphyrna lewini* cerca.", ctx()),
        )
        assertTrue(SpeciesReportValidator.inventedSpecies("Se vio *Caretta caretta* cerca.", ctx()).isEmpty())
    }

    @Test
    fun `a text that invents a figure is rejected`() {
        val result = SpeciesReportValidator.validate(honest + "\n\nSe contaron 17 ejemplares.", ctx(), bundle)
        assertTrue(result.checks.any { it.field == "numbers_from_tools" && it.status == CheckStatus.FAIL })
        assertEquals(listOf("17"), SpeciesReportValidator.unsupportedNumbers("Se contaron 17 ejemplares.", bundle))
        // 2 is a figure of the data (two observations of the loggerhead), so repeating it is allowed.
        assertTrue(SpeciesReportValidator.unsupportedNumbers("Hubo 2 observaciones.", bundle).isEmpty())
    }

    @Test
    fun `the guard drops only the unsupported sentences`() {
        val raw = "La tortuga boba domina. El pulpo común vive aquí. Se contaron 17 ejemplares. Fue una buena jornada."
        val cleaned = SpeciesReportAssembler.assemble(raw, ctx(), bundle)
        assertTrue(cleaned.contains("La tortuga boba domina."))
        assertTrue(cleaned.contains("Fue una buena jornada."))
        assertFalse(cleaned.contains("pulpo"))
        assertFalse(cleaned.contains("17"))
        assertTrue(SpeciesReportValidator.validate(cleaned, ctx(), bundle).failCount == 0)
    }

    @Test
    fun `a combined report cannot claim a cause`() {
        val c = ctx(ReportType.COMBINED)
        val b = SpeciesReportBundle.build(c)
        val causal = "La tortuga boba aparece debido a los residuos."
        assertTrue(SpeciesReportValidator.validate(causal, c, b).checks.any { it.field == "no_causality" && it.status == CheckStatus.FAIL })
        val plain = "La tortuga boba y los residuos se registraron en los mismos puntos."
        assertTrue(SpeciesReportAssembler.assemble(plain, c, b).contains(plain))
        assertFalse(SpeciesReportAssembler.assemble(causal, c, b).contains("debido"))
        // The same sentence is not a debris-versus-species claim in a species-only report.
        assertTrue(SpeciesReportValidator.validate(causal, ctx(), bundle).checks.single { it.field == "no_causality" }.status == CheckStatus.PASS)
    }

    @Test
    fun `no data fails validation`() {
        val empty = SpeciesReportContext(ReportType.SPECIES, emptyList(), emptyList(), catalog, "es", ReportAudience.CITIZEN)
        assertEquals(0, SpeciesReportValidator.validate("texto", empty, SpeciesReportBundle.build(empty)).overallScore)
    }

    // ---- assembler

    @Test
    fun `the assembler swaps placeholders for the real tables and drops the model's own`() {
        val raw = "## Especies Observadas\n\nTexto.\n\n| Especie | n |\n|---|---|\n| Falsa | 99 |\n\n[TABLE: sp_list]\n[TABLE: sp_list]"
        val out = SpeciesReportAssembler.assemble(raw, ctx(), bundle)
        assertEquals(1, Regex("Confianza media").findAll(out).count())
        assertFalse(out.contains("Falsa"))
        assertFalse(out.contains("[TABLE"))
    }

    @Test
    fun `tables the model forgot are appended and the template has every section`() {
        val out = SpeciesReportAssembler.assemble("## Resumen\n\nTexto breve.", ctx(), bundle)
        assertTrue(out.contains("Nombre científico"))
        val template = SpeciesReportAssembler.template(ctx(), bundle)
        assertTrue(template.contains("## Especies Observadas"))
        assertTrue(template.contains("Caretta caretta"))
        assertEquals(0, SpeciesReportValidator.validate(template, ctx(), bundle).failCount)
    }

    // ---- tool names and R8

    private fun toolNames(type: Class<*>): Set<String> =
        type.declaredMethods.filter { it.isAnnotationPresent(Tool::class.java) }.map { ToolNames.canonical(it.name) }.toSet()

    @Test
    fun `every required tool exists as a Tool method`() {
        assertTrue(toolNames(OceanGuardSpeciesTools::class.java).containsAll(ToolNames.canonical(SpeciesReportGenerator.requiredTools(ReportType.SPECIES))))
        assertTrue(toolNames(OceanGuardCombinedTools::class.java).containsAll(ToolNames.canonical(SpeciesReportGenerator.requiredTools(ReportType.COMBINED))))
    }

    @Test
    fun `the new tool sets are covered by the R8 keep rule`() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep class * implements com.google.ai.edge.litertlm.ToolSet"))
        assertTrue(rules.contains("@com.google.ai.edge.litertlm.Tool <methods>"))
        for (type in listOf(OceanGuardSpeciesTools::class.java, OceanGuardCombinedTools::class.java)) {
            assertTrue(type.name, ToolSet::class.java.isAssignableFrom(type))
            assertTrue(type.name, toolNames(type).isNotEmpty())
        }
    }

    @Test
    fun `the report type of an old report is debris`() {
        assertEquals(ReportType.DEBRIS, ReportType.fromKey(null))
        assertEquals(ReportType.COMBINED, ReportType.fromKey("COMBINED"))
        assertEquals(ReportType.DEBRIS, ReportType.fromKey("garbage"))
    }
}
