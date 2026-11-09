package com.oceanguard.ai.species

import com.oceanguard.ai.data.species.SpeciesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Catalog v2 (`tools/species/build_catalog_v2.py`): same wrapper as v1 plus the optional `group`,
 * filled `descriptions` and the new header. Also parses the generated file when it exists on this machine.
 */
class SpeciesCatalogV2Test {

    private val v2Json = """
        {
          "version": 2,
          "sources": ["OBIS", "GBIF", "AquaMaps", "WoRMS"],
          "license_note": "WoRMS: CC BY 4.0. Atribución ñ.",
          "species": [
            {
              "speciesKey": "caretta_caretta",
              "aphiaId": 137205,
              "scientificName": "Caretta caretta",
              "commonNames": {"en": "Loggerhead sea turtle", "es": "Tortuga boba"},
              "ecoregions": [],
              "cosmopolitan": true,
              "spritePath": "sprites/caretta_caretta.webp",
              "descriptions": {"en": "Sea turtle (order Testudines).", "es": "Tortuga marina (orden Testudines)."},
              "iucnStatus": "VU",
              "group": "turtles",
              "campoFuturoDesconocido": 1
            },
            {
              "speciesKey": "octopus_vulgaris",
              "aphiaId": 140605,
              "scientificName": "Octopus vulgaris",
              "commonNames": {"en": "Common octopus"},
              "ecoregions": [30, 31],
              "descriptions": {"en": "Cephalopod."},
              "iucnStatus": null
            }
          ]
        }
    """.trimIndent()

    private fun tempCatalog(content: String): File =
        File.createTempFile("species_catalog_v2", ".json").apply {
            writeText(content, Charsets.UTF_8)
            deleteOnExit()
        }

    /** Same selection as SpeciesInfoTabContent: descriptions[lang], else descriptions["en"]. */
    private fun descriptionFor(catalog: SpeciesCatalog, key: String, lang: String): String? {
        val entry = catalog.byKey(key)
        return entry?.descriptions?.get(lang) ?: entry?.descriptions?.get("en")
    }

    @Test
    fun parsesV2WithGroupAndIgnoresUnknownFields() {
        val catalog = SpeciesCatalog(tempCatalog(v2Json))
        catalog.load()
        assertEquals(2, catalog.totalCount())
        assertEquals("turtles", catalog.byKey("caretta_caretta")!!.group)
        assertNull(catalog.byKey("octopus_vulgaris")!!.group)
        assertFalse(catalog.byKey("octopus_vulgaris")!!.cosmopolitan)
    }

    @Test
    fun infoTabUsesDescriptionOfTheLanguageWithEnglishFallback() {
        val catalog = SpeciesCatalog(tempCatalog(v2Json))
        catalog.load()
        assertEquals("Tortuga marina (orden Testudines).", descriptionFor(catalog, "caretta_caretta", "es"))
        assertEquals("Sea turtle (order Testudines).", descriptionFor(catalog, "caretta_caretta", "de"))
        assertEquals("Cephalopod.", descriptionFor(catalog, "octopus_vulgaris", "fr"))
    }

    @Test
    fun generatedCatalogV2ParsesWhenAvailable() {
        val f = File(System.getProperty("species.catalog.v2") ?: "C:/rerun_v9/runs/_species_catalog_v2/species_catalog_v2.json")
        assumeTrue(f.exists())
        val catalog = SpeciesCatalog(f)
        catalog.load()
        assertEquals(202, catalog.totalCount())
        val langs = listOf("en", "es", "fr", "de", "it", "pt")
        for (e in catalog.all()) {
            assertNotNull(e.group)
            for (l in langs) {
                assertTrue("${e.speciesKey} name $l", !e.commonNames[l].isNullOrBlank())
                assertTrue("${e.speciesKey} desc $l", !e.descriptions[l].isNullOrBlank())
            }
        }
    }
}
