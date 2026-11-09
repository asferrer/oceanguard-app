package com.oceanguard.ai.inference

import java.util.Locale

/**
 * The deterministic data of a species or combined report: the markdown tables the writer model is
 * asked to point at with `[TABLE: key]` placeholders, and the text that tells it what is confirmed.
 * Everything here is rendered from [SpeciesReportContext]; the model never writes a table.
 */
class SpeciesReportBundle(
    /** The "confirmed data" the writer reads: every table, in the report's language. */
    val text: String,
    /** Table or bullet block by placeholder key. */
    val tables: Map<String, String>,
) {
    /** Every number in the data, as the validator compares it: the only figures the prose may repeat. */
    val allowedNumbers: Set<String> by lazy { numberTokens(text) }

    companion object {
        private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

        /** Numbers of [text] without leading zeros and with a dot as the decimal mark. */
        fun numberTokens(text: String): Set<String> =
            NUMBER.findAll(text).map { normalizeNumber(it.value) }.toSet()

        fun normalizeNumber(raw: String): String {
            val dotted = raw.replace(',', '.')
            val trimmed = dotted.trimStart('0')
            return if (trimmed.isEmpty() || trimmed.startsWith('.')) "0$trimmed" else trimmed
        }

        fun build(ctx: SpeciesReportContext): SpeciesReportBundle = Builder(ctx).build()
    }

    private class Builder(val ctx: SpeciesReportContext) {
        val lang = SpeciesReportText.normalize(ctx.language)
        val tables = LinkedHashMap<String, String>()

        fun w(key: String) = SpeciesReportText.word(key, lang)

        fun build(): SpeciesReportBundle {
            if (ctx.type.hasDebris) debrisTables()
            speciesTotals()
            speciesList()
            groups()
            threatened()
            outOfRange()
            if (ctx.type == ReportType.SPECIES) locations()
            if (ctx.type == ReportType.COMBINED) cooccurrence()
            val text = buildString {
                append("## CONFIRMED DATA — USE ONLY THESE SPECIES AND NUMBERS\n\n")
                append("SPECIES OBSERVED (closed list): ")
                append(ctx.species.joinToString("; ") { "${it.scientificName} (${it.commonName})" }.ifEmpty { w("none") })
                append("\n\n")
                tables.forEach { (key, block) -> append("### ").append(key).append("\n").append(block).append("\n\n") }
            }
            return SpeciesReportBundle(text, tables)
        }

        fun debrisTables() {
            val debris = ctx.debris ?: return
            val canon = ToolDataBundleFormatter.formatWithCanon(debris).second
            for (key in listOf("totals", "material", "type", "risk")) {
                canon.tablesByKey[key]?.takeIf { it.isNotBlank() }?.let { tables["debris_$key"] = it.trim() }
            }
        }

        fun speciesTotals() {
            val period = when {
                ctx.startMs != null || ctx.endMs != null ->
                    "${ctx.startMs?.let { SpeciesReportContext.isoDate(it) } ?: "…"} – ${ctx.endMs?.let { SpeciesReportContext.isoDate(it) } ?: "…"}"
                else -> null
            }
            tables["sp_totals"] = buildString {
                append("- ${w("totalObs")}: **${ctx.totalObservations}**\n")
                append("- ${w("totalPhotos")}: **${ctx.photoCount}**\n")
                append("- ${w("totalSpecies")}: **${ctx.species.size}**\n")
                append("- ${w("totalGroups")}: **${ctx.groups.size}**\n")
                append("- ${w("totalThreatened")}: **${ctx.threatened.size}**\n")
                append("- ${w("totalOut")}: **${ctx.outOfRange.size}**\n")
                append("- ${w("totalLocated")}: **${ctx.locatedObservations}**\n")
                if (period != null) append("- ${w("period")}: **$period**\n")
            }.trim()
        }

        fun speciesList() {
            val rows = ctx.species.take(OceanGuardSpeciesTools.MAX_LISTED)
            tables["sp_list"] = table(
                listOf(w("species"), w("scientific"), w("group"), w("iucn"), w("observations"), w("photos"), w("outOfRange"), w("confidence"), w("firstSeen"), w("lastSeen")),
                rows.map {
                    listOf(
                        it.commonName, it.scientificName, groupLabel(it.group), it.iucn ?: "—", it.observations.toString(),
                        it.photos.toString(), it.outOfRange.toString(), it.avgConfidencePercent.toString(),
                        SpeciesReportContext.isoDate(it.firstSeenMs), SpeciesReportContext.isoDate(it.lastSeenMs),
                    )
                },
            )
        }

        fun groups() {
            tables["sp_groups"] = table(
                listOf(w("group"), w("speciesCount"), w("observations")),
                ctx.groups.map { listOf(SpeciesReportText.groupName(it.group, lang), it.species.toString(), it.observations.toString()) },
            )
        }

        fun threatened() {
            tables["sp_threatened"] = table(
                listOf(w("species"), w("scientific"), w("iucn"), w("observations")),
                ctx.threatened.map { listOf(it.commonName, it.scientificName, it.iucn.orEmpty(), it.observations.toString()) },
            )
        }

        fun outOfRange() {
            tables["sp_outofrange"] = table(
                listOf(w("species"), w("scientific"), w("outOfRange"), w("observations")),
                ctx.outOfRange.map { listOf(it.commonName, it.scientificName, it.outOfRange.toString(), it.observations.toString()) },
            )
        }

        fun locations() {
            tables["sp_locations"] = table(
                listOf(w("id"), w("scientific"), w("lat"), w("lon"), w("date")),
                ctx.locations.map {
                    listOf("#${it.observationId}", it.scientificName, coord(it.lat), coord(it.lon), SpeciesReportContext.isoDate(it.dateMs))
                },
            )
        }

        fun cooccurrence() {
            tables["co_occurrence"] = table(
                listOf(w("session"), w("date"), w("lat"), w("lon"), w("debrisItems"), w("health"), w("nearby")),
                ctx.hotspots.map { h ->
                    listOf(
                        "#${h.sessionId}", SpeciesReportContext.isoDate(h.dateMs), coord(h.lat), coord(h.lon),
                        h.debrisCount.toString(), h.healthScore.toString(),
                        h.nearbySpecies.joinToString("; ") { (n, c) -> "$n ($c)" }.ifEmpty { w("none") },
                    )
                },
            )
        }

        fun groupLabel(group: String?) = SpeciesReportText.groupName(group ?: SpeciesReportContext.UNKNOWN_GROUP, lang)

        fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

        /** A markdown table, or a bullet "none" when there are no rows so the section never stays empty. */
        fun table(header: List<String>, rows: List<List<String>>): String {
            if (rows.isEmpty()) return "- ${w("none")}"
            return buildString {
                append("| ").append(header.joinToString(" | ")).append(" |\n")
                append("|").append(header.joinToString("|") { "---" }).append("|\n")
                rows.forEach { r -> append("| ").append(r.joinToString(" | ") { it.replace("|", "/") }).append(" |\n") }
            }.trim()
        }
    }
}
