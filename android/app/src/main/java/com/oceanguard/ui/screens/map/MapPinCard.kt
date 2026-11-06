package com.oceanguard.ai.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme
import com.oceanguard.ai.ui.theme.healthScoreColor
import java.text.DateFormat

private val PHOTO_SIZE = 72.dp

/**
 * Card of the photo whose pin was tapped: where it was taken, how healthy the spot is, and the
 * two next steps: open the analysis or correct its location.
 */
@Composable
fun MapPinCard(
    session: DetectionSession,
    onOpenDetail: () -> Unit,
    onFixLocation: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    SonarCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = session.thumbnailUri ?: session.imageUri,
                contentDescription = stringResource(R.string.cd_session_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(PHOTO_SIZE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dateFormat.format(session.timestamp), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.history_health_score, session.healthScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = healthScoreColor(session.healthScore),
                )
                session.location?.let {
                    Text(
                        text = formatCoordinates(it.latitude, it.longitude),
                        style = SonarTheme.type.dataSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
        }
        PinCardActions(onOpenDetail, onFixLocation)
    }
}

@Composable
private fun PinCardActions(onOpenDetail: () -> Unit, onFixLocation: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SonarButton(
            text = stringResource(R.string.map_pin_open_detail),
            onClick = onOpenDetail,
            modifier = Modifier.weight(1f),
        )
        SonarButton(
            text = stringResource(R.string.map_pin_fix_location),
            onClick = onFixLocation,
            style = SonarButtonStyle.Secondary,
            icon = Icons.Filled.Edit,
            modifier = Modifier.weight(1f),
        )
    }
}
