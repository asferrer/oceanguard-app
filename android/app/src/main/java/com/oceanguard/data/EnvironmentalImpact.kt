package com.oceanguard.ai.data

import androidx.annotation.StringRes
import com.oceanguard.ai.R

/**
 * Environmental impact information for each debris type.
 *
 * Data sourced from NOAA Marine Debris Program and UNEP reports.
 */
data class DebrisImpactInfo(
    /** Canonical English text: it feeds the tools and the report translator, not the screens. */
    val degradationTime: String,
    @StringRes val degradationTimeRes: Int,
    val primaryRisk: String,
    @StringRes val primaryRiskRes: Int,
    val riskScore: Int,
    val icon: String,
    val annualVolumeOcean: String? = null,
    @StringRes val annualVolumeRes: Int? = null,
)

/**
 * Singleton object containing the environmental impact dictionary
 * for all debris types detected by OceanGuard AI.
 */
object EnvironmentalImpact {

    val IMPACT_MAP: Map<DebrisType, DebrisImpactInfo> = mapOf(
        DebrisType.BOTTLE to DebrisImpactInfo(
            degradationTime = "450+ years",
            degradationTimeRes = R.string.impact_degradation_450,
            primaryRisk = "Ingestion risk, microplastic fragmentation",
            primaryRiskRes = R.string.impact_risk_ingestion_fragmentation,
            riskScore = 8,
            icon = "\uD83C\uDF7E", // bottle
            annualVolumeOcean = "~1M tonnes/year",
            annualVolumeRes = R.string.impact_volume_1m,
        ),
        DebrisType.CAN to DebrisImpactInfo(
            degradationTime = "200+ years",
            degradationTimeRes = R.string.impact_degradation_200,
            primaryRisk = "Sharp edges, toxic chemical leaching",
            primaryRiskRes = R.string.impact_risk_sharp_toxic,
            riskScore = 6,
            icon = "\uD83E\uDD6B", // can
            annualVolumeOcean = "~500K tonnes/year",
            annualVolumeRes = R.string.impact_volume_500k,
        ),
        DebrisType.FISHING_NET to DebrisImpactInfo(
            degradationTime = "600+ years",
            degradationTimeRes = R.string.impact_degradation_600,
            primaryRisk = "Ghost fishing, entanglement of marine life",
            primaryRiskRes = R.string.impact_risk_ghost_fishing,
            riskScore = 10,
            icon = "\uD83D\uDD78\uFE0F", // spider web
            annualVolumeOcean = "~640K tonnes/year",
            annualVolumeRes = R.string.impact_volume_640k,
        ),
        DebrisType.GLOVE to DebrisImpactInfo(
            degradationTime = "100+ years",
            degradationTimeRes = R.string.impact_degradation_100,
            primaryRisk = "Ingestion risk, microplastic release",
            primaryRiskRes = R.string.impact_risk_ingestion_release,
            riskScore = 7,
            icon = "\uD83E\uDDE4", // glove
        ),
        DebrisType.MASK to DebrisImpactInfo(
            degradationTime = "450+ years",
            degradationTimeRes = R.string.impact_degradation_450,
            primaryRisk = "Entanglement of small marine organisms",
            primaryRiskRes = R.string.impact_risk_entanglement_small,
            riskScore = 7,
            icon = "\uD83D\uDE37", // face with mask
        ),
        DebrisType.METAL_DEBRIS to DebrisImpactInfo(
            degradationTime = "200-500 years",
            degradationTimeRes = R.string.impact_degradation_200_500,
            primaryRisk = "Sharp edges, chemical leaching, habitat disruption",
            primaryRiskRes = R.string.impact_risk_sharp_habitat,
            riskScore = 6,
            icon = "\u2699\uFE0F", // gear
        ),
        DebrisType.PLASTIC_DEBRIS to DebrisImpactInfo(
            degradationTime = "450+ years",
            degradationTimeRes = R.string.impact_degradation_450,
            primaryRisk = "Microplastic fragmentation, ingestion by marine fauna",
            primaryRiskRes = R.string.impact_risk_plastic_fragmentation,
            riskScore = 8,
            icon = "\uD83D\uDCA7", // droplet
            annualVolumeOcean = "~8M tonnes/year",
            annualVolumeRes = R.string.impact_volume_8m,
        ),
        DebrisType.TIRE to DebrisImpactInfo(
            degradationTime = "2000+ years",
            degradationTimeRes = R.string.impact_degradation_2000,
            primaryRisk = "Toxic chemicals (zinc, cadmium, heavy metals)",
            primaryRiskRes = R.string.impact_risk_toxic_chemicals,
            riskScore = 9,
            icon = "\uD83D\uDEDE", // wheel
        ),
        DebrisType.FABRIC_DEBRIS to DebrisImpactInfo(
            degradationTime = "1-200+ years",
            degradationTimeRes = R.string.impact_degradation_1_200,
            primaryRisk = "Microfiber release, entanglement",
            primaryRiskRes = R.string.impact_risk_microfiber,
            riskScore = 5,
            icon = "\uD83D\uDC55", // t-shirt
        ),
        DebrisType.GLASS_DEBRIS to DebrisImpactInfo(
            degradationTime = "1,000,000+ years",
            degradationTimeRes = R.string.impact_degradation_million,
            primaryRisk = "Physical injury to marine life",
            primaryRiskRes = R.string.impact_risk_physical_injury,
            riskScore = 4,
            icon = "\uD83E\uDE9F", // window \u2014 glass material
        ),
        DebrisType.OTHER to DebrisImpactInfo(
            degradationTime = "Variable",
            degradationTimeRes = R.string.impact_degradation_variable,
            primaryRisk = "Unknown environmental impact",
            primaryRiskRes = R.string.impact_risk_unknown,
            riskScore = 3,
            icon = "\u2753", // question mark
        ),
    )

    /**
     * Get the impact info for a given debris type,
     * falling back to OTHER if not found.
     */
    fun getImpact(type: DebrisType): DebrisImpactInfo {
        return IMPACT_MAP[type] ?: IMPACT_MAP[DebrisType.OTHER]!!
    }
}
