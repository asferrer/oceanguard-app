package com.oceanguard.ai.ui.screens.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.oceanguard.ai.R
import com.oceanguard.ai.ui.components.analysis.OnPhoto
import com.oceanguard.ai.ui.components.sonar.SonarButton
import com.oceanguard.ai.ui.components.sonar.SonarButtonStyle
import com.oceanguard.ai.ui.components.sonar.SonarEmptyState
import com.oceanguard.ai.ui.theme.SonarDarkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CameraOverlays"
private const val GRID_ALPHA = 0.15f
private const val THIRD = 1f / 3f

private val BAR_PADDING_X = 24.dp
private val BAR_PADDING_Y = 20.dp
private val BAR_GAP = 16.dp

/** Rule-of-thirds guide over the viewfinder. */
@Composable
internal fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawGridLine(Offset(size.width * THIRD, 0f), Offset(size.width * THIRD, size.height))
        drawGridLine(Offset(size.width * 2 * THIRD, 0f), Offset(size.width * 2 * THIRD, size.height))
        drawGridLine(Offset(0f, size.height * THIRD), Offset(size.width, size.height * THIRD))
        drawGridLine(Offset(0f, size.height * 2 * THIRD), Offset(size.width, size.height * 2 * THIRD))
    }
}

private fun DrawScope.drawGridLine(start: Offset, end: Offset) {
    drawLine(OnPhoto.ink.copy(alpha = GRID_ALPHA), start, end, strokeWidth = 1.dp.toPx())
}

/** Photo just taken, to confirm or repeat it. Shown when "confirm capture" is on. */
@Composable
internal fun CapturePreviewOverlay(
    imageUri: Uri,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, imageUri) {
        value = withContext(Dispatchers.IO) { loadUprightBitmap(context, imageUri) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SonarDarkColors.abyss),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_captured_photo_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, OnPhoto.scrim)))
                .navigationBarsPadding()
                .padding(horizontal = BAR_PADDING_X, vertical = BAR_PADDING_Y),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        ) {
            SonarButton(
                text = stringResource(R.string.camera_btn_retake),
                onClick = onRetake,
                style = SonarButtonStyle.Secondary,
                icon = Icons.Filled.CameraAlt,
                modifier = Modifier.weight(1f),
            )
            SonarButton(
                text = stringResource(R.string.camera_btn_analyze),
                onClick = onConfirm,
                icon = Icons.Filled.Search,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Decodes a photo and applies its EXIF rotation, so the preview matches the capture. */
private fun loadUprightBitmap(context: Context, uri: Uri): Bitmap? = try {
    val resolver = context.contentResolver
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }
    decoded?.let { rotateBy(it, exifDegrees(orientation)) }
} catch (exception: Exception) {
    Log.e(TAG, "Failed to load preview bitmap", exception)
    null
}

private fun exifDegrees(orientation: Int?): Float = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
}

/** Rotated copy of [source], which is released; [source] itself when there is nothing to do. */
internal fun rotateBy(source: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return source
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}

/** Explains why the camera is needed and asks for it, for the camera and the continuous scan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraPermissionScreen(
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_permission_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_go_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            SonarEmptyState(
                icon = Icons.Filled.Camera,
                title = stringResource(R.string.camera_permission_title),
                message = stringResource(
                    if (shouldShowRationale) {
                        R.string.camera_permission_rationale
                    } else {
                        R.string.camera_permission_request
                    },
                ),
                actionLabel = stringResource(R.string.camera_btn_grant_permission),
                onAction = onRequestPermission,
            )
        }
    }
}
