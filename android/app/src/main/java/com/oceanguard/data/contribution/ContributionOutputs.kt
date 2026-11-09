package com.oceanguard.ai.data.contribution

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DetectionSession
import kotlin.math.roundToInt

/** One species answer of the app for a photo, as it goes in `outputs.species`. */
data class SpeciesOutput(
    val speciesKey: String?,
    val scientificName: String,
    val idSource: String,
    val confidence: Float,
    val candidates: List<Pair<String, Float>> = emptyList(),
    val bboxPx: BoundingBox? = null,
    val geoMatchLevel: String? = null,
    val outOfRange: Boolean = false,
    val packVersion: Int? = null,
)

/**
 * What the app answered for a photo, sent next to the photo so the NAS can train on it
 * (schema in docs/CONTRIBUTION.md). Only what the app really has: nothing is invented, and no
 * location or device data ever goes in here (the location stays in the EXIF, if allowed).
 *
 * Queued without the `image` block (its dimensions and hash exist only once the bytes to send
 * are known); [finish] adds it at upload time.
 */
object ContributionOutputs {
    const val SCHEMA = 1

    /** Debris detections of a saved session. */
    fun forDetection(session: DetectionSession): String = JsonObject().apply {
        addProperty("schema", SCHEMA)
        add("detection", detection(session))
    }.toString()

    /** Species identifications of one photo. */
    fun forSpecies(items: List<SpeciesOutput>): String = JsonObject().apply {
        addProperty("schema", SCHEMA)
        add("species", JsonArray().apply { items.forEach { add(species(it)) } })
    }.toString()

    fun speciesOf(result: IdentificationResult, packVersion: Int?) = SpeciesOutput(
        speciesKey = result.speciesKey.takeIf { !result.uncatalogued },
        scientificName = result.scientificName,
        idSource = result.idSource.name,
        confidence = result.confidence,
        candidates = result.candidates.map { it.speciesKey to it.confidence },
        bboxPx = result.bbox,
        geoMatchLevel = result.geoMatchLevel,
        outOfRange = result.outOfRange,
        packVersion = packVersion,
    )

    /** Saved observations carry no candidates or pack version: those are omitted. */
    fun speciesOf(obs: SpeciesObservation) = SpeciesOutput(
        speciesKey = obs.aphiaId?.toString(),
        scientificName = obs.scientificName,
        idSource = obs.idSource,
        confidence = obs.confidence,
        bboxPx = obs.bbox,
        geoMatchLevel = obs.geoMatchLevel,
        outOfRange = obs.outOfRange,
    )

    /** Final `outputs` for the envelope: the queued answer plus the `image` block of the sent bytes. */
    fun finish(queuedJson: String?, image: JsonObject): JsonObject {
        val out = queuedJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            ?: JsonObject().apply { addProperty("schema", SCHEMA) }
        out.add("image", image)
        val w = image.get("width").asInt
        val h = image.get("height").asInt
        out.getAsJsonObject("detection")?.getAsJsonArray("items")?.forEach { item ->
            val o = item.asJsonObject
            val norm = o.getAsJsonArray("bbox_norm") ?: return@forEach
            o.add("bbox_px", JsonArray().apply {
                add(norm[0].asFloat * w); add(norm[1].asFloat * h)
                add(norm[2].asFloat * w); add(norm[3].asFloat * h)
            })
        }
        return out
    }

    private fun detection(session: DetectionSession) = JsonObject().apply {
        session.modelName?.let { addProperty("model", it) }
        addProperty("processing_ms", session.processingTimeMs)
        addProperty("image_quality", session.imageQuality.name)
        add("items", JsonArray().apply { session.debrisList.forEach { add(item(it)) } })
    }

    private fun item(d: Debris) = JsonObject().apply {
        addProperty("class", d.type.name)
        addProperty("score", d.confidence)
        add("bbox_norm", norm(d.bbox))
        d.subType?.let { addProperty("subtype", it.name) }
        addProperty("material", d.material.name)
        d.rawLabel?.let { addProperty("raw_label", it) }
        addProperty("source", if (d.rawLabel != null) "gemma" else "detector")
    }

    private fun norm(b: BoundingBox) = JsonArray().apply {
        val x = b.x.coerceIn(0f, 1f)
        val y = b.y.coerceIn(0f, 1f)
        add(x); add(y); add(b.width.coerceIn(0f, 1f - x)); add(b.height.coerceIn(0f, 1f - y))
    }

    private fun species(s: SpeciesOutput) = JsonObject().apply {
        s.speciesKey?.let { addProperty("species_key", it) }
        addProperty("scientific_name", s.scientificName)
        addProperty("id_source", s.idSource)
        addProperty("confidence", s.confidence)
        if (s.candidates.isNotEmpty()) add("candidates", JsonArray().apply {
            s.candidates.forEach { (key, score) ->
                add(JsonObject().apply { addProperty("species_key", key); addProperty("score", score) })
            }
        })
        s.bboxPx?.let { b ->
            add("bbox_px", JsonArray().apply {
                add(b.x.roundToInt()); add(b.y.roundToInt()); add(b.width.roundToInt()); add(b.height.roundToInt())
            })
        }
        s.packVersion?.let { addProperty("pack_version", it) }
        s.geoMatchLevel?.let { addProperty("geo_match_level", it) }
        if (s.outOfRange) addProperty("out_of_range", true)
    }
}
