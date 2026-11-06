package com.oceanguard.ai.ui.screens.models

import com.oceanguard.ai.inference.VlmModelManager

/**
 * True when the file of [model] is on the phone. A fine-tuned file that is not published
 * resolves to the base one in `isModelAvailable`; it is not installed here.
 */
fun VlmModelManager.isInstalled(model: CatalogModel): Boolean {
    val tier = model.tier ?: return isSpeciesPackAvailable()
    if (tier.variantFallback(model.variant) != model.variant) return false
    return isModelAvailable(tier, model.variant)
}

/** Bytes [model] takes on disk. */
fun VlmModelManager.sizeOnDisk(model: CatalogModel): Long {
    val tier = model.tier ?: return speciesPackBytes()
    return installedBytes(tier, model.variant)
}
