package com.oceanguard.ai.inference

import android.util.Log
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tool-calling generator of the species and combined reports, the counterpart of
 * [ToolReportGenerator] for biodiversity. Same two phases: the model dispatches the typed tools of
 * [OceanGuardSpeciesTools] (or [OceanGuardCombinedTools]), then a fresh conversation writes the
 * prose with the deterministic bundle in view and `[TABLE: key]` placeholders where tables go.
 * [SpeciesReportAssembler] swaps the tables in and the guard removes what the data does not support.
 */
class SpeciesReportGenerator(private val engine: LiteRTTextEngine) {

    suspend fun generate(
        ctx: SpeciesReportContext,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit = {},
        onToolCallProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        onPhaseChanged: (ReportPhase) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(ctx.hasData) { "Cannot generate a ${ctx.type} report without data" }
        val bundle = SpeciesReportBundle.build(ctx)
        val required = requiredTools(ctx.type)
        val tools: ToolSet = if (ctx.type == ReportType.COMBINED) OceanGuardCombinedTools(ctx) else OceanGuardSpeciesTools(ctx)
        val system = systemMessage(ctx)
        Log.i(TAG, "${ctx.type} report: ${ctx.totalObservations} observations, ${ctx.species.size} species, bundle=${bundle.text.length} chars")

        onPhaseChanged(ReportPhase.PHASE_1_TOOLS)
        val seen = LinkedHashSet<String>()
        engine.generateWithTools(
            prompt = phase1Prompt(ctx),
            toolSet = tools,
            systemMessage = system,
            maxToolRounds = MAX_TOOL_ROUNDS,
            requiredToolNames = required,
            dataBundle = bundle.text,
            onPartialResult = { /* PHASE 1 prose is throwaway */ },
            onToolCallStarted = { name ->
                onToolCallStarted(name)
                if (seen.add(name)) onToolCallProgress(seen.size, required.size, name)
            },
        )

        onPhaseChanged(ReportPhase.PHASE_2_PROSE)
        val raw = engine.generateText(
            prompt = writerPrompt(ctx, bundle),
            maxTokens = MAX_OUTPUT_TOKENS,
            systemMessage = writerSystem(ctx),
            onPartialResult = onPartialResult,
        )
        val text = SpeciesReportAssembler.assemble(raw, ctx, bundle)
        onPartialResult(text)
        text
    }

    companion object {
        private const val TAG = "SpeciesReportGenerator"
        private const val MAX_TOOL_ROUNDS = 14
        private const val MAX_OUTPUT_TOKENS = 2600

        private val SPECIES_TOOLS = setOf(
            "get_species_summary", "get_species_list", "get_group_breakdown",
            "get_threatened_species", "get_out_of_range_species",
        )

        /** Tools the model must call before writing; matched through [ToolNames.canonical]. */
        fun requiredTools(type: ReportType): Set<String> = when (type) {
            ReportType.COMBINED -> SPECIES_TOOLS + setOf(
                "get_debris_summary", "get_material_breakdown", "get_type_breakdown", "get_risk_assessment",
                "get_debris_species_cooccurrence",
            )
            else -> SPECIES_TOOLS + "get_species_locations"
        }

        private val LANGUAGE_NAMES = mapOf(
            "en" to "English", "es" to "Spanish (Español)", "fr" to "French (Français)",
            "de" to "German (Deutsch)", "it" to "Italian (Italiano)", "pt" to "Portuguese (Português)",
        )

        internal fun persona(audience: ReportAudience): String = when (audience) {
            ReportAudience.SCIENTIFIC ->
                "You are a senior marine biologist writing a field biodiversity assessment. Use scientific nomenclature and a rigorous tone."
            ReportAudience.NGO_MANAGER ->
                "You are a senior conservation consultant preparing an operational assessment for a coastal NGO."
            ReportAudience.CITIZEN ->
                "You are a marine educator writing for citizen scientists, divers and the public, in accessible language."
        }

        internal fun structure(ctx: SpeciesReportContext): String {
            val lang = SpeciesReportText.normalize(ctx.language)
            return SpeciesReportAssembler.sections(ctx.type).withIndex().joinToString("\n") { (i, s) ->
                val tokens = s.tables.joinToString("  ") { "[TABLE: $it]" }.ifEmpty { "(no table; prose only)" }
                "  ${i + 1}. ## ${SpeciesReportText.word(s.headingKey, lang)}  ->  $tokens"
            }
        }

        private fun kindLabel(type: ReportType) =
            if (type == ReportType.COMBINED) "combined marine debris and species" else "marine species"

        internal fun systemMessage(ctx: SpeciesReportContext): String {
            val language = LANGUAGE_NAMES[SpeciesReportText.normalize(ctx.language)]
            val tools = requiredTools(ctx.type).joinToString(", ")
            return """
OUTPUT LANGUAGE: $language (headings and prose, every word).

${persona(ctx.audience)}

PROTOCOL:
  Step 1: invoke EVERY one of these tools ONCE, with empty arguments, before writing any prose: $tools. Emit only tool calls in this step.
  Step 2: when the last tool has returned, write the ${kindLabel(ctx.type)} report using ONLY the species, numbers and rows the tools returned.

RULES:
  - CLOSED WORLD: mention only species that appear in the tool results. Never add a species from memory, even a very likely one.
  - Never state a number that is not in the tool results.
${if (ctx.type == ReportType.COMBINED) "  - NO CAUSALITY: debris and species are reported side by side. Never say that debris causes, explains or affects the presence, absence or condition of a species.\n" else ""}
STRUCTURE (## headings in $language, in order):
${structure(ctx)}
""".trimIndent()
        }

        internal fun writerSystem(ctx: SpeciesReportContext): String {
            val language = LANGUAGE_NAMES[SpeciesReportText.normalize(ctx.language)]
            return """
OUTPUT LANGUAGE: $language (headings and prose, every word).

${persona(ctx.audience)}

RULES (your output is post-processed):
  - TABLES: never write them. Where a table belongs, write its placeholder on its own line, in English, verbatim, once: for example [TABLE: sp_list]. The post-processor inserts the real table.
  - PROSE IS QUALITATIVE ONLY: write no digits at all (no counts, percentages, dates, coordinates, years). Point to the table instead ("see the table below"). Comparative words are fine ("most", "few", "dominant").
  - CLOSED WORLD: name only species of the CONFIRMED DATA, using the names written there. If a section has no data (the table says none), say so in one sentence.
${if (ctx.type == ReportType.COMBINED) "  - NO CAUSALITY: report debris and species side by side. Never claim that one causes, explains, attracts or harms the other; you may say that both were recorded in the same places.\n" else ""}  - Use `## Heading` headings exactly as in STRUCTURE. 2-4 sentences of prose per section, then its placeholder(s).

STRUCTURE (## headings in $language, in order):
${structure(ctx)}
""".trimIndent()
        }

        internal fun phase1Prompt(ctx: SpeciesReportContext): String = """
Producing the ${kindLabel(ctx.type)} report in ${LANGUAGE_NAMES[SpeciesReportText.normalize(ctx.language)]}.

STEP 1 NOW: invoke every required tool listed in the system message ONCE each, with empty arguments. Emit only tool calls in this turn.
""".trimIndent()

        internal fun writerPrompt(ctx: SpeciesReportContext, bundle: SpeciesReportBundle): String = """
You are writing the body of a ${kindLabel(ctx.type)} report.

${bundle.text}

Write the report as prose with markdown headings and the placeholders from STRUCTURE. Do not write any table rows and do not write any digit.
""".trimIndent()
    }
}
