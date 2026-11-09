package com.oceanguard.ai.species

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.data.OceanGuardDatabase
import com.oceanguard.ai.data.contribution.inMemoryDatabase
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesDexEntry
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.DownloadFailureKind
import com.oceanguard.ai.inference.VlmDownloadException
import com.oceanguard.ai.inference.fetchFirstAvailable
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.inference.species.OrganismLocator
import com.oceanguard.ai.inference.species.SpeciesConfidence
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex.Companion.ProtoSeed
import com.oceanguard.ai.ui.screens.SpeciesViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun entry(key: String, name: String, aphia: Long?) = SpeciesCatalogEntry(
    speciesKey = key, aphiaId = aphia, scientificName = name, commonNames = emptyMap(),
    spritePath = null, ecoregions = emptyList(), cosmopolitan = true,
)

private fun spike(i: Int) = FloatArray(4).also { it[i] = 1f }

/** Pack v3 manifest fields: parsing, defaults and the Gson null trap. */
class SpeciesPackV3ConfigTest {

    private fun manifest(extra: String) = """
        {"version":3,"encoderFile":"e.onnx","indexFile":"i.bin","catalogFile":"c.json",
         "encoder":{"inputName":"pixel_values","outputName":"image_features","size":224,
                    "mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5],"dim":768},
         "calibration":{"plattA":0.0,"plattB":0.0,"tauHigh":0.773,"tauLow":0.531,"delta":0.0,"temperature":0.05421}
         $extra}
    """.trimIndent()

    @Test
    fun `v3 fields are parsed`() {
        val c = SpeciesPackConfig.parse(manifest(""","scoring":{"imageWeight":1.0,"textWeight":4.0,
            "textViewTag":"text","missingImageScore":0.8427},"sessionOptimization":"basic","cropMode":"full",
            "merged":{"hypselodoris_picta":"felimare_picta"}"""))!!
        assertEquals(SpeciesPackConfig.Scoring(1f, 4f, "text", 0.8427f), c.scoring)
        assertEquals(SpeciesPackConfig.Optimization.BASIC, c.optimization)
        assertTrue(c.fullCrop)
        assertEquals(mapOf("hypselodoris_picta" to "felimare_picta"), c.mergedKeys)
    }

    @Test
    fun `without the v3 fields the behaviour is the old one`() {
        val c = SpeciesPackConfig.parse(manifest(""))!!
        assertNull(c.scoring)
        assertEquals(SpeciesPackConfig.Optimization.ALL, c.optimization)
        assertFalse(c.fullCrop)
        assertEquals("center", c.cropMode)
        assertTrue(c.mergedKeys.isEmpty())
    }

    @Test
    fun `fields Gson leaves null get defaults and unknown values are ignored`() {
        val c = SpeciesPackConfig.parse(manifest(""","scoring":{"textWeight":2.0},"sessionOptimization":"turbo",
            "cropMode":"zoom","merged":{"a":"a"}"""))!!
        assertEquals(SpeciesPackConfig.Scoring(1f, 2f, "text", 0f), c.scoring)
        assertEquals(SpeciesPackConfig.Optimization.ALL, c.optimization)
        assertFalse(c.fullCrop)
        assertTrue("a key merged into itself is dropped", c.mergedKeys.isEmpty())
        val n = SpeciesPackConfig.parse(manifest(""","scoring":{"textViewTag":null},"merged":null"""))!!
        assertEquals("text", n.scoring!!.textViewTag)
        assertTrue(n.mergedKeys.isEmpty())
    }

    @Test
    fun `every pack config class is covered by the proguard keep rule`() {
        val rules = listOf("proguard-rules.pro", "app/proguard-rules.pro", "android/app/proguard-rules.pro")
            .map { File(it) }.first { it.exists() }.readText()
        assertTrue(rules.contains("-keep class com.oceanguard.ai.inference.species.SpeciesPackConfig\$* { *; }"))
        val nested = SpeciesPackConfig::class.java.declaredClasses.map { it.name }
        assertTrue(nested.all { it.startsWith("com.oceanguard.ai.inference.species.SpeciesPackConfig$") })
        assertTrue(nested.any { it.endsWith("Scoring") })
    }
}

/** Image + text row scoring of pack v3 in [SpeciesReferenceIndex]. */
class SpeciesTextScoringTest {

    private val catalog = mapOf(
        "a" to entry("a", "Aus a", 1), "b" to entry("b", "Bus b", 2), "c" to entry("c", "Cus c", 3),
    )
    private val seeds = listOf(
        ProtoSeed("a", "Aus a", spike(0), "lateral"),
        ProtoSeed("a", "Aus a", spike(1), "text"),
        ProtoSeed("b", "Bus b", spike(2), "text"),   // text only
        ProtoSeed("c", "Cus c", spike(3), "lateral"),
    )
    private val conf = SpeciesConfidence(temperature = 0.05f)
    private val scoring = SpeciesPackConfig.Scoring(imageWeight = 1f, textWeight = 4f, textViewTag = "text", missingImageScore = 0.5f)

