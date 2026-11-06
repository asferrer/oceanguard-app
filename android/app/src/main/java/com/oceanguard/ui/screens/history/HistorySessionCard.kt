package com.oceanguard.ai.ui.screens.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.data.RiskLevel
import com.oceanguard.ai.ui.components.PlaceNameText
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.theme.HealthCritical
import com.oceanguard.ai.ui.theme.HealthExcellent
import com.oceanguard.ai.ui.theme.HealthFair
import com.oceanguard.ai.ui.theme.HealthPoor
import com.oceanguard.ai.ui.theme.healthScoreColor
import com.oceanguard.ai.utils.PhotonGeocoderClient
import java.text.DateFormat

private val THUMBNAIL_SIZE = 100.dp
private val ACCENT_WIDTH = 4.dp

/** How a card relates to multi-selection: absent, chosen or not chosen. */
enum class SelectionState { Off, Unchecked, Checked }

/**
 * One analysis of the history: thumbnail, when, how many objects, health, risk, quality and place.
 * In selection mode a tap toggles it and the check mark shows whether it is chosen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    session: DetectionSession,
    geocoder: PhotonGeocoderClient,
    selection: SelectionState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val checked = selection == SelectionState.Checked
    val selectedText = stringResource(R.string.history_state_selected)
    val notSelectedText = stringResource(R.string.history_state_not_selected)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .springPress(interaction)
            .semantics {
                if (selection != SelectionState.Off) {
                    selected = checked
                    stateDescription = if (checked) selectedText else notSelectedText
                }
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Box(
            Modifier
                .height(IntrinsicSize.Min)
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        ) {
            SessionCardBody(session, geocoder, onEditLocation)
            if (selection != SelectionState.Off) CheckMark(checked, Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun SessionCardBody(session: DetectionSession, geocoder: PhotonGeocoderClient, onEditLocation: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(ACCENT_WIDTH)
                .fillMaxHeight()
                .background(
                    if (session.totalCount > 0) {
                        healthScoreColor(session.healthScore)
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
        )
        Row(
            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionThumbnail(session)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SessionHeadline(session)
                SessionHealthRow(session)
                SessionLocationRow(session, geocoder, onEditLocation)
            }
        }
    }
}

@Composable
private fun CheckMark(checked: Boolean, modifier: Modifier) {
    Icon(
        imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = null,
        modifier = modifier.padding(12.dp).size(24.dp),
        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionThumbnail(session: DetectionSession) {
    Box(
        modifier = Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        // The annotated image, with its boxes, is preferred over the original.
        val source = session.thumbnailUri ?: session.imageUri
        if (source.isNotEmpty()) {
            AsyncImage(
                model = source,
                contentDescription = stringResource(R.string.cd_session_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun SessionHeadline(session: DetectionSession) {
    val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = date.format(session.timestamp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = time.format(session.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    Text(
        text = when (session.totalCount) {
            0 -> stringResource(R.string.results_no_detections_title)
            1 -> stringResource(R.string.history_debris_count_singular, session.totalCount)
            else -> stringResource(R.string.history_debris_count_plural, session.totalCount)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SessionHealthRow(session: DetectionSession) {
    // Without detections there is nothing to score: a health of 100 would read as a clean result.
    if (session.totalCount > 0) {
        val scoreColor = healthScoreColor(session.healthScore)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(scoreColor, CircleShape))
            Text(
                text = stringResource(R.string.history_health_score, session.healthScore),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            RiskBadge(session.getRiskLevel())
        }
    }
    Text(
        text = stringResource(R.string.history_image_quality, stringResource(session.imageQuality.labelRes())),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionLocationRow(session: DetectionSession, geocoder: PhotonGeocoderClient, onEditLocation: () -> Unit) {
    val location = session.location
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = stringResource(if (location != null) R.string.cd_gps_location else R.string.cd_add_location),
            modifier = Modifier.size(16.dp),
            tint = if (location != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (location != null) {
            PlaceNameText(
                location = location,
                geocoder = geocoder,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        } else {
            Box(Modifier.weight(1f))
        }
        IconButton(onClick = onEditLocation) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(if (location != null) R.string.cd_edit_location else R.string.cd_add_location),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Risk in words, with a dot in the health palette: colour alone never carries the meaning. */
@Composable
fun RiskBadge(riskLevel: RiskLevel) {
    Surface(shape = RoundedCornerShape(6.dp), color = riskColor(riskLevel).copy(alpha = BADGE_TINT)) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(6.dp).background(riskColor(riskLevel), CircleShape))
            Text(
                text = stringResource(riskLevel.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private const val BADGE_TINT = 0.18f

internal fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.LOW -> HealthExcellent
    RiskLevel.MODERATE -> HealthFair
    RiskLevel.HIGH -> HealthPoor
    RiskLevel.CRITICAL -> HealthCritical
}

private fun RiskLevel.labelRes(): Int = when (this) {
    RiskLevel.LOW -> R.string.history_risk_low
    RiskLevel.MODERATE -> R.string.history_risk_moderate
    RiskLevel.HIGH -> R.string.history_risk_high
    RiskLevel.CRITICAL -> R.string.history_risk_critical
}

private fun ImageQuality.labelRes(): Int = when (this) {
    ImageQuality.GOOD -> R.string.history_quality_good
    ImageQuality.FAIR -> R.string.history_quality_fair
    ImageQuality.POOR -> R.string.history_quality_poor
}
