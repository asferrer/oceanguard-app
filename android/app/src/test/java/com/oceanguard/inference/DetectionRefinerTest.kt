package com.oceanguard.ai.inference

import com.oceanguard.ai.data.DebrisMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionRefinerTest {

    private fun det(x1: Float, y1: Float, x2: Float, y2: Float, cls: String, conf: Float = 0.9f,
                    raw: String? = null, sub: String? = null, mat: DebrisMaterial? = null) =
        DetectionResult(x1, y1, x2, y2, 0, cls, conf, mat, raw, sub)

    @Test
    fun copiesSubTypeAndMaterialFromOverlappingBoxOfSameFamily() {
        val base = listOf(det(0.1f, 0.1f, 0.5f, 0.5f, "Plastic_Debris"))
        val gemma = listOf(det(0.12f, 0.1f, 0.5f, 0.52f, "PLASTIC_DEBRIS", raw = "plastic_bag", sub = "PLASTIC_BAG", mat = DebrisMaterial.PLASTIC))
        val out = DetectionRefiner.refine(base, gemma).single()
        assertEquals("plastic_bag", out.rawLabel)
        assertEquals("PLASTIC_BAG", out.subType)
        assertEquals(DebrisMaterial.PLASTIC, out.material)
        assertEquals(0.9f, out.confidence, 0f)            // RT-DETRv2 stays authoritative
        assertEquals("Plastic_Debris", out.className)
    }

    @Test
    fun ignoresOtherFamilyAndLowOverlap() {
        val base = listOf(det(0.1f, 0.1f, 0.5f, 0.5f, "Can"), det(0.6f, 0.6f, 0.9f, 0.9f, "Tire"))
        val gemma = listOf(
            det(0.1f, 0.1f, 0.5f, 0.5f, "Bottle", raw = "bottle", sub = "BOTTLE"),       // wrong family
            det(0.0f, 0.0f, 0.2f, 0.2f, "Tire", raw = "tire", sub = "TIRE"),             // IoU ~ 0
        )
        val out = DetectionRefiner.refine(base, gemma)
        assertNull(out[0].subType)
        assertNull(out[1].subType)
    }

    @Test
    fun oneGemmaBoxServesOnlyTheMostConfidentBase() {
        val b1 = det(0.1f, 0.1f, 0.5f, 0.5f, "Bottle", conf = 0.95f)
        val b2 = det(0.11f, 0.1f, 0.5f, 0.5f, "Bottle", conf = 0.6f)
        val g = det(0.1f, 0.1f, 0.5f, 0.5f, "Bottle", raw = "water_bottle", sub = "BOTTLE")
        val out = DetectionRefiner.refine(listOf(b2, b1), listOf(g))
        assertNull(out[0].rawLabel)                        // b2 (weaker) loses
        assertEquals("water_bottle", out[1].rawLabel)
    }

    @Test
    fun emptyGemmaKeepsInput() {
        val base = listOf(det(0.1f, 0.1f, 0.5f, 0.5f, "Mask"))
        assertEquals(base, DetectionRefiner.refine(base, emptyList()))
    }
}
