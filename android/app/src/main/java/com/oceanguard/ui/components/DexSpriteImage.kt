package com.oceanguard.ai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.collection.DexSprites

/**
 * Renders the pixel art sprite for a [DebrisType].
 *
 * Uses [FilterQuality.None] via [BitmapPainter] to keep the pixels crisp, without smoothing.
 * With [silhouette] the sprite is painted as a flat shape of [silhouetteColor], which is how an
 * entry the user has not discovered yet is shown. The shape is decorative: pass a
 * [contentDescription] only when the image carries meaning of its own.
 */
@Composable
fun DexSpriteImage(
    debrisType: DebrisType,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    silhouette: Boolean = false,
    silhouetteColor: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val spriteRes = DexSprites.getSprite(debrisType)
    val context = LocalContext.current
    val painter = remember(spriteRes) {
        val bmp = BitmapFactory.decodeResource(context.resources, spriteRes)
        BitmapPainter(bmp.asImageBitmap(), filterQuality = FilterQuality.None)
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        colorFilter = if (silhouette && silhouetteColor.isSpecified) {
            ColorFilter.tint(silhouetteColor, BlendMode.SrcIn)
        } else {
            null
        },
    )
}
