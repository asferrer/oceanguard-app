package com.oceanguard.ai.inference

import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.data.species.entryFor

/**
 * Checks a species or combined report against the facts it was written from: it may name only the
 * species observed, repeat only figures of the data, and (combined) never state a cause.
 *
 * [validate] scores a finished text like [ReportValidator] does for debris reports.
 * [removeUnsupported] is the guard that runs before a report is saved: it drops every sentence
 * that names an unobserved species, repeats a figure the tools never returned, or claims a cause,
 * so an invented fact never reaches the reader.
 */
object SpeciesReportValidator {

    private val ITALIC_BINOMIAL = Regex("""[*_]([A-Z][a-z]{2,} [a-z]{3,})[*_]""")
    private val PLAIN_BINOMIAL = Regex("""\b([A-Z][a-z]{3,}) ([a-z]{4,})\b""")
    private val LIST_MARKER = Regex("""^\s*(?:[-*]\s+)?\d+[.)]\s+""", RegexOption.MULTILINE)
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
    private val PLACEHOLDER = Regex("""\[TABLE[^\]]*]|\[[A-Za-zÀ-ÿ_ ]{2,}]""")
    private const val MIN_NAME_LENGTH = 4

    private val CAUSAL = mapOf(
        "en" to listOf("caused by", "because of", "due to", "as a result of", "leads to", "led to", "responsible for", "is the reason", "explains why"),
        "es" to listOf("causad", "debido a", "a causa de", "provoca", "provocad", "por culpa", "como resultado de", "responsable de", "explica por qué"),
        "fr" to listOf("causé", "à cause", "en raison de", "dû à", "due à", "provoque", "responsable de", "explique pourquoi"),
        "de" to listOf("verursacht", "wegen", "aufgrund", "infolge", "führt zu", "verantwortlich", "erklärt, warum"),
        "it" to listOf("causat", "a causa di", "dovut", "provoca", "responsabile di", "spiega perché"),
        "pt" to listOf("causad", "devido a", "por causa", "provoca", "responsável por", "explica por que"),
    )

    fun validate(text: String, ctx: SpeciesReportContext, bundle: SpeciesReportBundle): ValidationResult {
        if (!ctx.hasData) {
            return ValidationResult(0, listOf(ValidationCheck("source_data", CheckStatus.FAIL, "No source data provided")))
        }
        if (text.isBlank()) {
            return ValidationResult(0, listOf(ValidationCheck("report_text", CheckStatus.FAIL, "Generated report is empty")))
        }
        val checks = listOf(
            speciesCheck(text, ctx),
            numbersCheck(text, bundle),
            causalCheck(text, ctx),
            placeholderCheck(text),
            coverageCheck(text, ctx),
        )
        val score = (100 - 25 * checks.count { it.status == CheckStatus.FAIL } - 8 * checks.count { it.status == CheckStatus.WARNING })
            .coerceIn(0, 100)
        return ValidationResult(score, checks)
    }

    /** Species named in [text] that were not observed: catalogue names and italic binomials. */
    fun inventedSpecies(text: String, ctx: SpeciesReportContext): List<String> {
        val observed = observedNames(ctx)
        var masked = " " + text.lowercase() + " "
        observed.sortedByDescending { it.length }.forEach { masked = masked.replace(it, " ".repeat(it.length)) }
        val found = LinkedHashSet<String>()
        for (name in unobservedCatalogueNames(ctx.catalog, observed)) {
            if (Regex("""(?<![\p{L}])${Regex.escape(name)}(?![\p{L}])""").containsMatchIn(masked)) found += name
        }
        ITALIC_BINOMIAL.findAll(text).map { it.groupValues[1].lowercase() }
            .filter { it !in observed && observed.none { o -> o.contains(it) } }
            .forEach { found += it }
        val genera = ctx.catalog.all().map { it.scientificName.substringBefore(' ').lowercase() }.toSet()
        PLAIN_BINOMIAL.findAll(text).forEach { m ->
            val full = m.value.lowercase()
            if (m.groupValues[1].lowercase() in genera && full !in observed) found += full
        }
        return found.toList()
    }

    /** Figures of [text] (outside list numbering) that the data never states. */
    fun unsupportedNumbers(text: String, bundle: SpeciesReportBundle): List<String> =
        SpeciesReportBundle.numberTokens(LIST_MARKER.replace(text, " "))
            .filter { it !in bundle.allowedNumbers }

