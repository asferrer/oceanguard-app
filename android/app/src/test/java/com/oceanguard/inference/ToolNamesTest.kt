package com.oceanguard.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNamesTest {

    @Test fun allSpellingsOfAToolReduceToOneForm() {
        val spellings = listOf("per-session-details", "get_per_session_details", "getPerSessionDetails", "Per Session Details")
        assertEquals(setOf("per_session_details"), spellings.map { ToolNames.canonical(it) }.toSet())
    }

    @Test fun everyRequiredNameMatchesItsSdkName() {
        val sdk = listOf(
            "debris-summary", "material-breakdown", "type-breakdown", "risk-assessment",
            "ecological-impacts", "survey-statistics", "per-session-details", "temporal-trend",
        )
        val required = listOf(
            "get_debris_summary", "get_material_breakdown", "get_type_breakdown", "get_risk_assessment",
            "get_ecological_impacts", "get_survey_statistics", "get_per_session_details", "get_temporal_trend",
        )
        assertEquals(ToolNames.canonical(required), ToolNames.canonical(sdk))
    }
}
