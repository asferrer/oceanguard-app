package com.oceanguard.ai.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.REMOTE_SPECIES_MANIFESTS
import com.oceanguard.ai.inference.species.CropProvider
import com.oceanguard.ai.inference.species.GeoMatchLevel
import com.oceanguard.ai.inference.species.GroupFallback
import com.oceanguard.ai.inference.species.OrganismCrop
import com.oceanguard.ai.inference.species.SpeciesConfidence
import com.oceanguard.ai.inference.species.SpeciesEmbedder
import com.oceanguard.ai.inference.species.SpeciesIdentifier
import com.oceanguard.ai.inference.species.SpeciesMatch
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.SpeciesPackUpdate
import com.oceanguard.ai.inference.species.SpeciesReferenceIndex
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Group fallback (pack manifest `groupFallback`), candidate names and the pack update rules. */
class SpeciesGroupFallbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val conf = SpeciesConfidence(tauHigh = 0.9f, tauLow = 0.6f, delta = 0.0f, temperature = 0.05f)

    private fun entry(key: String, group: String?, names: Map<String, String> = emptyMap()) = SpeciesCatalogEntry(
        speciesKey = key, aphiaId = null, scientificName = "Species $key",
        commonNames = names, spritePath = null, ecoregions = emptyList(), cosmopolitan = true, group = group,
    )

    private val catalog = mapOf(
        "s0" to entry("s0", "sharks_rays", mapOf("en" to "Ray zero", "es" to "Raya cero")),
        "s1" to entry("s1", "sharks_rays", mapOf("en" to "Ray one")),
        "s2" to entry("s2", "bony_fish"),
        "s3" to entry("s3", "bony_fish"),
        "s4" to entry("s4", null),
    )

    private fun index(): SpeciesReferenceIndex = SpeciesReferenceIndex.inMemory(
        catalogEntries = catalog,
        prototypes = catalog.keys.mapIndexed { i, k ->
            SpeciesReferenceIndex.Companion.ProtoSeed(k, "Species $k", SpeciesReferenceIndexTest.spikeVector(i))
        },
        confidence = conf,
    )

    private fun mix(vararg dims: Int): FloatArray {
        val v = FloatArray(SpeciesEmbedder.EMBEDDING_DIM)
        dims.forEach { v[it] = 1f / sqrt(dims.size.toFloat()) }
        return v
    }

    private class FixedEmbedder(private val v: FloatArray) : SpeciesEmbedder {
        override val isReady = true
        override suspend fun embed(bitmap: Bitmap) = v
        override fun close() {}
    }

    private fun identify(query: FloatArray, threshold: Float?) = runBlocking {
        val locator = object : CropProvider {
            override suspend fun locate(bitmap: Bitmap) =
                listOf(OrganismCrop(BoundingBox(0f, 0f, 1f, 1f), bitmap, 1f))
        }
        SpeciesIdentifier(
            embedder = FixedEmbedder(query), index = index(), confidence = conf, locator = locator,
            isBlankFrame = { false }, groupFallbackThreshold = threshold,
        ).identify(org.mockito.Mockito.mock(Bitmap::class.java)).single()
    }

    @Test
    fun `search sums the softmax over all species of each group`() {
        val out = index().searchDetailed(mix(0, 1), region = null, topK = 1)
        assertEquals(1, out.matches.size)
        assertEquals(1f, out.groupProbabilities.getValue("sharks_rays"), 0.01f)
        assertTrue(out.groupProbabilities.getValue("bony_fish") < 0.01f)
        assertEquals(setOf("sharks_rays", "bony_fish"), out.groupProbabilities.keys)
    }

    @Test
    fun `search returns the same matches as before`() {
        val idx = index()
        assertEquals(idx.search(mix(0, 1), null), idx.searchDetailed(mix(0, 1), null).matches)
    }

    @Test
    fun `unidentified with a group above the threshold carries the group and stays not confident`() {
        val r = identify(mix(0, 1), threshold = 0.62f)
        assertEquals(SpeciesIdSource.UNIDENTIFIED, r.idSource)
        assertEquals("sharks_rays", r.groupKey)
        assertTrue(r.groupConfidence >= 0.62f)
        assertTrue(r.isUnidentified)
        assertFalse(r.isConfident)
    }

    @Test
    fun `without the manifest field there is no group`() {
        val r = identify(mix(0, 1), threshold = null)
        assertEquals(SpeciesIdSource.UNIDENTIFIED, r.idSource)
        assertNull(r.groupKey)
        assertEquals(0f, r.groupConfidence, 0f)
    }

    @Test
    fun `no group when the best group is below the threshold`() {
        val r = identify(mix(0, 2), threshold = 0.62f)
        assertEquals(SpeciesIdSource.UNIDENTIFIED, r.idSource)
        assertNull(r.groupKey)
    }

    @Test
    fun `a confident species never gets a group`() {
        val r = identify(mix(0), threshold = 0.1f)
        assertEquals(SpeciesIdSource.RAG_CORE, r.idSource)
        assertNull(r.groupKey)
    }

    @Test
    fun `group best honours the threshold`() {
        val sums = mapOf("a" to 0.7f, "b" to 0.2f)
        assertEquals("a", GroupFallback.best(sums, 0.62f)?.key)
        assertEquals(0.7f, GroupFallback.best(sums, 0.7f)!!.confidence, 0f)
        assertNull(GroupFallback.best(sums, 0.71f))
        assertNull(GroupFallback.best(sums, null))
        assertNull(GroupFallback.best(emptyMap(), 0.5f))
    }

    @Test
    fun `groupSums ignores species without group and caps at one`() {
        val sums = GroupFallback.groupSums(listOf("a" to 0.6f, "b" to 0.6f, "c" to 0.3f)) {
            mapOf("a" to "g", "b" to "g", "c" to null)[it]
        }
        assertEquals(mapOf("g" to 1f), sums)
    }

    @Test
    fun `manifest field groupFallback is parsed and absent means null`() {
        val base = """{"version":4,"encoderFile":"e.onnx","indexFile":"i.bin","catalogFile":"c.json",
            "encoder":{"mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},
            "calibration":{"plattA":1,"plattB":0,"tauHigh":0.9,"tauLow":0.5,"delta":0,"temperature":0.02}"""
        val with = SpeciesPackConfig.parse("$base,\"groupFallback\":{\"threshold\":0.62}}")
        assertNotNull(with)
        assertEquals(0.62f, with!!.groupThreshold!!, 0f)
        assertNull(SpeciesPackConfig.parse("$base}")!!.groupThreshold)
        assertNull(SpeciesPackConfig.parse("$base,\"groupFallback\":{\"threshold\":0}}")!!.groupThreshold)
        assertNull(SpeciesPackConfig.parse("$base,\"groupFallback\":{\"threshold\":7}}")!!.groupThreshold)
    }

    @Test
    fun `remote manifests are tried v4 then v3 then the legacy name`() {
        assertEquals(
            listOf("species_pack_v4.json", "species_pack_v3.json", "species_pack.json"),
            REMOTE_SPECIES_MANIFESTS,
        )
    }

    @Test
    fun `candidate names use the language then English then the scientific name`() {
        fun match(k: String) = SpeciesMatch(k, "Species $k", 0.5f, 0.1f, GeoMatchLevel.GLOBAL)
        val four = listOf(match("s0"), match("s1"), match("s2"), match("s3"))
        assertEquals(listOf("Raya cero", "Ray one", "Species s2"), GroupFallback.candidateNames(four, catalog::get, "es"))
        assertEquals(listOf("Species zz"), GroupFallback.candidateNames(listOf(match("zz")), catalog::get, "en"))
        assertTrue(GroupFallback.candidateNames(emptyList(), catalog::get, "en").isEmpty())
    }

    @Test
    fun `update is offered only for a newer remote version`() {
        assertTrue(SpeciesPackUpdate.isNewer(3, 4))
        assertFalse(SpeciesPackUpdate.isNewer(4, 4))
        assertFalse(SpeciesPackUpdate.isNewer(null, 4))
        assertFalse(SpeciesPackUpdate.isNewer(3, null))
    }

    @Test
    fun `update reuses files by sha when the manifest has them and the encoder otherwise`() {
        val file = tmp.newFile("f.bin").apply { writeText("hello") }
        val sha = SpeciesPackUpdate.sha256(file)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
        val cfg = SpeciesPackConfig.parse(
            """{"version":4,"encoderFile":"enc.onnx","indexFile":"idx.bin","catalogFile":"cat.json",
               "encoder":{"mean":[0,0,0],"std":[1,1,1]},
               "calibration":{"plattA":1,"plattB":0,"tauHigh":0.9,"tauLow":0.5,"delta":0},
               "sha256":{"idx.bin":"${sha.uppercase()}","cat.json":"00"}}""",
        )!!
        fun reuse(name: String) = SpeciesPackUpdate.canReuse(name, cfg, true, 10, 1) { sha }
        assertTrue(reuse("idx.bin"))
        assertFalse(reuse("cat.json"))
        assertTrue(reuse("enc.onnx"))
        assertFalse(SpeciesPackUpdate.canReuse("enc.onnx", cfg, false, 0, 1) { sha })
        assertTrue(SpeciesPackUpdate.matches(file, null))
        assertTrue(SpeciesPackUpdate.matches(file, sha))
        assertFalse(SpeciesPackUpdate.matches(file, "00"))
        // Without sha the index is downloaded again.
        assertFalse(SpeciesPackUpdate.canReuse("idx.bin", cfg.copy(sha256 = null), true, 10, 1) { sha })
    }
}
