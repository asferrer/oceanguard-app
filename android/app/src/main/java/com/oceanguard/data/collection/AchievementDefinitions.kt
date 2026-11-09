package com.oceanguard.ai.data.collection

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ScubaDiving
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.oceanguard.ai.R

/**
 * Static definition of an achievement (display metadata).
 * Separate from the Room [Achievement] entity which holds mutable state.
 *
 * [nameRes] and [descriptionRes] are string resource IDs so this data
 * class can be defined outside of a Composable context.
 */
data class AchievementDef(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val target: Int,
    val icon: ImageVector,
    val category: AchievementCategory,
)

enum class AchievementCategory(@param:StringRes val labelRes: Int) {
    FIRST_STEPS(R.string.achievement_category_first_steps),
    EXPLORER(R.string.achievement_category_explorer),
    COLLECTOR(R.string.achievement_category_collector),
    BIODEX(R.string.achievement_category_biodex),
    IMPACT(R.string.achievement_category_impact),
    RESEARCH(R.string.achievement_category_research),
    ELITE(R.string.achievement_category_elite),
}

/** Every achievement except the meta one, in display order; report_10 must stay last. */
private val REGULAR_ACHIEVEMENTS: List<AchievementDef> = listOf(
    // --- First Steps (6) ---
    AchievementDef("first_scan", R.string.achievement_name_first_scan, R.string.achievement_desc_first_scan, 1, Icons.Filled.Explore, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_live", R.string.achievement_name_first_live, R.string.achievement_desc_first_live, 1, Icons.Filled.Videocam, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_report", R.string.achievement_name_first_report, R.string.achievement_desc_first_report, 1, Icons.Filled.Description, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_batch", R.string.achievement_name_first_batch, R.string.achievement_desc_first_batch, 1, Icons.Filled.BurstMode, AchievementCategory.FIRST_STEPS),
    AchievementDef("first_dex_entry", R.string.achievement_name_first_dex_entry, R.string.achievement_desc_first_dex_entry, 1, Icons.AutoMirrored.Filled.MenuBook, AchievementCategory.FIRST_STEPS),
    AchievementDef("tutorial_complete", R.string.achievement_name_tutorial_complete, R.string.achievement_desc_tutorial_complete, 1, Icons.Filled.School, AchievementCategory.FIRST_STEPS),

    // --- Explorer (5) ---
    AchievementDef("scan_10", R.string.achievement_name_scan_10, R.string.achievement_desc_scan_10, 10, Icons.Filled.CleaningServices, AchievementCategory.EXPLORER),
    AchievementDef("scan_50", R.string.achievement_name_scan_50, R.string.achievement_desc_scan_50, 50, Icons.Filled.ScubaDiving, AchievementCategory.EXPLORER),
    AchievementDef("scan_100", R.string.achievement_name_scan_100, R.string.achievement_desc_scan_100, 100, Icons.Filled.Shield, AchievementCategory.EXPLORER),
    AchievementDef("scan_500", R.string.achievement_name_scan_500, R.string.achievement_desc_scan_500, 500, Icons.Filled.MilitaryTech, AchievementCategory.EXPLORER),
    AchievementDef("streak_7", R.string.achievement_name_streak_7, R.string.achievement_desc_streak_7, 7, Icons.Filled.LocalFireDepartment, AchievementCategory.EXPLORER),

    // --- MarineDex Collector (5) ---
    AchievementDef("dex_3", R.string.achievement_name_dex_3, R.string.achievement_desc_dex_3, 3, Icons.Filled.WaterDrop, AchievementCategory.COLLECTOR),
    AchievementDef("dex_6", R.string.achievement_name_dex_6, R.string.achievement_desc_dex_6, 6, Icons.Filled.Science, AchievementCategory.COLLECTOR),
    AchievementDef("dex_9", R.string.achievement_name_dex_9, R.string.achievement_desc_dex_9, 9, Icons.Filled.Waves, AchievementCategory.COLLECTOR),
    AchievementDef("dex_11", R.string.achievement_name_dex_11, R.string.achievement_desc_dex_11, 11, Icons.Filled.Star, AchievementCategory.COLLECTOR),
    AchievementDef("dex_favorite_3", R.string.achievement_name_dex_favorite_3, R.string.achievement_desc_dex_favorite_3, 3, Icons.Filled.Favorite, AchievementCategory.COLLECTOR),

    // --- BioDex species (7) ---
    AchievementDef("species_1", R.string.achievement_name_species_1, R.string.achievement_desc_species_1, 1, Icons.Filled.Eco, AchievementCategory.BIODEX),
    AchievementDef("species_5", R.string.achievement_name_species_5, R.string.achievement_desc_species_5, 5, Icons.Filled.Pets, AchievementCategory.BIODEX),
    AchievementDef("species_15", R.string.achievement_name_species_15, R.string.achievement_desc_species_15, 15, Icons.Filled.Biotech, AchievementCategory.BIODEX),
    AchievementDef("species_40", R.string.achievement_name_species_40, R.string.achievement_desc_species_40, 40, Icons.Filled.TravelExplore, AchievementCategory.BIODEX),
    AchievementDef("species_100", R.string.achievement_name_species_100, R.string.achievement_desc_species_100, 100, Icons.Filled.Star, AchievementCategory.BIODEX),
    AchievementDef("species_favorite_3", R.string.achievement_name_species_favorite_3, R.string.achievement_desc_species_favorite_3, 3, Icons.Filled.FavoriteBorder, AchievementCategory.BIODEX),
    AchievementDef("species_obs_50", R.string.achievement_name_species_obs_50, R.string.achievement_desc_species_obs_50, 50, Icons.Filled.Visibility, AchievementCategory.BIODEX),

    // --- Environmental Impact (3) ---
    AchievementDef("debris_50", R.string.achievement_name_debris_50, R.string.achievement_desc_debris_50, 50, Icons.Filled.Park, AchievementCategory.IMPACT),
    AchievementDef("debris_200", R.string.achievement_name_debris_200, R.string.achievement_desc_debris_200, 200, Icons.Filled.Rocket, AchievementCategory.IMPACT),
    AchievementDef("health_80", R.string.achievement_name_health_80, R.string.achievement_desc_health_80, 1, Icons.Filled.CalendarMonth, AchievementCategory.IMPACT),

    // --- Research Contribution (4) ---
    AchievementDef("contribute_1",   R.string.achievement_name_contribute_1,   R.string.achievement_desc_contribute_1,   1,   Icons.Filled.CloudUpload, AchievementCategory.RESEARCH),
    AchievementDef("contribute_10",  R.string.achievement_name_contribute_10,  R.string.achievement_desc_contribute_10,  10,  Icons.Filled.Backup,      AchievementCategory.RESEARCH),
    AchievementDef("contribute_50",  R.string.achievement_name_contribute_50,  R.string.achievement_desc_contribute_50,  50,  Icons.Filled.Hub,         AchievementCategory.RESEARCH),
    AchievementDef("contribute_100", R.string.achievement_name_contribute_100, R.string.achievement_desc_contribute_100, 100, Icons.Filled.Rocket,      AchievementCategory.RESEARCH),

    // --- Elite: report_10 (the meta achievement goes before it, see below) ---
    AchievementDef("report_10", R.string.achievement_name_report_10, R.string.achievement_desc_report_10, 10, Icons.Filled.EmojiEvents, AchievementCategory.ELITE),
)

/**
 * Meta achievement: it counts every other achievement, so its target is derived from the list
 * (total - 1) and never written by hand.
 */
private val META_ACHIEVEMENT = AchievementDef(
    "all_achievements", R.string.achievement_name_all_achievements, R.string.achievement_desc_all_achievements,
    REGULAR_ACHIEVEMENTS.size, Icons.Filled.WorkspacePremium, AchievementCategory.ELITE,
)

/**
 * Every achievement definition, grouped by category: 6 first steps, 5 explorer, 5 collector,
 * 7 BioDex species, 3 impact, 4 research and 2 elite, [ACHIEVEMENT_TOTAL] in all.
 */
val ALL_ACHIEVEMENTS: List<AchievementDef> =
    REGULAR_ACHIEVEMENTS.dropLast(1) + META_ACHIEVEMENT + REGULAR_ACHIEVEMENTS.last()

/** Number of achievements the user can unlock. The UI must show this, never a literal. */
val ACHIEVEMENT_TOTAL: Int = ALL_ACHIEVEMENTS.size

/** Quick lookup by id. */
val ACHIEVEMENT_DEF_MAP: Map<String, AchievementDef> = ALL_ACHIEVEMENTS.associateBy { it.id }
