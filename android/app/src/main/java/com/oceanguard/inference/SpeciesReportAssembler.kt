package com.oceanguard.ai.inference

import android.util.Log

/** One section of a species or combined report: its heading word and the tables that follow its prose. */
internal data class ReportSection(val headingKey: String, val tables: List<String>)

/**
 * Turns what the writer model produced into the final report: its own tables are dropped, each
 * `[TABLE: key]` placeholder is replaced by the deterministic table of the bundle, tables the model
 * forgot are appended, and the guard removes every sentence the data does not support.
 */
internal object SpeciesReportAssembler {

    private const val TAG = "SpeciesReportAssembler"
    private val PLACEHOLDER = Regex("""\[TABLE:\s*([a-zA-Z_]+)\s*]""")

    fun sections(type: ReportType): List<ReportSection> = when (type) {
        ReportType.COMBINED -> listOf(
            ReportSection("hCombinedSummary", listOf("debris_totals", "sp_totals")),
            ReportSection("hDebris", listOf("debris_material", "debris_type", "debris_risk")),
            ReportSection("hBiodiversity", listOf("sp_list", "sp_groups", "sp_threatened", "sp_outofrange")),
            ReportSection("hTogether", listOf("co_occurrence")),
            ReportSection("hCombinedConclusions", emptyList()),
        )
        else -> listOf(
            ReportSection("hSpeciesSummary", listOf("sp_totals")),
            ReportSection("hSpeciesList", listOf("sp_list")),
            ReportSection("hGroups", listOf("sp_groups")),
            ReportSection("hThreatened", listOf("sp_threatened")),
            ReportSection("hOutOfRange", listOf("sp_outofrange")),
            ReportSection("hLocations", listOf("sp_locations")),
            ReportSection("hConclusions", emptyList()),
        )
    }

    fun assemble(raw: String, ctx: SpeciesReportContext, bundle: SpeciesReportBundle): String {
        val stripped = ToolReportGenerator.stripModelWrittenTables(raw)
        val substituted = substitute(stripped, ctx.type, bundle)
        val guarded = SpeciesReportValidator.removeUnsupported(substituted, ctx, bundle)
        if (guarded != substituted) Log.w(TAG, "Guard removed unsupported sentences (${substituted.length} -> ${guarded.length} chars)")
        return guarded
    }

    /** Placeholders replaced once each; unknown ones dropped; missing tables appended (bullet totals excepted). */
    internal fun substitute(text: String, type: ReportType, bundle: SpeciesReportBundle): String {
        val seen = mutableSetOf<String>()
        val first = PLACEHOLDER.replace(text) { m ->
            val key = m.groupValues[1].trim().lowercase()
            val block = bundle.tables[key]
            if (key in seen || block.isNullOrBlank()) {
                ""
            } else {
                seen += key
                "\n\n" + block.trim() + "\n\n"
            }
        }
        val missing = sections(type).flatMap { it.tables }
            .filter { it !in seen && !it.endsWith("totals") && bundle.tables[it]?.isNotBlank() == true }
        val body = if (missing.isEmpty()) first else first.trimEnd() + "\n\n" + missing.joinToString("\n\n") { bundle.tables.getValue(it) }
        return body.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** The report without a model: localised headings and the deterministic tables. */
    fun template(ctx: SpeciesReportContext, bundle: SpeciesReportBundle): String {
        val lang = SpeciesReportText.normalize(ctx.language)
        return sections(ctx.type).filter { it.tables.isNotEmpty() }.joinToString("\n\n") { s ->
            "## ${SpeciesReportText.word(s.headingKey, lang)}\n\n" +
                s.tables.mapNotNull { bundle.tables[it] }.joinToString("\n\n")
        }
    }
}
