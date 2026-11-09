package com.oceanguard.ai.data.species

import android.content.res.Resources
import androidx.annotation.StringRes
import com.oceanguard.ai.R

/** Localised names of the catalogue `group` keys shown by the group fallback of the species result. */
object SpeciesGroups {

    private val LABELS: Map<String, Int> = mapOf(
        "bony_fish" to R.string.species_group_bony_fish,
        "sharks_rays" to R.string.species_group_sharks_rays,
        "turtles" to R.string.species_group_turtles,
        "mammals" to R.string.species_group_mammals,
        "cephalopods" to R.string.species_group_cephalopods,
        "other_molluscs" to R.string.species_group_other_molluscs,
        "crustaceans" to R.string.species_group_crustaceans,
        "echinoderms" to R.string.species_group_echinoderms,
        "corals_anemones" to R.string.species_group_corals_anemones,
        "jellyfish_hydrozoans" to R.string.species_group_jellyfish_hydrozoans,
        "sponges" to R.string.species_group_sponges,
        "polychaetes" to R.string.species_group_polychaetes,
        "ascidians" to R.string.species_group_ascidians,
        "seagrasses" to R.string.species_group_seagrasses,
        "algae" to R.string.species_group_algae,
    )

    /** The keys that have a localised name. */
    val keys: Set<String> get() = LABELS.keys

    @StringRes
    fun labelRes(key: String): Int? = LABELS[key]

    /** Localised group name; a readable form of the key for a group this app version does not know. */
    fun name(resources: Resources, key: String): String =
        LABELS[key]?.let(resources::getString) ?: key.replace('_', ' ')

    /** "Possible group: Sharks and rays". */
    fun title(resources: Resources, key: String): String =
        resources.getString(R.string.species_group_title, name(resources, key))
}
