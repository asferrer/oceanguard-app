package com.oceanguard.ai.species

import android.graphics.Bitmap
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.species.SampleSpeciesData
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.CropProvider
import com.oceanguard.ai.inference.species.NoOpVlmImageEngine
import com.oceanguard.ai.inference.species.OrganismCrop
import com.oceanguard.ai.inference.species.SpeciesIdentifier
import com.oceanguard.ai.inference.species.SpeciesPipelineProvider
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/**
 * Root cause of "every photo is identified as Common octopus" (v1.1.1).
 *
 * The production identifier fell back to [SampleSpeciesData] whenever the
 * species pack was not installed when `MainActivity.onCreate` resolved the
 * lazy identifier. That demo pipeline never looks at the photo: its embedder
 * returns spike `abs(bitmap.hashCode()) % 4`, which matches one of four
 * hard-coded prototypes with cosine 1.0, i.e. RAG_CORE at ~99.8 % confidence.
 * On ART the identity hash comes from a linear congruential generator whose two
 * low bits just count the hashes generated in the process, so the "species"
 * only depends on how many objects were hashed before, never on the image.
 */
class SpeciesDemoRootCauseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val demoKeys = SampleSpeciesData.catalogEntries().map { it.speciesKey }.toSet()

    @Test
    fun `demo embedder never reads the pixels`() = runBlocking {
        val bitmap = mock(Bitmap::class.java)
        val v = SampleSpeciesData.embedder().embed(bitmap)
        verifyNoInteractions(bitmap) // no getPixels / getWidth: the image is ignored
        assertEquals(1f, v[abs(bitmap.hashCode()) % 4], 0f)
    }

    @Test
    fun `demo pipeline returns a hard-coded species with near-certain confidence for any input`() = runBlocking {
        val sut = SpeciesIdentifier(
            embedder = SampleSpeciesData.embedder(),
            index = SampleSpeciesData.buildIndex(),
            locator = WholeFrame,
            isBlankFrame = { false },
        )
        repeat(20) {
            val r = sut.identify(mock(Bitmap::class.java)).single()
            assertTrue(r.speciesKey in demoKeys)
            assertEquals(SpeciesIdSource.RAG_CORE, r.idSource)
            assertTrue("confidence ${r.confidence}", r.confidence > 0.99f)
        }
    }

    /**
     * ART `Object::GenerateIdentityHashCode` (art/runtime/mirror/object.cc):
     * seed = seed * 1103515245 + 12345 (mod 2^32), hash = seed & ((1 << 28) - 1).
     * 1103515245 and 12345 are both 1 mod 4, so hash mod 4 advances by exactly
     * +1 per generated hash: the demo species is (hashes so far) mod 4.
     */
    @Test
    fun `ART identity hash mod 4 only counts generated hashes`() {
        var seed = 0x2545F491L
        var previous = -1L
        repeat(1000) {
            val hash = seed and ((1L shl 28) - 1)
            if (previous >= 0) assertEquals((previous + 1) % 4, hash % 4)
            previous = hash % 4
            seed = (seed * 1103515245L + 12345L) and 0xFFFFFFFFL
        }
    }

    @Test
    fun `without a species pack there is no identifier (no demo fallback)`() {
        val dir = tmp.newFolder("species")
        val provider = SpeciesPipelineProvider({ dir }, SpeciesCatalog(File(dir, "species_catalog_v1.json")), NoOpVlmImageEngine())
        assertNull(provider.current())
    }

    @Test
    fun `a broken pack gives no identifier instead of the demo`() {
        val dir = tmp.newFolder("species")
        listOf("clip_vitb32.onnx", "species_index_v1.bin").forEach { File(dir, it).writeText("not a model") }
        File(dir, "species_catalog_v1.json").writeText("""{"species":[]}""")
        val provider = SpeciesPipelineProvider({ dir }, SpeciesCatalog(File(dir, "species_catalog_v1.json")), NoOpVlmImageEngine())
        assertNull(provider.current())
    }

    private object WholeFrame : CropProvider {
        override suspend fun locate(bitmap: Bitmap): List<OrganismCrop> =
            listOf(OrganismCrop(BoundingBox(0f, 0f, 1f, 1f), bitmap, 1f))
    }
}
