package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.screens.map.species.MapDataMode

private val MapDataMode.labelRes: Int
    get() = when (this) {
        MapDataMode.DEBRIS -> R.string.map_mode_debris
        MapDataMode.SPECIES -> R.string.map_mode_species
        MapDataMode.BOTH -> R.string.map_mode_both
    }

/** Debris, species or both: which data the map shows. */
@Composable
fun MapDataModeSelector(mode: MapDataMode, onSelect: (MapDataMode) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.map_mode_cd)
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        MapDataMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == mode,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, MapDataMode.entries.size),
            ) {
                Text(stringResource(option.labelRes))
            }
        }
    }
}
