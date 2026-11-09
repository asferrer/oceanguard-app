package com.oceanguard.ai.species

import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.data.OceanGuardDatabase
import com.oceanguard.ai.data.contribution.inMemoryDatabase
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.data.species.SpeciesIdSource
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The species gallery and map find the observations of a species whose catalogue key is a name. */
@RunWith(RobolectricTestRunner::class)
class SpeciesObservationLookupTest {

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
    fun `observations saved now are found by catalogue key`() = runBlocking {
        repo.saveObservation(result("caretta_caretta", "Caretta caretta"), "content://a", null, null)
        repo.saveObservation(result("mola_mola", "Mola mola"), "content://b", null, null)

        val found = repo.observationsForSpecies("caretta_caretta").first()
        assertEquals(listOf("content://a"), found.map { it.imageUri })
        assertEquals(137205L, found.single().aphiaId)
    }

    @Test
    fun `observations saved without AphiaID are found by scientific name`() = runBlocking {
        db.speciesObservationDao().insert(
            SpeciesObservation(
                imageUri = "content://old", scientificName = "Caretta caretta", aphiaId = null,
                cosineScore = 0.9f, confidence = 0.9f, idSource = SpeciesIdSource.RAG_CORE.name,
            ),
        )
        assertEquals(1, repo.observationsForSpecies("caretta_caretta").first().size)
        assertEquals(0, repo.observationsForSpecies("mola_mola").first().size)
    }

    @Test
    fun `recompute keeps the dex entries of name-keyed species`() = runBlocking {
        repo.saveObservation(result("caretta_caretta", "Caretta caretta"), "content://a", null, null)
        repo.recomputeFromObservations()
        assertEquals(1, db.speciesDexDao().getDiscoveredCount().first())
    }

    private fun entry(key: String, aphiaId: Long, name: String) = SpeciesCatalogEntry(
        speciesKey = key, aphiaId = aphiaId, scientificName = name, commonNames = mapOf("en" to name),
        spritePath = null, ecoregions = emptyList(), cosmopolitan = false,
    )

    private fun result(key: String, name: String) = IdentificationResult(
        speciesKey = key, scientificName = name, idSource = SpeciesIdSource.RAG_CORE,
        cosineScore = 0.9f, confidence = 0.95f,
    )
}
