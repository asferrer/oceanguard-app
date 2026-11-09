package com.oceanguard.ai.data.contribution

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.inference.species.SpeciesMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionOutputsTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun session(vararg debris: Debris) = DetectionSession(
        id = 3, imageUri = "file:///x.jpg", debrisList = debris.toList(), totalCount = debris.size, healthScore = 70,
        imageQuality = ImageQuality.GOOD, processingTimeMs = 812, modelName = "RT-DETRv2",
    )

    private val bottle = Debris(
        bbox = BoundingBox(0.1f, 0.2f, 0.3f, 0.4f), material = DebrisMaterial.PLASTIC,
        type = DebrisType.BOTTLE, confidence = 0.83f,
    )
    private val bag = Debris(
        bbox = BoundingBox(0.5f, 0.5f, 0.9f, 0.9f), material = DebrisMaterial.PLASTIC,
        type = DebrisType.PLASTIC_DEBRIS, confidence = 0.6f, rawLabel = "plastic_bag", subType = DebrisType.PLASTIC_BAG,
    )

    @Test
    fun detectionOutputsOnlyCarryWhatTheSessionHas() {
        val out = json(ContributionOutputs.forDetection(session(bottle, bag)))
        assertEquals(1, out.get("schema").asInt)
        val det = out.getAsJsonObject("detection")
        assertEquals("RT-DETRv2", det.get("model").asString)
        assertEquals(812, det.get("processing_ms").asLong)
        assertFalse("mode, threshold and edits are not known", det.has("mode") || det.has("threshold") || det.has("user_edited"))
        assertFalse(out.has("species") || out.has("report") || out.has("image"))
        val items = det.getAsJsonArray("items")
        assertEquals(2, items.size())
        val first = items[0].asJsonObject
        assertEquals("BOTTLE", first.get("class").asString)
        assertEquals("detector", first.get("source").asString)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), first.getAsJsonArray("bbox_norm").map { it.asFloat })
        assertFalse(first.has("subtype"))
        val second = items[1].asJsonObject
        assertEquals("gemma", second.get("source").asString)
        assertEquals("PLASTIC_BAG", second.get("subtype").asString)
        assertEquals("plastic_bag", second.get("raw_label").asString)
        assertEquals("box is clamped inside the frame", 0.5f, second.getAsJsonArray("bbox_norm")[2].asFloat, 1e-6f)
    }

    @Test
    fun noLocationOrDeviceDataEverGoesInOutputs() {
        val text = ContributionOutputs.forDetection(session(bottle)).lowercase()
        listOf("lat", "lon", "gps", "location", "device", "model_name_of_phone").forEach {
            assertFalse(it, text.contains("\"$it"))
        }
    }

    @Test
    fun finishAddsTheImageAndPixelBoxes() {
        val queued = ContributionOutputs.forDetection(session(bottle))
        val image = JsonObject().apply { addProperty("width", 4000); addProperty("height", 3000) }
        val out = ContributionOutputs.finish(queued, image)
        assertEquals(4000, out.getAsJsonObject("image").get("width").asInt)
        val px = out.getAsJsonObject("detection").getAsJsonArray("items")[0].asJsonObject
            .getAsJsonArray("bbox_px").map { it.asFloat }
        assertEquals(listOf(400f, 600f, 1200f, 1200f), px)
    }

    @Test
    fun finishSurvivesMissingOrBrokenQueuedOutputs() {
        val image = JsonObject().apply { addProperty("width", 10); addProperty("height", 10) }
        assertEquals(1, ContributionOutputs.finish(null, image).get("schema").asInt)
        assertEquals(1, ContributionOutputs.finish("not json", image).get("schema").asInt)
        assertTrue(ContributionOutputs.finish("{}", image).has("image"))
    }

    @Test
    fun speciesOutputsFromAnIdentification() {
        val result = IdentificationResult(
            speciesKey = "126436", scientificName = "Thunnus thynnus", idSource = SpeciesIdSource.RAG_CORE,
            cosineScore = 0.8f, confidence = 0.91f, vlmDescription = "free text that stays on the phone",
            bbox = BoundingBox(10.4f, 20.6f, 300f, 200f), geoMatchLevel = GeoMatchLevel.PROVINCE.name,
            candidates = listOf(SpeciesMatch("126436", "Thunnus thynnus", 0.8f, 0.91f, GeoMatchLevel.PROVINCE)),
        )
        val out = json(ContributionOutputs.forSpecies(listOf(ContributionOutputs.speciesOf(result, packVersion = 3))))
        val s = out.getAsJsonArray("species")[0].asJsonObject
        assertEquals("126436", s.get("species_key").asString)
        assertEquals("Thunnus thynnus", s.get("scientific_name").asString)
        assertEquals("RAG_CORE", s.get("id_source").asString)
        assertEquals(0.91f, s.get("confidence").asFloat, 1e-6f)
        assertEquals(3, s.get("pack_version").asInt)
        assertEquals("PROVINCE", s.get("geo_match_level").asString)
        assertEquals(listOf(10, 21, 300, 200), s.getAsJsonArray("bbox_px").map { it.asInt })
        assertEquals("126436", s.getAsJsonArray("candidates")[0].asJsonObject.get("species_key").asString)
        assertFalse("the free-text description is not sent", s.has("vlm_description") || s.has("description"))
        assertFalse(out.has("detection"))
    }

    @Test
    fun unidentifiedAndUncataloguedAnswersAreHonest() {
        val unidentified = IdentificationResult(
            speciesKey = IdentificationResult.UNIDENTIFIED_KEY, scientificName = "", idSource = SpeciesIdSource.UNIDENTIFIED,
            cosineScore = 0.1f, confidence = 0.05f, uncatalogued = true,
        )
        val s = json(ContributionOutputs.forSpecies(listOf(ContributionOutputs.speciesOf(unidentified, null))))
            .getAsJsonArray("species")[0].asJsonObject
        assertEquals("UNIDENTIFIED", s.get("id_source").asString)
        assertFalse("no key for uncatalogued", s.has("species_key"))
        assertFalse(s.has("pack_version") || s.has("candidates") || s.has("bbox_px"))
    }

    @Test
    fun speciesOutputsFromASavedObservationOmitWhatItNeverStored() {
        val obs = SpeciesObservation(
            imageUri = "file:///s.jpg", aphiaId = 105814, scientificName = "Octopus vulgaris",
            cosineScore = 0.7f, confidence = 0.85f, idSource = SpeciesIdSource.RAG_CORE.name,
        )
        val s = ContributionOutputs.speciesOf(obs)
        assertEquals("105814", s.speciesKey)
        assertNull(s.packVersion)
        assertTrue(s.candidates.isEmpty())
    }
}