    private fun index(s: SpeciesPackConfig.Scoring?) =
        SpeciesReferenceIndex.inMemory(catalog, seeds, dim = 4, confidence = conf, scoring = s)

    @Test
    fun `species with image and text rows adds weighted image max and text cosine`() {
        val m = index(scoring).search(floatArrayOf(0.6f, 0.8f, 0f, 0f), region = null, topK = 3)
        assertEquals("a", m[0].speciesKey)
        assertEquals(0.6f + 4f * 0.8f, m[0].cosineScore, 1e-5f)
        assertEquals(m[0].cosineScore, m[0].rankScore, 1e-5f)
    }

    @Test
    fun `text only species uses missingImageScore for the image part`() {
        val m = index(scoring).search(floatArrayOf(0f, 0f, 1f, 0f), region = null, topK = 3)
        assertEquals("b", m[0].speciesKey)
        assertEquals(0.5f + 4f * 1f, m[0].cosineScore, 1e-5f)
        assertTrue(m[0].confidence > 0.99f)
    }

    @Test
    fun `image row does not leak into the text part and the text row not into the image part`() {
        // Query equal to a's text row: image max of a is 0, text is 1.
        val m = index(scoring).search(floatArrayOf(0f, 1f, 0f, 0f), region = null, topK = 3)
        assertEquals("a", m[0].speciesKey)
        assertEquals(0f + 4f, m[0].cosineScore, 1e-5f)
        // c has an image row only: image 0 (not missing) and no text row.
        assertEquals(0f, m.first { it.speciesKey == "c" }.cosineScore, 1e-5f)
    }

    @Test
    fun `without scoring every row counts and the maximum cosine wins`() {
        val m = index(null).search(floatArrayOf(0.6f, 0.8f, 0f, 0f), region = null, topK = 3)
        assertEquals("a", m[0].speciesKey)
        assertEquals(0.8f, m[0].cosineScore, 1e-5f)   // the text row counts as one more row
        val b = index(null).search(floatArrayOf(0f, 0f, 1f, 0f), region = null, topK = 3)
        assertEquals("b", b[0].speciesKey)
        assertEquals(1f, b[0].cosineScore, 1e-5f)
    }
}

/** Crop mode "full" of the locator fallback. */
@RunWith(RobolectricTestRunner::class)
class OrganismLocatorCropModeTest {

    private val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)

    @Test
    fun `centre mode crops the centre square`() = runBlocking {
        val c = OrganismLocator().locate(photo).single()
        assertEquals(200, c.crop.width)
        assertEquals(200, c.crop.height)
        assertEquals(100f, c.bbox.x, 0f)
    }

    @Test
    fun `full mode resizes the whole photo to size x size`() = runBlocking {
        val c = OrganismLocator(fullFrameSize = 224).locate(photo).single()
        assertEquals(224, c.crop.width)
        assertEquals(224, c.crop.height)
        assertEquals(0f, c.bbox.x, 0f)
        assertEquals(400f, c.bbox.width, 0f)
        assertEquals(200f, c.bbox.height, 0f)
    }
}

/** Versioned manifest: v4 first, then v3, then the legacy name when the repository does not have it. */
class SpeciesManifestFetchTest {

    private fun missing() = VlmDownloadException("HTTP 404", kind = DownloadFailureKind.NOT_FOUND)

    @Test
    fun `asks v4 first and stops when it exists`() = runBlocking {
        val asked = mutableListOf<String>()
        val used = fetchFirstAvailable { asked += it }
        assertEquals("species_pack_v4.json", used)
        assertEquals(listOf("species_pack_v4.json"), asked)
    }

    @Test
    fun `falls back to v3 when v4 is not published`() = runBlocking {
        val asked = mutableListOf<String>()
        val used = fetchFirstAvailable { asked += it; if (it.contains("v4")) throw missing() }
        assertEquals("species_pack_v3.json", used)
        assertEquals(listOf("species_pack_v4.json", "species_pack_v3.json"), asked)
    }

    @Test
    fun `falls back to species_pack json when v4 and v3 are not published`() = runBlocking {
        val asked = mutableListOf<String>()
        val used = fetchFirstAvailable { asked += it; if (it.contains("_v")) throw missing() }
        assertEquals("species_pack.json", used)
        assertEquals(listOf("species_pack_v4.json", "species_pack_v3.json", "species_pack.json"), asked)
    }

    @Test
    fun `a connection failure on v3 is not turned into a silent downgrade`() = runBlocking {
        val asked = mutableListOf<String>()
        try {
            fetchFirstAvailable { asked += it; throw VlmDownloadException("offline", kind = DownloadFailureKind.NO_CONNECTION) }
            fail("expected an exception")
        } catch (e: VlmDownloadException) {
            assertEquals(DownloadFailureKind.NO_CONNECTION, e.kind)
        }
        assertEquals(1, asked.size)
    }

