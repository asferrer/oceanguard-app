package com.oceanguard.ai.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.oceanguard.ai.ui.motion.OceanMotion

private typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

/**
 * Motion of the whole graph: shared axis X between tabs, depth into a detail, dissolve for splash,
 * onboarding and camera. All four directions run backwards under the predictive back gesture,
 * because Navigation seeks these same transitions. Under "Remove animations" only a fade remains.
 */
class OceanTransitions(private val reducedMotion: () -> Boolean) {

    private fun NavScope.motion(): NavMotion = OceanRouteRules.motionBetween(
        initialState.destination.route,
        targetState.destination.route,
    )

    private fun fadeInOnly(): EnterTransition = fadeIn(OceanMotion.reducedFade())

    private fun fadeOutOnly(): ExitTransition = fadeOut(OceanMotion.reducedFade())

    /** Enters from the side the user is moving towards, or from behind when going back. */
    private fun axisIn(side: Int): EnterTransition = fadeIn(
        OceanMotion.standard(OceanMotion.STAGGER_MS),
    ) + slideInHorizontally(OceanMotion.standard<IntOffset>(OceanMotion.STAGGER_MS)) { width ->
        (width * OceanMotion.AXIS_OFFSET_FRACTION * side).toInt()
    }

    private fun axisOut(side: Int): ExitTransition = fadeOut(OceanMotion.exit()) +
        slideOutHorizontally(OceanMotion.exit<IntOffset>()) { width ->
            (width * OceanMotion.AXIS_OFFSET_FRACTION * -side).toInt()
        }

    private fun depthIn(fromScale: Float): EnterTransition =
        fadeIn(OceanMotion.standard(OceanMotion.STAGGER_MS)) +
            scaleIn(OceanMotion.standard(OceanMotion.STAGGER_MS), initialScale = fromScale)

    private fun depthOut(toScale: Float): ExitTransition =
        fadeOut(OceanMotion.exit()) + scaleOut(OceanMotion.exit(), targetScale = toScale)

    fun enter(scope: NavScope): EnterTransition = with(scope) {
        if (reducedMotion()) return fadeInOnly()
        when (motion()) {
            NavMotion.AXIS_FORWARD -> axisIn(FORWARD)
            NavMotion.AXIS_BACKWARD -> axisIn(BACKWARD)
            NavMotion.DEPTH -> depthIn(OceanMotion.DEPTH_SCALE_OUT)
            NavMotion.DISSOLVE -> fadeIn(OceanMotion.standard())
        }
    }

    fun exit(scope: NavScope): ExitTransition = with(scope) {
        if (reducedMotion()) return fadeOutOnly()
        when (motion()) {
            NavMotion.AXIS_FORWARD -> axisOut(FORWARD)
            NavMotion.AXIS_BACKWARD -> axisOut(BACKWARD)
            NavMotion.DEPTH -> depthOut(OceanMotion.DEPTH_SCALE_IN)
            NavMotion.DISSOLVE -> fadeOut(OceanMotion.exit())
        }
    }

    fun popEnter(scope: NavScope): EnterTransition = with(scope) {
        if (reducedMotion()) return fadeInOnly()
        when (motion()) {
            NavMotion.AXIS_FORWARD -> axisIn(FORWARD)
            NavMotion.AXIS_BACKWARD -> axisIn(BACKWARD)
            NavMotion.DEPTH -> depthIn(OceanMotion.DEPTH_SCALE_IN)
            NavMotion.DISSOLVE -> fadeIn(OceanMotion.standard())
        }
    }

    fun popExit(scope: NavScope): ExitTransition = with(scope) {
        if (reducedMotion()) return fadeOutOnly()
        when (motion()) {
            NavMotion.AXIS_FORWARD -> axisOut(FORWARD)
            NavMotion.AXIS_BACKWARD -> axisOut(BACKWARD)
            NavMotion.DEPTH -> depthOut(OceanMotion.DEPTH_SCALE_OUT)
            NavMotion.DISSOLVE -> fadeOut(OceanMotion.exit())
        }
    }

    private companion object {
        const val FORWARD = 1
        const val BACKWARD = -1
    }
}