    /**
     * The report without any sentence that names an unobserved species, states a figure the data
     * does not contain or (combined) claims a cause. Table rows, headings and bullet lines of the
     * deterministic data are kept as they are: they come from the bundle, not from the model.
     */
    fun removeUnsupported(text: String, ctx: SpeciesReportContext, bundle: SpeciesReportBundle): String {
        val kept = text.lines().map { line ->
            val trimmed = line.trim()
            val deterministic = trimmed.startsWith("- ") && trimmed.contains("**")
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("|") || deterministic) {
                line
            } else {
                line.split(SENTENCE_END).filter { sentenceSupported(it, ctx, bundle) }.joinToString(" ")
            }
        }
        return kept.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun sentenceSupported(sentence: String, ctx: SpeciesReportContext, bundle: SpeciesReportBundle): Boolean =
        inventedSpecies(sentence, ctx).isEmpty() &&
            unsupportedNumbers(sentence, bundle).isEmpty() &&
            !(ctx.type == ReportType.COMBINED && isCausal(sentence, ctx.language))

    private fun speciesCheck(text: String, ctx: SpeciesReportContext): ValidationCheck {
        val invented = inventedSpecies(text, ctx)
        return if (invented.isEmpty()) {
            ValidationCheck("species_closed_world", CheckStatus.PASS, "Only observed species are named")
        } else {
            ValidationCheck("species_closed_world", CheckStatus.FAIL, "Species not observed: ${invented.joinToString()}")
        }
    }

    private fun numbersCheck(text: String, bundle: SpeciesReportBundle): ValidationCheck {
        val extra = unsupportedNumbers(text, bundle)
        return if (extra.isEmpty()) {
            ValidationCheck("numbers_from_tools", CheckStatus.PASS, "Every figure comes from the data")
        } else {
            ValidationCheck("numbers_from_tools", CheckStatus.FAIL, "Figures not in the data: ${extra.joinToString()}")
        }
    }

    private fun causalCheck(text: String, ctx: SpeciesReportContext): ValidationCheck {
        if (ctx.type != ReportType.COMBINED) return ValidationCheck("no_causality", CheckStatus.PASS, "Not a combined report")
        val claims = text.split(SENTENCE_END).filter { isCausal(it, ctx.language) }
        return if (claims.isEmpty()) {
            ValidationCheck("no_causality", CheckStatus.PASS, "No causal claim between debris and species")
        } else {
            ValidationCheck("no_causality", CheckStatus.FAIL, "Causal claim: ${claims.first().trim().take(120)}")
        }
    }

    private fun placeholderCheck(text: String): ValidationCheck =
        if (PLACEHOLDER.containsMatchIn(text)) {
            ValidationCheck("placeholders", CheckStatus.FAIL, "Unresolved placeholder in the text")
        } else {
            ValidationCheck("placeholders", CheckStatus.PASS, "No placeholders")
        }

    /** Every observed species appears somewhere (the table counts), or the report is incomplete. */
    private fun coverageCheck(text: String, ctx: SpeciesReportContext): ValidationCheck {
        val lower = text.lowercase()
        val missing = ctx.species.take(OceanGuardSpeciesTools.MAX_LISTED).filter { it.scientificName.lowercase() !in lower }
        return if (missing.isEmpty()) {
            ValidationCheck("species_coverage", CheckStatus.PASS, "Every observed species is listed")
        } else {
            ValidationCheck("species_coverage", CheckStatus.WARNING, "Not listed: ${missing.joinToString { it.scientificName }}")
        }
    }

    private fun isCausal(sentence: String, language: String): Boolean {
        val lower = sentence.lowercase()
        return (CAUSAL[SpeciesReportText.normalize(language)] ?: emptyList()).any { lower.contains(it) }
    }

    /** Lowercase names of the species observed: binomial and every common name of its catalogue entry. */
    private fun observedNames(ctx: SpeciesReportContext): Set<String> = buildSet {
        ctx.observations.forEach { o -> addEntryNames(o, ctx.catalog) }
        ctx.species.forEach { add(it.scientificName.lowercase()); add(it.commonName.lowercase()) }
    }

    private fun MutableSet<String>.addEntryNames(o: SpeciesObservation, catalog: SpeciesCatalog) {
        add(o.scientificName.lowercase())
        catalog.entryFor(o)?.commonNames?.values?.forEach { add(it.lowercase()) }
    }

    private var cachedCatalog: SpeciesCatalog? = null
    private var cachedSize = -1
    private var cachedNames: List<String> = emptyList()

    @Synchronized
    private fun unobservedCatalogueNames(catalog: SpeciesCatalog, observed: Set<String>): List<String> {
        if (cachedCatalog !== catalog || cachedSize != catalog.size()) {
            cachedCatalog = catalog
            cachedSize = catalog.size()
            cachedNames = catalog.all().flatMap { e -> listOf(e.scientificName) + e.commonNames.values }
                .map { it.lowercase().trim() }.filter { it.length >= MIN_NAME_LENGTH }.distinct()
        }
        return cachedNames.filter { it !in observed }
    }
}
