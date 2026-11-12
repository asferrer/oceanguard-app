package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.oceanguard.ai.R
import com.oceanguard.ai.inference.species.SpeciesPackConfig
import java.io.File

/**
 * Loads a species sprite from [spritePath] inside the species pack directory
 * (`getExternalFilesDir/models/species/`); the catalogue stores it as `sprites/<speciesKey>.webp`.
 *
 * Uses Coil 3 [AsyncImage] for async disk I/O and crossfade.
 * Falls back to the [R.drawable.dex_species_unknown] placeholder drawable
 * when [spritePath] is null or the file does not exist on disk.
 *
 * The artwork comes with the species pack (v5 and later); species without a sprite, or a pack
 * without them, show the neutral placeholder. With [silhouette] the image is painted as a flat
 * shape of [silhouetteColor] instead (an entry not discovered yet).
 *
 * @param spritePath  Relative path as stored in SpeciesCatalogEntry.spritePath.
 *                    May be null for uncatalogued species.
 * @param size        Rendered size (width == height, square aspect).
 * @param modifier    Additional layout modifiers applied to the image.
 */
@Composable
fun SpeciesSpriteImage(
    spritePath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    silhouette: Boolean = false,
    silhouetteColor: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val file = spritePath?.let { path -> spriteFile(context.getExternalFilesDir(null), path) }
    val model = if (file != null && file.exists()) {
        ImageRequest.Builder(context)
            .data(file)
            .crossfade(true)
            .build()
    } else {
        // File absent or spritePath null — fall back to bundled drawable.
        ImageRequest.Builder(context)
            .data(R.drawable.dex_species_unknown)
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        placeholder = painterResource(R.drawable.dex_species_unknown),
        error = painterResource(R.drawable.dex_species_unknown),
        colorFilter = if (silhouette && silhouetteColor.isSpecified) {
            ColorFilter.tint(silhouetteColor, BlendMode.SrcIn)
        } else {
            null
        },
        modifier = modifier.size(size),
    )
}

/** Sprite file of the catalogue [spritePath] (`sprites/<key>.webp`) under [filesDir]; null for a path leaving the pack. */
internal fun spriteFile(filesDir: File?, spritePath: String): File? =
    filesDir?.takeIf { SpeciesPackConfig.isSafeRelative(spritePath) }
        ?.let { File(it, "models/species/$spritePath") }
