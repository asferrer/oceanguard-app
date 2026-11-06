package com.oceanguard.ai.ui.screens.results

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.ui.components.FitWidthBoundingBoxOverlay
import com.oceanguard.ai.utils.BitmapAnnotator
import kotlinx.coroutines.launch

/**
 * The photo of a result. With [annotatedUri] it shows the picture that already has the boxes drawn
 * in, the same one the saved detail shows; before that it draws them live over [imageUri].
 * Save and share sit on the bottom corner once the photo is final ([showActions]).
 */
@Composable
fun ResultPhoto(
    imageUri: Uri?,
    annotatedUri: String?,
    detections: List<DetectionResult>,
    showConfidence: Boolean,
    modifier: Modifier = Modifier,
    showActions: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = annotatedUri ?: imageUri?.toString()

    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        if (annotatedUri != null) {
            AsyncImage(
                model = annotatedUri,
                contentDescription = stringResource(R.string.session_detail_cd_annotated_image),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LivePhoto(imageUri, detections, showConfidence)
        }
        if (showActions && source != null) {
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
                    onClick = {
                        scope.launch {
                            savePhotoToGallery(context) {
                                annotatedUri ?: imageUri?.let {
                                    BitmapAnnotator.annotateAndSave(context, it, detections, showConfidence = showConfidence)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LivePhoto(imageUri: Uri?, detections: List<DetectionResult>, showConfidence: Boolean) {
    Box {
        FitWidthBoundingBoxOverlay(
            imageUri = imageUri?.toString(),
            detections = detections,
            modifier = Modifier.fillMaxWidth(),
            showConfidence = showConfidence,
        )
        if (detections.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ) {
                Text(
                    text = stringResource(R.string.results_detection_count, detections.size),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
