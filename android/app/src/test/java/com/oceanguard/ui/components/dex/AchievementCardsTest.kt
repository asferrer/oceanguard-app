package com.oceanguard.ai.ui.components.dex

import com.oceanguard.ai.data.collection.ACHIEVEMENT_TOTAL
import com.oceanguard.ai.data.collection.ACHIEVEMENT_DEF_MAP
import com.oceanguard.ai.data.collection.ALL_ACHIEVEMENTS
import com.oceanguard.ai.data.collection.AchievementCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementCardsTest {

    @Test fun fractionIsClampedAndSafeForAnEmptyTarget() {
        assertEquals(0f, achievementFraction(3, 0), 0f)
        assertEquals(0.5f, achievementFraction(5, 10), 0.0001f)
        assertEquals(1f, achievementFraction(40, 10), 0f)
        assertEquals(0f, achievementFraction(-2, 10), 0f)
    }

    @Test fun theTotalShownIsTheNumberDefined() {
        assertEquals(32, ACHIEVEMENT_TOTAL)
        assertEquals(ALL_ACHIEVEMENTS.size, ACHIEVEMENT_TOTAL)
    }

    @Test fun idsAreUnique() {
        assertEquals(ALL_ACHIEVEMENTS.size, ACHIEVEMENT_DEF_MAP.size)
    }

    @Test fun theMetaAchievementCountsEveryOtherOne() {
        assertEquals(ACHIEVEMENT_TOTAL - 1, ACHIEVEMENT_DEF_MAP.getValue("all_achievements").target)
    }

    @Test fun everyCategoryHasAtLeastOneAchievementToCelebrate() {
        AchievementCategory.entries.forEach { category ->
            assertTrue(category.name, ALL_ACHIEVEMENTS.any { it.category == category })
        }
    }
}
