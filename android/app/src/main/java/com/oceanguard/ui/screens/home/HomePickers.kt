package com.oceanguard.ai.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.oceanguard.ai.ui.MainViewModel

/** Where photos come from when the camera is not used. */
enum class PhotoSource { GALLERY, FILES, SPECIES }

/** Ways to bring photos into the app without the camera. */
@Stable
class HomePickers internal constructor(
    private val gallery: () -> Unit,
    private val files: () -> Unit,
    private val species: () -> Unit,
) {
    /**
     * Gallery and files take images and videos and analyse them as a batch. Species take images
     * only and always end in the batch screen, even for a single photo.
     */
    fun pick(source: PhotoSource) = when (source) {
        PhotoSource.GALLERY -> gallery()
        PhotoSource.FILES -> files()
        PhotoSource.SPECIES -> species()
    }
}

@Composable
fun rememberHomePickers(
    viewModel: MainViewModel,
    navController: NavController,
    onSpeciesBatchPicked: (List<Uri>) -> Unit,
): HomePickers {
    val context = LocalContext.current
    val analyze = { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.analyzeMedia(context, uris, navController)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> analyze(uris) }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> analyze(uris) }
    val speciesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> if (uris.isNotEmpty()) onSpeciesBatchPicked(uris) }

    return remember(galleryLauncher, filesLauncher, speciesLauncher) {
        HomePickers(
            gallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            files = { filesLauncher.launch(arrayOf("image/*", "video/*")) },
            species = {
                speciesLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }
}
