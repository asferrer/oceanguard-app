package com.oceanguard.ai.ui.screens.results

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.utils.DataExporter
import com.oceanguard.ai.utils.ImageGallerySaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SCRIM_ALPHA = 0.85f

/** Round button laid over a photo: 48 dp target, readable on any picture. */
@Composable
fun PhotoActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA)
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = container,
            disabledContainerColor = container,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Saves the photo that [resolvePath] produces to the gallery and tells the person how it went. */
suspend fun savePhotoToGallery(context: Context, resolvePath: suspend () -> String?) {
    val saved = withContext(Dispatchers.IO) {
        resolvePath()?.let { ImageGallerySaver.saveToGallery(context, it) }
    }
    val message = if (saved != null) R.string.save_to_gallery_success else R.string.save_to_gallery_error
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}

/** Opens the share sheet with a copy of the photo at [source] (a URI or an absolute path). */
suspend fun sharePhoto(context: Context, source: String) {
    val uri = withContext(Dispatchers.IO) { DataExporter.exportImage(context, source) }
    if (uri != null) {
        DataExporter.shareFile(context, uri, "image/jpeg")
    } else {
        Toast.makeText(context, context.getString(R.string.results_share_error), Toast.LENGTH_SHORT).show()
    }
}
