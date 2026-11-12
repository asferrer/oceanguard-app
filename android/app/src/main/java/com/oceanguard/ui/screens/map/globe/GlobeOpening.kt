package com.oceanguard.ai.ui.screens.map.globe

/** What the globe does by itself when the screen opens or the user asks to see everything. */
enum class OpeningAction {
    /** Nothing: it stays where it is. */
    NONE,

    /** Turns slowly with nothing to face. */
    SPIN,

    /** Turns to face the data without changing the zoom. */
    FACE,

    /** Flies in until the flat map takes over. Only an explicit request does this. */
    DIVE,
}

/** The rules for the globe's own motion, kept apart from the composables so they can be tested. */
object GlobeOpening {

    /**
     * Decides the opening motion. The globe never zooms in on its own: only a deep link to one
     * place ([hasFocus]) or the user's own "frame all" ([asked]) fly in.
     *
     * @param hasData There are zones or points to face.
     * @param userTookOver The user has already touched the globe.
     * @param asked The user pressed "frame all" since the last decision.
     * @param reducedMotion Spin and flights are off.
     */
    fun plan(hasFocus: Boolean, hasData: Boolean, userTookOver: Boolean, asked: Boolean, reducedMotion: Boolean): OpeningAction =
        when {
            asked && hasData -> OpeningAction.DIVE
            userTookOver -> OpeningAction.NONE
            hasFocus -> OpeningAction.DIVE
            hasData -> OpeningAction.FACE
            reducedMotion -> OpeningAction.NONE
            else -> OpeningAction.SPIN
        }

    /** [view] turned to face a coordinate, at the same zoom: it never changes how close the camera is. */
    fun facing(view: GlobeCamera, latitude: Double, longitude: Double): GlobeCamera =
        GlobeCamera(latitude, longitude, view.zoom)
}
