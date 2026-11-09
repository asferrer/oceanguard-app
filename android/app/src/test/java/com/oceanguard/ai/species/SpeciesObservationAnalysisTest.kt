package com.oceanguard.ai.species

import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.data.OceanGuardDatabase
import com.oceanguard.ai.data.contribution.inMemoryDatabase
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.IdentificationResult
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The identification run (a photo or a batch) is saved with every observation it produces. */
@RunWith(RobolectricTestRunner::class)
class SpeciesObservationAnalysisTest {

    private lateinit var db: OceanGuardDatabase
    private lateinit var repo: SpeciesCollectionRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        val catalog = SpeciesCatalog(File("unused.json")).apply {
            seedInMemory(listOf(entry("caretta_caretta", 137205L, "Caretta caretta"), entry("mola_mola", 127405L, "Mola mola")))
        }
        repo = SpeciesCollectionRepository(db.speciesObservationDao(), db.speciesDexDao(), catalog)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a batch shares one analysis id across its photos`() = runBlocking {
        repo.saveObservation(result("caretta_caretta", "Caretta caretta"), "content://a", null, null, analysisId = 1000L)
        repo.saveObservation(result("mola_mola", "Mola mola"), "content://b", null, null, analysisId = 1000L)
        repo.saveObservation(result("mola_mola", "Mola mola"), "content://c", null, null, analysisId = 2000L)

        val all = db.speciesObservationDao().getAll().first()
        assertEquals(listOf(1000L, 1000L), all.filter { it.imageUri != "content://c" }.map { it.analysisId })
        assertEquals(2000L, all.single { it.imageUri == "content://c" }.analysisId)
    }

    @Test
    fun `an observation saved without analysis keeps it null`() = runBlocking {
        repo.saveObservation(result("mola_mola", "Mola mola"), "content://old", null, null)
        assertNull(db.speciesObservationDao().getAll().first().single().analysisId)
    }

    private fun entry(key: String, aphia: Long, name: String) = SpeciesCatalogEntry(
        speciesKey = key, aphiaId = aphia, scientificName = name, commonNames = mapOf("en" to name),
        spritePath = null, ecoregions = emptyList(),
    )

    private fun result(key: String, name: String) = IdentificationResult(
        speciesKey = key, scientificName = name, idSource = SpeciesIdSource.RAG_CORE,
        cosineScore = 0.9f, confidence = 0.95f,
    )
}
