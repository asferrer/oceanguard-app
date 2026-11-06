package com.oceanguard.ai.ui.components.dex

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType

/** Name of a canonical debris type in the six languages, or null for the extended types. */
@StringRes
internal fun debrisNameRes(type: DebrisType): Int? = when (type) {
    DebrisType.BOTTLE -> R.string.dex_debris_bottle
    DebrisType.CAN -> R.string.dex_debris_can
    DebrisType.FISHING_NET -> R.string.dex_debris_fishing_net
    DebrisType.GLOVE -> R.string.dex_debris_glove
    DebrisType.MASK -> R.string.dex_debris_mask
    DebrisType.METAL_DEBRIS -> R.string.dex_debris_metal_debris
    DebrisType.PLASTIC_DEBRIS -> R.string.dex_debris_plastic_debris
    DebrisType.TIRE -> R.string.dex_debris_tire
    DebrisType.FABRIC_DEBRIS -> R.string.dex_debris_fabric_debris
    DebrisType.GLASS_DEBRIS -> R.string.dex_debris_glass_debris
    DebrisType.OTHER -> R.string.dex_debris_other
    else -> null
}

/** `PLASTIC_BAG` -> "Plastic bag": readable name for a type that has no translation. */
internal fun humanizeEnumName(name: String): String =
    name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }

/** Localised name of [type]; extended types fall back to their humanised identifier. */
@Composable
fun debrisDisplayName(type: DebrisType): String {
    val res = debrisNameRes(type)
    return if (res != null) stringResource(res) else humanizeEnumName(type.name)
}
