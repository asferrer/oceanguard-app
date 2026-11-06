package com.oceanguard.ai.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.BottomBarTab

/** Route names used from more than one file. Routes private to an area stay in its route file. */
object OceanRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MAP = "map"
    const val REPORTS = "reports"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CAMERA = "camera"
    const val CONTINUOUS_SCAN = "live_detection"
    const val CAMERA_RESULT = "camera_result"
    const val SPECIES_RESULT = "species_result"

    /** Without `?` arguments: the form to compare routes. */
    fun base(route: String?): String? = route?.substringBefore("?")
}

/** Ordered tabs of the bottom bar. The order gives the direction of the shared axis. */
val OCEAN_BOTTOM_TABS = listOf(
    BottomBarTab(OceanRoutes.HOME, R.string.nav_home, Icons.Filled.Home),
    BottomBarTab(OceanRoutes.MAP, R.string.nav_map, Icons.Filled.Map),
    BottomBarTab(OceanRoutes.REPORTS, R.string.nav_reports, Icons.Filled.Analytics),
    BottomBarTab(OceanRoutes.HISTORY, R.string.nav_history, Icons.Filled.History),
    BottomBarTab(OceanRoutes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

private val TAB_ROUTES = listOf(
    OceanRoutes.HOME, OceanRoutes.MAP, OceanRoutes.REPORTS, OceanRoutes.HISTORY, OceanRoutes.SETTINGS,
)

/** Screens drawn under the status bar: they apply their own insets. */
private val IMMERSIVE_ROUTES = setOf(
    OceanRoutes.CAMERA,
    OceanRoutes.CONTINUOUS_SCAN,
    OceanRoutes.CAMERA_RESULT,
    OceanRoutes.SPECIES_RESULT,
)

/** Plain function of the routes, so the motion of each navigation can be unit tested. */
enum class NavMotion {
    /** Tab to a tab further right. */
    AXIS_FORWARD,
    /** Tab to a tab further left. */
    AXIS_BACKWARD,
    /** Into a detail, or back out of it. */
    DEPTH,
    /** Splash, onboarding and the camera, which has its own reveal. */
    DISSOLVE,
}

object OceanRouteRules {

    fun isTab(route: String?): Boolean = OceanRoutes.base(route) in TAB_ROUTES

    fun isImmersive(route: String?): Boolean = OceanRoutes.base(route) in IMMERSIVE_ROUTES

    fun motionBetween(from: String?, to: String?): NavMotion {
        val fromBase = OceanRoutes.base(from)
        val toBase = OceanRoutes.base(to)
        val fromTab = TAB_ROUTES.indexOf(fromBase)
        val toTab = TAB_ROUTES.indexOf(toBase)
        return when {
            fromBase in DISSOLVE_ROUTES || toBase in DISSOLVE_ROUTES -> NavMotion.DISSOLVE
            fromTab >= 0 && toTab >= 0 && toTab >= fromTab -> NavMotion.AXIS_FORWARD
            fromTab >= 0 && toTab >= 0 -> NavMotion.AXIS_BACKWARD
            else -> NavMotion.DEPTH
        }
    }

    private val DISSOLVE_ROUTES = setOf(
        OceanRoutes.SPLASH, OceanRoutes.ONBOARDING, OceanRoutes.CAMERA,
    )
}
