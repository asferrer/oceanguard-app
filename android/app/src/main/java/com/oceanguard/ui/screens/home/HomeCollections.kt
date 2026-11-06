package com.oceanguard.ai.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.collection.MarineDexEntry
import com.oceanguard.ai.ui.components.DexSpriteImage
import com.oceanguard.ai.ui.components.sonar.SonarCard
import com.oceanguard.ai.ui.theme.SonarTheme

private val CARD_ICON = 28.dp
private val SPRITE_SIZE = 28.dp
private val BAR_HEIGHT = 4.dp
private const val DEBRIS_FAMILIES = 11
private const val RECENT_SPRITES = 5

private val ButtonRole = Modifier.semantics(mergeDescendants = true) { role = Role.Button }

/** MarineDex: recent discoveries as sprites and the progress over the 11 families. */
@Composable
fun MarineDexCard(
    dexEntries: List<MarineDexEntry>,
    discoveredCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SonarCard(modifier = modifier.then(ButtonRole), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(CARD_ICON),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_marinedex_title), style = MaterialTheme.typography.titleMedium)
                RecentSprites(dexEntries)
                LinearProgressIndicator(
                    progress = { discoveredCount / DEBRIS_FAMILIES.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = stringResource(R.string.home_marinedex_progress, discoveredCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron(stringResource(R.string.cd_view_marinedex))
        }
    }
}

@Composable
private fun RecentSprites(dexEntries: List<MarineDexEntry>) {
    if (dexEntries.isEmpty()) {
        Text(
            text = stringResource(R.string.home_marinedex_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dexEntries.take(RECENT_SPRITES).forEach { entry ->
            val type = runCatching { DebrisType.valueOf(entry.debrisType) }.getOrNull()
            if (type != null) DexSpriteImage(debrisType = type, size = SPRITE_SIZE)
        }
    }
}

/** BioDex: the species collection, which runs beside MarineDex. */
@Composable
fun BioDexCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SonarCard(modifier = modifier.then(ButtonRole), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_fish),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(CARD_ICON),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.biodex_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.biodex_home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron(null)
        }
    }
}

@Composable
private fun Chevron(description: String?) {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
