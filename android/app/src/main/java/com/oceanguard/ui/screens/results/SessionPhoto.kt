package com.oceanguard.ai.ui.screens.results

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.AnalysisModels
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.components.BoundingBoxOverlay
import com.oceanguard.ai.ui.theme.SonarTheme
import kotlinx.coroutines.launch

/** Photo of a saved analysis with share, save and upload-for-research buttons over it. */
@Composable
internal fun SessionPhoto(session: DetectionSession, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = session.thumbnailUri ?: session.imageUri

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        if (session.thumbnailUri != null) {
            AsyncImage(
                model = session.thumbnailUri,
                contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            BoundingBoxOverlay(
                imageUri = session.imageUri,
                detections = remember(session) { session.toDetections() },
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                showConfidence = AnalysisModels.hasModelConfidence(session.modelName),
            )
        }
        if (source.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhotoActionButton(
                    icon = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.results_share_photo),
                    onClick = { scope.launch { sharePhoto(context, source) } },
                )
                PhotoActionButton(
                    icon = Icons.Filled.SaveAlt,
                    contentDescription = stringResource(R.string.save_to_gallery_cd),
                    onClick = { scope.launch { savePhotoToGallery(context) { source } } },
                )
            }
        }
        ResearchUploadButton(session, Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

/** The stored objects as boxes, for sessions that have no pre-drawn picture. */
private fun DetectionSession.toDetections(): List<DetectionResult> = debrisList.map { debris ->
    DetectionResult(
        x1 = debris.bbox.x,
        y1 = debris.bbox.y,
        x2 = debris.bbox.x + debris.bbox.width,
        y2 = debris.bbox.y + debris.bbox.height,
        classId = debris.type.ordinal,
        className = debris.type.name.lowercase(),
        confidence = debris.confidence,
    )
}

@Composable
private fun ResearchUploadButton(session: DetectionSession, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(context) { context.applicationContext as OceanGuardApp }
    // Only with a destination in this build and the user's consent (given in Settings or in
    // the post-analysis sheet, which explain what is sent).
    val consent by app.settingsRepository.contributeConsentGiven.collectAsStateWithLifecycle(initialValue = false)
    if (!app.contributionRepository.isAvailable || !consent) return
    val enqueueError = stringResource(R.string.contribute_enqueue_error)
    // "LOADING" keeps the button disabled while the first status arrives, so the upload icon
    // does not flash as available.
    val status by app.contributionRepository
        .getStatusFlowForUri(session.imageUri)
        .collectAsStateWithLifecycle(initialValue = "LOADING")
    val canUpload = status == null || status == "FAILED"
    val (icon, description) = when (status) {
        "PENDING" -> Icons.Filled.Sync to R.string.contribute_status_pending
        "DONE" -> Icons.Filled.CheckCircle to R.string.contribute_status_done
        "FAILED" -> Icons.Filled.Refresh to R.string.contribute_status_failed
        else -> Icons.Filled.CloudUpload to R.string.contribute_upload_btn
    }
    PhotoActionButton(
        icon = icon,
        contentDescription = stringResource(description),
        enabled = canUpload,
        tint = if (status == "FAILED") MaterialTheme.colorScheme.error else SonarTheme.colors.trace,
        modifier = modifier,
        onClick = {
            scope.launch {
                if (!app.contributionRepository.enqueueSession(session)) {
                    Toast.makeText(context, enqueueError, Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}
