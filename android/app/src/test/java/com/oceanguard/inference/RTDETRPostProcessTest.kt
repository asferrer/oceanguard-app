package com.oceanguard.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Post-processing contract of RT-DETRv2 TFLite output: sigmoid, threshold, cxcywh->xyxy, per-class NMS, cap. */
class RTDETRPostProcessTest {

    private val none = -10f   // below the -5 logit pre-filter

    private fun logitsRow(cls: Int, logit: Float) = FloatArray(8) { if (it == cls) logit else none }

    @Test
    fun sigmoidAndThreshold() {
        val logits = arrayOf(logitsRow(0, 0f), logitsRow(1, -1f))        // 0.5 and 0.269
        val boxes = arrayOf(floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f), floatArrayOf(0.2f, 0.2f, 0.1f, 0.1f))
        val out = RTDETRInference.postProcess(logits, boxes, 640, 640, 0.5f)
        assertEquals(1, out.size)
        assertEquals(0.5f, out[0].confidence, 1e-6f)
        assertEquals("Bottle", out[0].className)
    }

    @Test
    fun cxcywhToXyxyAndClipping() {
        val logits = arrayOf(logitsRow(3, 4f))
        val boxes = arrayOf(floatArrayOf(0.95f, 0.5f, 0.2f, 0.4f))     // x2 = 1.05 -> clipped to 1
        val d = RTDETRInference.postProcess(logits, boxes, 640, 640, 0.5f).single()
        assertEquals(0.85f, d.x1, 1e-6f)
        assertEquals(1.0f, d.x2, 1e-6f)
        assertEquals(0.3f, d.y1, 1e-6f)
        assertEquals(0.7f, d.y2, 1e-6f)
        assertEquals("Glove", d.className)
    }

    @Test
    fun nmsIsPerClass() {
        val box = floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f)
        val logits = arrayOf(logitsRow(0, 3f), logitsRow(0, 2f), logitsRow(1, 2.5f))
        val out = RTDETRInference.postProcess(logits, arrayOf(box, box.clone(), box.clone()), 640, 640, 0.5f)
        // same box twice for class 0 -> one survives; the class 1 box is kept (different class)
        assertEquals(2, out.size)
        assertEquals(listOf(0, 1), out.map { it.classId })
    }

    @Test
    fun oneQueryCanYieldSeveralClasses() {
        val row = FloatArray(8) { none }.also { it[0] = 2f; it[6] = 1f }
        val out = RTDETRInference.postProcess(arrayOf(row), arrayOf(floatArrayOf(0.5f, 0.5f, 0.3f, 0.3f)), 640, 640, 0.5f)
        assertEquals(2, out.size)
        assertTrue(out[0].confidence >= out[1].confidence)
    }

    @Test
    fun capsAtHundredDetections() {
        val n = 150
        val logits = Array(n) { logitsRow(it % 8, 3f) }
        val boxes = Array(n) { i -> floatArrayOf(0.01f + (i % 50) * 0.02f, 0.01f + (i / 50) * 0.3f, 0.005f, 0.005f) }
        val out = RTDETRInference.postProcess(logits, boxes, 640, 640, 0.5f)
        assertTrue(out.size <= 100)
    }
}
