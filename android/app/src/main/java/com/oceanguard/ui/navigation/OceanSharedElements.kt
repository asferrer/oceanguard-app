package com.oceanguard.ai.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.oceanguard.ai.ui.motion.LocalReducedMotion
import com.oceanguard.ai.ui.motion.OceanMotion

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalOceanSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** Animated scope of the destination being composed: a shared element needs it to know its state. */
val LocalOceanNavAnimation = compositionLocalOf<AnimatedContentScope?> { null }

/** Keys of the elements that travel between screens. Both ends must use the same one. */
object OceanSharedKeys {
    fun sessionImage(sessionId: Long): String = "session-image-$sessionId"
    fun dexSprite(debrisType: String): String = "dex-sprite-$debrisType"
    fun speciesSprite(speciesKey: String): String = "species-sprite-$speciesKey"
}

/**
 * `NavHost` destination with what shared elements need already in place. Every route of the app
 * must use this instead of `composable`, or [oceanSharedElement] silently does nothing in it.
 */
fun NavGraphBuilder.oceanScreen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        CompositionLocalProvider(LocalOceanNavAnimation provides this) { content(entry) }
    }
}

/**
 * Makes this element fly to the element with the same [key] on the next screen (thumbnail to
 * detail, sprite to header). Without a shared scope or under "Remove animations" it is a no-op:
 * the screen then changes with the fade of the graph and nothing moves in space.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.oceanSharedElement(key: String): Modifier {
    val sharedScope = LocalOceanSharedTransition.current
    val navScope = LocalOceanNavAnimation.current
    if (sharedScope == null || navScope == null || LocalReducedMotion.current) return this
    return with(sharedScope) {
        this@oceanSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = navScope,
            boundsTransform = OceanBoundsTransform,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val OceanBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    OceanMotion.transition()
}
