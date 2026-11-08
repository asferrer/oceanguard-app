package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanguard.ai.R

/** Sections of the contribution privacy policy, in reading order (title, body). */
private val POLICY_SECTIONS = listOf(
    R.string.contribute_policy_what_title to R.string.contribute_policy_what_body,
    R.string.contribute_policy_why_title to R.string.contribute_policy_why_body,
    R.string.contribute_policy_where_title to R.string.contribute_policy_where_body,
    R.string.contribute_policy_publish_title to R.string.contribute_policy_publish_body,
    R.string.contribute_policy_withdraw_title to R.string.contribute_policy_withdraw_body,
    R.string.contribute_policy_license_title to R.string.contribute_policy_license_body,
)

/**
 * Privacy policy of the photo contribution, inside the app so it is readable offline and in
 * the six app languages: what is collected, why, where it is kept, what is published, how to
 * withdraw and under which licence.
 */
@Composable
fun ContributionPrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contribute_policy_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                POLICY_SECTIONS.forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contribute_policy_close)) }
        },
    )
}

/** Text button that opens [ContributionPrivacyDialog]. */
@Composable
fun ContributionPrivacyLink(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text(stringResource(R.string.contribute_policy_link))
    }
    if (open) ContributionPrivacyDialog(onDismiss = { open = false })
}
