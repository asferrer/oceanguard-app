package com.oceanguard.ai.species

import com.oceanguard.ai.inference.species.SelfTestPattern
import com.oceanguard.ai.inference.species.SpeciesCandidate
import com.oceanguard.ai.inference.species.SpeciesDescriber
import com.oceanguard.ai.inference.species.SpeciesPackAssets
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import com.oceanguard.ai.inference.species.VlmChoice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Species pack manifest, self-test input and Gemma choice prompt (parity with tools/species). */
class SpeciesPackConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifestJson = """
        {"version":2,"encoderFile":"biodex_enc_v2.onnx","indexFile":"species_index_v2.bin",
         "catalogFile":"species_catalog_v1.json",
         "encoder":{"inputName":"pixel_values","outputName":"image_features","size":224,
                    "mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5],"dim":768},
         "calibration":{"plattA":20.0,"plattB":-12.0,"tauHigh":0.9,"tauLow":0.5,"delta":0.0},
         "vlmChoiceTopK":5,
         "selfTest":{"pattern":"gradient_v1","expected":[0.1,0.2],"minCosine":0.99}}
    """.trimIndent()

    @Test
    fun `manifest is parsed with encoder contract and calibration`() {
        val c = SpeciesPackConfig.parse(manifestJson)
        assertNotNull(c)
        assertEquals(768, c!!.encoder.dim)
        assertEquals(0.5f, c.encoder.mean[1], 0f)
        assertEquals(0.9f, c.calibration.toConfidence().tauHigh, 0f)
        assertEquals(5, c.vlmChoiceTopK)
        assertEquals(listOf("biodex_enc_v2.onnx", "species_index_v2.bin", "species_catalog_v1.json"), c.requiredFiles())
    }

    @Test
    fun `invalid or incomplete manifests are rejected`() {
        assertNull(SpeciesPackConfig.parse("not json"))
        assertNull(SpeciesPackConfig.parse("""{"version":2,"encoderFile":"a.onnx"}"""))
    }

    @Test
    fun `load uses the manifest when present and needs every file`() {
        val dir = tmp.newFolder("species")
        File(dir, SpeciesPackConfig.MANIFEST).writeText(manifestJson)
        assertNull("files missing", SpeciesPackConfig.load(dir))
        listOf("biodex_enc_v2.onnx", "species_index_v2.bin", "species_catalog_v1.json").forEach { File(dir, it).writeText("x") }
        assertEquals(2, SpeciesPackConfig.load(dir)!!.version)
    }

    @Test
    fun `load falls back to the published v1 layout without manifest`() {
        val dir = tmp.newFolder("species")
        assertNull(SpeciesPackConfig.load(dir))
        listOf("clip_vitb32.onnx", "species_index_v1.bin", "species_catalog_v1.json").forEach { File(dir, it).writeText("x") }
        val c = SpeciesPackConfig.load(dir)!!
        assertEquals(1, c.version)
        assertEquals(512, c.encoder.dim)
        assertTrue(SpeciesPackAssets.fileNames(dir).containsAll(c.requiredFiles()))
    }

    @Test
    fun `self-test pattern matches the Python generator`() {
        val spec = SpeciesPackConfig.EncoderSpec(mean = listOf(0.5f, 0.5f, 0.5f), std = listOf(0.5f, 0.5f, 0.5f))
        val t = SelfTestPattern.tensor(SelfTestPattern.GRADIENT_V1, spec)!!
        val s = 224
        val plane = s * s
        // Reference values printed by tools/species/build_pack.py::self_test_pattern.
        assertEquals(-1f, t[0], 1e-6f)
        assertEquals(1f, t[10 * s + 223], 1e-6f)
        assertEquals(1f, t[plane + 223 * s + 5], 1e-6f)
        assertEquals(-0.3333333f, t[2 * plane + 100 * s + 50], 1e-6f)
        assertNull(SelfTestPattern.tensor("unknown", spec))
    }

    @Test
    fun `cosine is scale invariant and 0 for size mismatch`() {
        assertEquals(1f, SelfTestPattern.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, SelfTestPattern.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test
    fun `choice prompt is the one evaluated on the PC`() {
        val prompt = SpeciesDescriber.buildChoicePrompt(listOf(
            SpeciesCandidate("octopus_vulgaris", "Octopus vulgaris", "Common octopus"),
            SpeciesCandidate("sepia_officinalis", "Sepia officinalis", ""),
        ))
        // Output of tools/species/gemma_rerank.py::choice_prompt for the same candidates.
        val expected = "Which of these species is the main organism in the photo?\n" +
            "1. Octopus vulgaris (Common octopus)\n2. Sepia officinalis\n" +
            "0. None of them, or the species cannot be told from the photo.\nAnswer with the number only."
        assertEquals(expected, prompt)
    }

    @Test
    fun `softmax confidence measures how much the best species stands out`() {
        fun entry(k: String) = com.oceanguard.ai.data.species.SpeciesCatalogEntry(
            speciesKey = k, aphiaId = null, scientificName = k, commonNames = emptyMap(),
            spritePath = null, ecoregions = emptyList(), cosmopolitan = true,
        )
        fun spike(i: Int) = FloatArray(4).also { it[i] = 1f }
        val seeds = listOf("a" to 0, "b" to 1, "c" to 2).map { (k, i) ->
            com.oceanguard.ai.inference.species.SpeciesReferenceIndex.Companion.ProtoSeed(k, k, spike(i))
        }
        val index = com.oceanguard.ai.inference.species.SpeciesReferenceIndex.inMemory(
            mapOf("a" to entry("a"), "b" to entry("b"), "c" to entry("c")), seeds, dim = 4,
            confidence = com.oceanguard.ai.inference.species.SpeciesConfidence(temperature = 0.05f),
        )
        // Query close to "a" (cos 0.8) and also close to "b" (cos 0.6).
        val m = index.search(floatArrayOf(0.8f, 0.6f, 0f, 0f), region = null, topK = 3)
        assertEquals("a", m[0].speciesKey)
        val expectedA = 1.0 / (1.0 + kotlin.math.exp(-4.0) + kotlin.math.exp(-16.0))
        assertEquals(expectedA.toFloat(), m[0].confidence, 1e-5f)
        assertEquals(1f, m.sumOf { it.confidence.toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `choice answers are parsed`() {
        assertEquals(VlmChoice.Picked(1), SpeciesDescriber.parseChoice(" 2", 5))
        assertEquals(VlmChoice.NoneOfThem, SpeciesDescriber.parseChoice("0", 5))
        assertNull(SpeciesDescriber.parseChoice("7", 5))
        assertNull(SpeciesDescriber.parseChoice("none", 5))
    }
}
