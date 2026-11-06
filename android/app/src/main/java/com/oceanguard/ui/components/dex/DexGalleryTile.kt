package com.oceanguard.ai.ui.components.dex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oceanguard.ai.ui.components.sonar.springPress
import com.oceanguard.ai.ui.theme.SonarDarkColors

private val TileShape = RoundedCornerShape(16.dp)

/** Text colour that reads over the dark scrim of a photo tile, in light and dark themes. */
@Composable
fun tileTextColor(): Color = SonarDarkColors.ink

/**
 * Square photo tile of a dex gallery. The whole tile is the touch target and opens the detail
 * of the analysis it shows. [imageModifier] is where the caller attaches the shared element
 * that flies into the detail. [badge] sits in the top corner; [caption] over the bottom scrim.
 */
@Composable
fun DexGalleryTile(
    imageModel: Any?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    borderColor: Color = Color.Transparent,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    caption: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .springPress(interaction)
            .clip(TileShape)
            .border(2.dp, borderColor, TileShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = imageModifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f),
                    ),
                ),
        )
        badge?.invoke(this)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            content = caption,
        )
    }
}
