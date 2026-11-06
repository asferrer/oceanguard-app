package com.oceanguard.ai.inference

import com.oceanguard.ai.data.DebrisType

/**
 * Optional second stage of the multi-model flow (RT-DETRv2 detects, Gemma 4 refines).
 *
 * RT-DETRv2 boxes, classes and confidences are authoritative. Gemma 4 only contributes the
 * fields RT-DETRv2 cannot fill (`rawLabel`, `subType`, `material`), copied from the Gemma 4
 * box that overlaps each RT-DETRv2 box the most, provided both belong to the same canonical
 * family. RT-DETRv2 boxes without a compatible Gemma 4 box are returned unchanged.
 *
 * Pure function: no Android dependencies, covered by unit tests.
 */
object DetectionRefiner {

    const val DEFAULT_MIN_IOU = 0.3f

    fun refine(
        base: List<DetectionResult>,
        gemma: List<DetectionResult>,
        minIou: Float = DEFAULT_MIN_IOU,
    ): List<DetectionResult> {
        if (gemma.isEmpty()) return base
        val used = BooleanArray(gemma.size)
        // Highest-confidence RT-DETRv2 boxes pick first so one Gemma box is not stolen by a weaker duplicate.
        val order = base.indices.sortedByDescending { base[it].confidence }
        val refined = base.toMutableList()
        for (i in order) {
            val det = base[i]
            val family = DebrisType.fromString(det.className).canonical()
            var best = -1
            var bestIou = minIou
            for (j in gemma.indices) {
                if (used[j]) continue
                if (DebrisType.fromString(gemma[j].className).canonical() != family) continue
                val iou = iou(det, gemma[j])
                if (iou >= bestIou) {
                    bestIou = iou
                    best = j
                }
            }
            if (best >= 0) {
                used[best] = true
                val g = gemma[best]
                refined[i] = det.copy(rawLabel = g.rawLabel, subType = g.subType, material = g.material)
            }
        }
        return refined
    }

    internal fun iou(a: DetectionResult, b: DetectionResult): Float {
        val ix = maxOf(0f, minOf(a.x2, b.x2) - maxOf(a.x1, b.x1))
        val iy = maxOf(0f, minOf(a.y2, b.y2) - maxOf(a.y1, b.y1))
        val inter = ix * iy
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}
