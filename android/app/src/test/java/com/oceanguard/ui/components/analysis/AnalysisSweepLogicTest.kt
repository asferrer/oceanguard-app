package com.oceanguard.ai.ui.components.analysis

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.oceanguard.ai.data.AnalysisMode
import com.oceanguard.ai.data.DebrisDetection
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.inference.AnalysisResult
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSweepLogicTest {

    private fun box(y1: Float, x1: Float = 0f, label: String? = null, name: String = "PLASTIC_DEBRIS") =
        DetectionResult(
            x1 = x1,
            y1 = y1,
            x2 = x1 + 0.1f,
            y2 = y1 + 0.1f,
            classId = 0,
            className = name,
            confidence = 0.87f,
            rawLabel = label,
        )

    private fun complete(detections: List<DetectionResult>) = UiState.AnalysisComplete(
        AnalysisResult(
            rtdetrDetections = detections,
            vlmAnalysis = DebrisDetection(emptyList(), detections.size, ImageQuality.FAIR),
            healthScore = 80,
            totalDebrisCount = detections.size,
            processingTimeMs = 3_900,
            hasVLMAnalysis = false,
            modelName = "RT-DETRv2",
        ),
    )

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    @Test fun sweepRunsOnlyWhileThePipelineWorks() {
        assertTrue(AnalysisFlow.isRunning(UiState.ModelLoading("x")))
        assertTrue(AnalysisFlow.isRunning(UiState.Detecting))
        assertTrue(AnalysisFlow.isRunning(UiState.AnalyzingDeep(emptyList())))
        assertTrue(AnalysisFlow.isRunning(UiState.DetectionsReady(listOf(box(0f)))))
        assertFalse(AnalysisFlow.isRunning(UiState.Idle))
        assertFalse(AnalysisFlow.isRunning(UiState.Error("x")))
        assertFalse(AnalysisFlow.isRunning(complete(emptyList())))
    }

    @Test fun gemmaHasNoBoxesUntilTheEnd() {
        assertTrue(AnalysisFlow.detections(UiState.Detecting).isEmpty())
        assertTrue(AnalysisFlow.detections(UiState.AnalyzingDeep(emptyList())).isEmpty())
        assertEquals(1, AnalysisFlow.detections(complete(listOf(box(0f)))).size)
    }

    @Test fun aRefiningPassAlreadyHasItsBoxes() {
        assertEquals(1, AnalysisFlow.detections(UiState.AnalyzingDeep(listOf(box(0f)))).size)
    }

    @Test fun statusSaysWhatReallyRuns() {
        fun status(state: UiState, warming: Boolean = false, gemma: Boolean = false) =
            AnalysisFlow.status(state, warming, gemma)

        assertEquals(AnalysisStatus.LOADING_IMAGE, status(UiState.ModelLoading("x")))
        assertEquals(AnalysisStatus.WARMING_MODEL, status(UiState.ModelLoading("x"), warming = true))
        assertEquals(AnalysisStatus.DETECTING, status(UiState.Detecting))
        assertEquals(AnalysisStatus.GEMMA, status(UiState.Detecting, gemma = true))
        assertEquals(AnalysisStatus.GEMMA, status(UiState.AnalyzingDeep(emptyList())))
        assertEquals(AnalysisStatus.REFINING, status(UiState.AnalyzingDeep(listOf(box(0f)))))
        assertEquals(AnalysisStatus.FINISHED, status(complete(emptyList())))
    }

    @Test fun slowerModesSweepSlower() {
        val periods = listOf(AnalysisMode.FAST, AnalysisMode.DETAILED, AnalysisMode.TUNED)
            .map(AnalysisFlow::sweepPeriodMs)
        assertEquals(periods.sorted(), periods)
        assertEquals(2_856, AnalysisFlow.sweepPeriodMs(AnalysisMode.FAST))
    }

    @Test fun boxesAreRevealedTopToBottomThenLeftToRight() {
        val ordered = RevealRules.order(listOf(box(0.5f), box(0.1f, x1 = 0.4f), box(0.1f, x1 = 0.2f)))
        assertEquals(listOf(0.1f, 0.1f, 0.5f), ordered.map { it.y1 })
        assertEquals(0.2f, ordered[0].x1, 0f)
    }

    @Test fun theLineFiresWhatItHasReachedAndEverythingAtTheEnd() {
        val ordered = RevealRules.order(listOf(box(0.2f), box(0.6f), box(1f)))
        assertEquals(0, RevealRules.fired(ordered, 0.1f))
        assertEquals(1, RevealRules.fired(ordered, 0.2f))
        assertEquals(2, RevealRules.fired(ordered, 0.9f))
        assertEquals(3, RevealRules.fired(ordered, 1f))
        assertEquals(0, RevealRules.fired(emptyList(), 1f))
    }

    @Test fun theLineNeverJumpsBackToTheLoopWhenFadingOut() {
        fun line(running: Boolean, passing: Boolean, detections: Boolean) =
            RevealRules.lineFraction(running, passing, detections, loopPhase = 0.3f, passFraction = 0.9f)

        assertEquals(0.3f, line(running = true, passing = false, detections = false), 0f)
        assertEquals(0.9f, line(running = false, passing = true, detections = true), 0f)
        assertEquals(0.9f, line(running = false, passing = false, detections = true), 0f)
        assertEquals(0.3f, line(running = true, passing = false, detections = true), 0f)
        assertEquals(0.3f, line(running = false, passing = false, detections = false), 0f)
    }

    @Test fun labelsOmitThePercentageWithoutARealScore() {
        assertEquals("Plastic debris 87%", RevealRules.label(box(0f), showConfidence = true))
        assertEquals(
            "Plastic grocery bag",
            RevealRules.label(box(0f, label = "plastic_grocery_bag"), showConfidence = false),
        )
    }

    @Test fun aWidePhotoIsLetterboxedInATallView() {
        val frame = SweepGeometry.fit(Size(1000f, 2000f), Size(4000f, 3000f))
        assertRect(Rect(0f, 625f, 1000f, 1375f), frame)
    }

    @Test fun aPreviewCropsTheFrameOutsideTheView() {
        val frame = SweepGeometry.fillCenter(Size(1000f, 2000f), Size(480f, 640f))
        assertRect(Rect(-250f, 0f, 1250f, 2000f), frame)
    }

    @Test fun anUnknownImageFillsTheView() {
        val whole = Rect(0f, 0f, 100f, 50f)
        assertRect(whole, SweepGeometry.fit(Size(100f, 50f), Size.Unspecified))
        assertRect(whole, SweepGeometry.fillCenter(Size(100f, 50f), Size(0f, 0f)))
    }

    @Test fun boxesAreMappedThroughTheFrameNotTheView() {
        val detection = box(0.5f, x1 = 0.5f)
        val rect = SweepGeometry.boxRect(detection, Rect(0f, 625f, 1000f, 1375f))
        assertRect(Rect(500f, 1000f, 600f, 1075f), rect)
    }

    @Test fun aBoxOverTheWholePhotoMatchesTheLetterboxedFrame() {
        val frame = SweepGeometry.fit(Size(1080f, 1500f), Size(4000f, 3000f))
        val whole = DetectionResult(0f, 0f, 1f, 1f, 0, "PLASTIC_DEBRIS", 0.9f)
        assertRect(frame, SweepGeometry.boxRect(whole, frame))
        assertEquals(810f, frame.height, 0.01f)
    }
}
