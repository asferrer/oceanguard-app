package com.oceanguard.ai.data.species

import android.util.Log
import com.oceanguard.ai.data.Location
import com.oceanguard.ai.data.collection.AchievementChecker
import com.oceanguard.ai.inference.species.IdentificationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Date

/**
 * Repository for the BioDex species collection.
 *
 * Mirrors [com.oceanguard.ai.data.collection.CollectionRepository] for the
 * species track. Provides reactive Flows for the UI and suspend functions for
 * write operations.
 *
 * [completionPercentage] is computed against [SpeciesCatalog.size]; if the
 * catalog is not loaded yet it falls back to 0f gracefully.
 *
 * @param speciesObservationDao  DAO for raw observation data.
 * @param speciesDexDao          DAO for the deduplicated dex entries.
 * @param catalog                In-memory species catalog (must be loaded).
 * @param achievementChecker     Evaluates the BioDex achievements after each save / favourite.
 */
class SpeciesCollectionRepository(
    private val speciesObservationDao: SpeciesObservationDao,
    private val speciesDexDao: SpeciesDexDao,
    private val catalog: SpeciesCatalog,
    private val achievementChecker: AchievementChecker? = null,
) {
    private companion object {
        const val TAG = "SpeciesCollectionRepo"
    }

    /** Reactive stream of all dex entries, newest first. */
    val allDexEntries: Flow<List<SpeciesDexEntry>> = speciesDexDao.getAll()

    /** Number of distinct species discovered. */
    fun discoveredCount(): Flow<Int> = speciesDexDao.getDiscoveredCount()

    /**
     * All observations of [speciesKey]. Catalogue keys are names such as
     * `posidonia_oceanica`, not AphiaIDs, so the old lookup by key found nothing
     * and the species gallery and map were always empty.
     */
    fun observationsForSpecies(speciesKey: String): Flow<List<SpeciesObservation>> {
        val entry = catalog.byKey(speciesKey)
        val aphia = entry?.aphiaId?.toString() ?: speciesKey.toLongOrNull()?.toString()
        return speciesObservationDao.getForSpecies(aphia, entry?.scientificName ?: speciesKey)
    }

    /** Single observation by id, for the observation detail screen (M6). */
    suspend fun getObservationById(id: Long): SpeciesObservation? =
        speciesObservationDao.getById(id)

    /**
     * Completion percentage as a fraction in [0, 1].
     * Denominator is the number of taxons in the loaded catalog.
     * Returns 0f if the catalog is empty or not loaded.
     */
    val completionPercentage: Flow<Float> = speciesDexDao.getDiscoveredCount().map { discovered ->
        val total = catalog.size()
        if (total <= 0) 0f else discovered.toFloat() / total.toFloat()
    }

    val favoriteCount: Flow<Int> = speciesDexDao.getFavoriteCount()

    val totalObservations: Flow<Int> = speciesDexDao.getTotalObservations()

    /**
     * Persist a single [IdentificationResult] as a [SpeciesObservation] and
     * update the dex entry for the matched species.
     *
     * Mapping rules:
     * - [IdentificationResult.speciesKey].toLongOrNull() → [SpeciesObservation.aphiaId]
     *   (null for uncatalogued "uncat:*" keys).
     * - [IdentificationResult.idSource].name → [SpeciesObservation.idSource].
     * - All score/confidence/bbox/geo fields are forwarded verbatim.
     *
     * Even for uncatalogued results ([IdentificationResult.uncatalogued] == true)
     * a dex entry is created so the organism appears on the BioDex screen.
     *
     * @param result       Identification produced by [com.oceanguard.ai.inference.species.SpeciesIdentifier].
     * @param imageUri     Content URI of the full-resolution capture.
     * @param thumbnailUri Optional content URI of the annotated thumbnail.
     * @param location     GPS position at capture time; null when unavailable.
     * @return             The auto-generated primary key of the inserted [SpeciesObservation].
     */
    suspend fun saveObservation(
        result: IdentificationResult,
        imageUri: String,
        thumbnailUri: String?,
        location: Location?,
    ): Long {
        val observation = toObservation(result, imageUri, thumbnailUri, location)
        val obsId = speciesObservationDao.insert(observation)
        discoverOrUpdate(result.speciesKey, result.scientificName, obsId)
        achievementChecker?.checkSpeciesAchievements()
        return obsId
    }

    /**
     * Pure mapping from [IdentificationResult] to [SpeciesObservation].
     *
     * Extracted as a package-internal helper so it can be tested without a
     * Room database (no DAO calls here).
     */
    internal fun toObservation(
        result: IdentificationResult,
        imageUri: String,
        thumbnailUri: String?,
        location: Location?,
    ): SpeciesObservation = SpeciesObservation(
        imageUri       = imageUri,
        thumbnailUri   = thumbnailUri,
        aphiaId        = catalog.byKey(result.speciesKey)?.aphiaId ?: result.speciesKey.toLongOrNull(),
        scientificName = result.scientificName,
        bbox           = result.bbox,
        cosineScore    = result.cosineScore,
        confidence     = result.confidence,
        idSource       = result.idSource.name,
        vlmDescription = result.vlmDescription,
        uncatalogued   = result.uncatalogued,
        ecoregionId    = null,
        geoMatchLevel  = result.geoMatchLevel,
        outOfRange     = result.outOfRange,
        timestamp      = Date(),
        location       = location,
    )

    /**
     * Record a new or repeat sighting of [speciesKey].
     *
     * Creates a [SpeciesDexEntry] if this is the first sighting, otherwise
     * increments [SpeciesDexEntry.timesObserved] and updates [SpeciesDexEntry.lastSeenAt].
     *
     * @return true if this was a NEW discovery (first time seeing this species).
     */
    suspend fun discoverOrUpdate(
        speciesKey: String,
        scientificName: String,
        observationId: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        val existing = speciesDexDao.getByKey(speciesKey)
        return if (existing == null) {
            speciesDexDao.upsert(
                SpeciesDexEntry(
                    speciesKey = speciesKey,
                    scientificName = scientificName,
                    firstSeenAt = now,
                    firstSeenObservationId = observationId,
                    timesObserved = 1,
                    lastSeenAt = now,
                )
            )
            true
        } else {
            speciesDexDao.upsert(
                existing.copy(
                    timesObserved = existing.timesObserved + 1,
                    lastSeenAt = now,
                )
            )
            false
        }
    }

    /**
     * Fold the BioDex entries and observations of merged species keys (pack `merged`:
     * old key -> new key) into the new key: counters add up, the first sighting is the
     * earliest, the last the latest, and the favourite flag is kept if either had it.
     * Idempotent, so it can run every time the pack loads. A pair whose new key is not
     * in the loaded catalogue is skipped (nothing would show it) and retried next time.
     *
     * @return number of dex entries that were migrated.
     */
    suspend fun mergeSpeciesKeys(merged: Map<String, String>): Int {
        var migrated = 0
        for ((oldKey, newKey) in merged) {
            val target = catalog.byKey(newKey) ?: continue
            val old = speciesDexDao.getByKey(oldKey)
            val oldName = old?.scientificName ?: catalog.byKey(oldKey)?.scientificName
            if (oldName != null && oldName != target.scientificName) {
                speciesObservationDao.renameSpecies(oldName, target.scientificName, target.aphiaId)
            }
            if (old == null) continue
            val existing = speciesDexDao.getByKey(newKey)
            speciesDexDao.upsert(mergeEntries(old, existing, newKey, target.scientificName))
            speciesDexDao.deleteByKey(oldKey)
            migrated++
        }
        if (migrated > 0) {
            Log.i(TAG, "Merged $migrated BioDex entries into their new species key")
            achievementChecker?.checkSpeciesAchievements()
        }
        return migrated
    }

    private fun mergeEntries(old: SpeciesDexEntry, existing: SpeciesDexEntry?, key: String, name: String): SpeciesDexEntry {
        if (existing == null) return old.copy(speciesKey = key, scientificName = name)
        val first = if (old.firstSeenAt < existing.firstSeenAt) old else existing
        return existing.copy(
            scientificName = name,
            firstSeenAt = first.firstSeenAt,
            firstSeenObservationId = first.firstSeenObservationId,
            timesObserved = existing.timesObserved + old.timesObserved,
            lastSeenAt = maxOf(existing.lastSeenAt, old.lastSeenAt),
            isFavorite = existing.isFavorite || old.isFavorite,
        )
    }

    /** Toggle the favourite flag for a dex entry. No-op if key not found. */
    suspend fun setFavorite(speciesKey: String, favorite: Boolean) {
        speciesDexDao.setFavorite(speciesKey, favorite)
        achievementChecker?.checkSpeciesAchievements()
    }

    /**
     * Rebuild all [SpeciesDexEntry] rows from the surviving [SpeciesObservation]
     * table.
     *
     * Call after deleting one or more observations so the denormalised dex stays
     * consistent. Species with no remaining observations are removed (re-locked).
     * [SpeciesDexEntry.isFavorite] is preserved across the recompute.
     */
    suspend fun recomputeFromObservations() {
        val observations = speciesObservationDao.getAllAscending()
        val agg = mutableMapOf<String, ObsAgg>()

        // Dex entries are keyed by catalogue key; observations carry the scientific name.
        val keyByName = catalog.all().associate { it.scientificName to it.speciesKey }
        for (obs in observations) {
            val key = keyByName[obs.scientificName] ?: obs.aphiaId?.toString() ?: continue   // skip uncatalogued
            val current = agg[key]
            if (current == null) {
                agg[key] = ObsAgg(
                    scientificName = obs.scientificName,
                    count = 1,
                    firstSeenAt = obs.timestamp.time,
                    firstObservationId = obs.id,
                    lastSeenAt = obs.timestamp.time,
                )
            } else {
                agg[key] = current.copy(
                    count = current.count + 1,
                    lastSeenAt = maxOf(current.lastSeenAt, obs.timestamp.time),
                )
            }
        }

        val currentEntries = speciesDexDao.getAll().first()
        var relocked = 0
        var updated = 0

        for (entry in currentEntries) {
            val target = agg[entry.speciesKey]
            if (target == null) {
                speciesDexDao.deleteByKey(entry.speciesKey)
                relocked++
            } else if (
                entry.timesObserved != target.count ||
                entry.firstSeenAt != target.firstSeenAt ||
                entry.lastSeenAt != target.lastSeenAt
            ) {
                speciesDexDao.upsert(
                    entry.copy(
                        timesObserved = target.count,
                        firstSeenAt = target.firstSeenAt,
                        firstSeenObservationId = target.firstObservationId,
                        lastSeenAt = target.lastSeenAt,
                    )
                )
                updated++
            }
        }

        if (relocked + updated > 0) {
            Log.i(TAG, "Dex recompute: $relocked re-locked, $updated updated")
        }
    }

    /** Delete all dex entries and observations. Irreversible. */
    suspend fun resetAll() {
        speciesDexDao.deleteAll()
    }

    private data class ObsAgg(
        val scientificName: String,
        val count: Int,
        val firstSeenAt: Long,
        val firstObservationId: Long,
        val lastSeenAt: Long,
    )
}