    @Test
    fun `fails when no manifest exists`() = runBlocking {
        try {
            fetchFirstAvailable { throw missing() }
            fail("expected an exception")
        } catch (e: VlmDownloadException) {
            assertEquals(DownloadFailureKind.NOT_FOUND, e.kind)
        }
    }
}

/** Only confident results enter the BioDex. */
class SaveOnlyConfidentTest {

    private fun result(source: SpeciesIdSource) = IdentificationResult(
        speciesKey = "k_${source.name}", scientificName = "N", idSource = source, cosineScore = 0.5f, confidence = 0.5f,
    )

    @Test
    fun `core is saved, tentative and unidentified are not`() {
        val all = SpeciesIdSource.entries.map(::result)
        val saved = SpeciesViewModel.resultsToSave(all).map { it.idSource }
        assertFalse(SpeciesIdSource.RAG_TENTATIVE in saved)
        assertFalse(SpeciesIdSource.UNIDENTIFIED in saved)
        assertTrue(SpeciesIdSource.RAG_CORE in saved)
    }

    @Test
    fun `batch summary counts the same results that are saved`() {
        val results = listOf(result(SpeciesIdSource.RAG_CORE), result(SpeciesIdSource.RAG_TENTATIVE))
        assertEquals(1, results.count { it.isConfident })
        assertEquals(SpeciesViewModel.resultsToSave(results), results.filter { it.isConfident })
    }
}

/** Entries and observations of a merged species key move under the new one. */
@RunWith(RobolectricTestRunner::class)
class SpeciesKeyMergeTest {

    private lateinit var db: OceanGuardDatabase
    private lateinit var repo: SpeciesCollectionRepository
    private lateinit var catalog: SpeciesCatalog
    private val merged = mapOf("hypselodoris_picta" to "felimare_picta")

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        catalog = SpeciesCatalog(File("unused.json")).apply {
            seedInMemory(listOf(entry("felimare_picta", "Felimare picta", 11L)))
        }
        repo = SpeciesCollectionRepository(db.speciesObservationDao(), db.speciesDexDao(), catalog)
    }

    @After
    fun tearDown() = db.close()

    private fun dex(key: String, name: String, times: Int, first: Long, last: Long, fav: Boolean = false, obs: Long = 1) =
        SpeciesDexEntry(key, name, first, obs, times, last, fav)

    private fun obs(name: String) = SpeciesObservation(
        imageUri = "content://$name", scientificName = name, aphiaId = null,
        cosineScore = 0.9f, confidence = 0.9f, idSource = SpeciesIdSource.RAG_CORE.name,
    )

    @Test
    fun `counters add up and the old key disappears`() = runBlocking {
        val dao = db.speciesDexDao()
        dao.upsert(dex("hypselodoris_picta", "Hypselodoris picta", 3, first = 100, last = 500, fav = true, obs = 7))
        dao.upsert(dex("felimare_picta", "Felimare picta", 2, first = 200, last = 400, obs = 9))
        db.speciesObservationDao().insert(obs("Hypselodoris picta"))

        assertEquals(1, repo.mergeSpeciesKeys(merged))

        assertNull(dao.getByKey("hypselodoris_picta"))
        val e = dao.getByKey("felimare_picta")!!
        assertEquals(5, e.timesObserved)
        assertEquals(100L, e.firstSeenAt)
        assertEquals(7L, e.firstSeenObservationId)
        assertEquals(500L, e.lastSeenAt)
        assertTrue(e.isFavorite)
        val seen = repo.observationsForSpecies("felimare_picta").first()
        assertEquals(1, seen.size)
        assertEquals("Felimare picta", seen.single().scientificName)
        assertEquals(11L, seen.single().aphiaId)
    }

    @Test
    fun `an entry without a counterpart is moved and the merge is idempotent`() = runBlocking {
        val dao = db.speciesDexDao()
        dao.upsert(dex("hypselodoris_picta", "Hypselodoris picta", 4, first = 10, last = 20))
        assertEquals(1, repo.mergeSpeciesKeys(merged))
        assertEquals(0, repo.mergeSpeciesKeys(merged))
        assertEquals(4, dao.getByKey("felimare_picta")!!.timesObserved)
        assertEquals("Felimare picta", dao.getByKey("felimare_picta")!!.scientificName)
        assertEquals(1, dao.getDiscoveredCount().first())
    }

    @Test
    fun `nothing is touched while the new key is not in the catalogue`() = runBlocking {
        val dao = db.speciesDexDao()
        dao.upsert(dex("hypselodoris_picta", "Hypselodoris picta", 4, first = 10, last = 20))
        val other = SpeciesCatalog(File("unused.json")).apply { seedInMemory(listOf(entry("otra_especie", "Otra especie", 12L))) }
        val noTarget = SpeciesCollectionRepository(db.speciesObservationDao(), dao, other)
        assertEquals(0, noTarget.mergeSpeciesKeys(merged))
        assertNotNull(dao.getByKey("hypselodoris_picta"))
    }
}
