package com.oceanguard.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.ui.components.dex.DexGalleryTile
import com.oceanguard.ai.ui.components.dex.debrisDisplayName
import com.oceanguard.ai.ui.components.dex.tileTextColor
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.navigation.OceanSharedKeys
import com.oceanguard.ai.ui.navigation.oceanSharedElement
import com.oceanguard.ai.ui.theme.materialColor
import java.text.SimpleDateFormat
import java.util.Locale

/** Detection confidence from which a photo gets the border of its dominant material. */
private const val HIGH_CONFIDENCE = 0.75f

/**
 * 2-column grid of session thumbnails containing the given [debrisType].
 *
 * Each tile shows the session image, the number of items of this type and the date over a
 * bottom scrim, and a border in the colour of the dominant material for high-confidence
 * detections. Tapping a tile opens that analysis; its photo flies into the detail.
 */
@Composable
fun MarineDexGallery(
    debrisType: DebrisType,
    sessions: List<DetectionSession>,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        SonarEmptyState(
            icon = Icons.Filled.PhotoLibrary,
            title = stringResource(R.string.dex_gallery_empty_title),
            message = stringResource(R.string.dex_gallery_empty_message, debrisDisplayName(debrisType)),
            modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = sessions, key = { it.id }) { session ->
            GalleryItem(session = session, debrisType = debrisType, onClick = { onSessionClick(session.id) })
        }
    }
}

@Composable
private fun GalleryItem(
    session: DetectionSession,
    debrisType: DebrisType,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val matching = remember(session, debrisType) { session.debrisList.filter { it.type == debrisType } }
    val dominantMaterial = remember(matching) {
        matching.groupBy { it.material }.maxByOrNull { it.value.size }?.key
    }
    val highConfidence = remember(matching) { matching.any { it.confidence >= HIGH_CONFIDENCE } }
    val borderColor = if (highConfidence && dominantMaterial != null) {
        materialColor(dominantMaterial)
    } else {
        Color.Transparent
    }

    DexGalleryTile(
        imageModel = session.thumbnailUri ?: session.imageUri,
        contentDescription = stringResource(R.string.dex_gallery_cd_session, debrisDisplayName(debrisType)),
        onClick = onClick,
        imageModifier = Modifier.oceanSharedElement(OceanSharedKeys.sessionImage(session.id)),
        borderColor = borderColor,
    ) {
        Text(
            text = stringResource(R.string.marinedex_gallery_found_count, matching.size),
            color = tileTextColor(),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = dateFormat.format(session.timestamp),
            color = tileTextColor(),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
