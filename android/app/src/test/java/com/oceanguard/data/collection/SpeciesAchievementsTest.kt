package com.oceanguard.ai.data.collection

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.oceanguard.ai.data.OceanGuardDatabase
import com.oceanguard.ai.data.contribution.inMemoryDatabase
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesIdSource
import com.oceanguard.ai.inference.species.IdentificationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeciesAchievementsTest {

    private lateinit var db: OceanGuardDatabase
    private lateinit var repo: SpeciesCollectionRepository

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = inMemoryDatabase(context)
        val checker = AchievementChecker(
            collectionRepo = CollectionRepository(db.marineDexDao(), db.achievementDao()),
            achievementDao = db.achievementDao(),
            marineDexDao = db.marineDexDao(),
            sessionDao = db.detectionSessionDao(),
            speciesDexDao = db.speciesDexDao(),
        )
        repo = SpeciesCollectionRepository(
            db.speciesObservationDao(), db.speciesDexDao(), SpeciesCatalog(File("missing.json")), checker,
        )
        runBlocking { CollectionRepository(db.marineDexDao(), db.achievementDao()).ensureAchievementsSeeded() }
    }

    @After
    fun close() = db.close()

    private fun result(key: String) = IdentificationResult(
        speciesKey = key, scientificName = "Species $key", idSource = SpeciesIdSource.RAG_CORE,
        cosineScore = 0.9f, confidence = 0.9f,
    )

    private fun save(key: String) = runBlocking { repo.saveObservation(result(key), "content://x/$key", null, null) }

    private fun unlocked(id: String): Boolean =
        runBlocking { db.achievementDao().getById(id)!!.unlockedAt > 0 }

    @Test
    fun distinctSpeciesUnlockTheCountAchievements() {
        assertFalse(unlocked("species_1"))
        save("s0")
        assertTrue(unlocked("species_1"))
        assertFalse(unlocked("species_5"))
        (1..4).forEach { save("s$it") }
        assertTrue(unlocked("species_5"))
        assertFalse(unlocked("species_15"))
        (5..14).forEach { save("s$it") }
        assertTrue(unlocked("species_15"))
        assertFalse(unlocked("species_40"))
    }

    @Test
    fun repeatedSightingsOfOneSpeciesDoNotCountAsDistinct() {
        repeat(6) { save("same") }
        assertTrue(unlocked("species_1"))
        assertFalse(unlocked("species_5"))
    }

    @Test
    fun fiftyObservationsUnlockTheObservationAchievement() {
        repeat(49) { save("same") }
        assertFalse(unlocked("species_obs_50"))
        save("same")
        assertTrue(unlocked("species_obs_50"))
    }

    @Test
    fun favouritesUnlockAndUnmarkingBeforeTheThirdDoesNot() {
        listOf("a", "b", "c").forEach { save(it) }
        runBlocking {
            repo.setFavorite("a", true)
            repo.setFavorite("b", true)
            repo.setFavorite("b", false)
            repo.setFavorite("c", true)
        }
        assertFalse(unlocked("species_favorite_3"))
        runBlocking { repo.setFavorite("b", true) }
        assertTrue(unlocked("species_favorite_3"))
    }

    @Test
    fun metaAchievementTargetIsTotalMinusOneAndUnlocksWithEveryOther() {
        assertEquals(ACHIEVEMENT_TOTAL - 1, runBlocking { db.achievementDao().getById("all_achievements")!!.target })
        runBlocking {
            ALL_ACHIEVEMENTS.filter { it.id != "all_achievements" && it.id != "species_1" }.forEach {
                db.achievementDao().upsert(Achievement(it.id, unlockedAt = 1L, progress = it.target, target = it.target))
            }
        }
        assertFalse(unlocked("all_achievements"))
        save("s0")
        assertTrue(unlocked("all_achievements"))
    }

    @Test
    fun existingInstallGetsNewAchievementsAndKeepsProgress() {
        val dao = db.achievementDao()
        runBlocking {
            dao.deleteAll()
            // Old install: 25 rows, meta target 24, one achievement already unlocked.
            ALL_ACHIEVEMENTS.filter { !it.id.startsWith("species_") }.forEach {
                val old = if (it.id == "all_achievements") 24 else it.target
                dao.upsert(Achievement(it.id, unlockedAt = if (it.id == "scan_10") 5L else 0L, progress = if (it.id == "scan_10") 10 else 0, target = old))
            }
            CollectionRepository(db.marineDexDao(), dao).ensureAchievementsSeeded()
        }
        assertEquals(ACHIEVEMENT_TOTAL, runBlocking { dao.getAll().first() }.size)
        assertEquals(ACHIEVEMENT_TOTAL - 1, runBlocking { dao.getById("all_achievements")!!.target })
        assertTrue(unlocked("scan_10"))
        assertFalse(unlocked("species_1"))
    }
}
