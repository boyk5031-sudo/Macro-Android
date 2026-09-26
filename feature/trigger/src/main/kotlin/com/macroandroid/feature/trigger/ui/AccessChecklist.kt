package com.macroandroid.feature.trigger.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroandroid.core.common.contract.TriggerAccessStatus
import com.macroandroid.core.common.contract.TriggerRequirement
import com.macroandroid.core.ui.theme.MacroTheme
import com.macroandroid.feature.trigger.R

/**
 * Shows exactly which access is missing and takes the user to the right place (§9). Hidden when everything is
 * granted. Nothing here assumes a grant: the runtime re-checks live state on every activation.
 */
@Composable
fun AccessChecklist(access: TriggerAccessStatus, onOpenDisclosure: () -> Unit, modifier: Modifier = Modifier) {
    if (access.ready) return
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.trg_access_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.trg_access_body), style = MaterialTheme.typography.bodySmall)
            TriggerRequirement.entries.forEach { req ->
                val done = req !in access.missing
                RequirementRow(req, done, access.restrictedSettingsMayApply, onOpenDisclosure) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(
    requirement: TriggerRequirement,
    done: Boolean,
    restrictedSettingsMayApply: Boolean,
    onOpenDisclosure: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (done) MacroTheme.status.success else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(requirement.titleRes()), style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                Text(
                    stringResource(requirement.whyRes(restrictedSettingsMayApply)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!done) {
            when (requirement) {
                TriggerRequirement.ACCESSIBILITY_CONSENT ->
                    TextButton(onOpenDisclosure) { Text(stringResource(R.string.trg_access_consent_cta)) }
                TriggerRequirement.ACCESSIBILITY_SERVICE_ENABLED,
                TriggerRequirement.ACCESSIBILITY_SERVICE_CONNECTED,
                TriggerRequirement.GESTURE_DISPATCH,
                -> TextButton(onOpenAccessibilitySettings) { Text(stringResource(R.string.trg_access_settings_cta)) }
            }
        }
    }
}

private fun TriggerRequirement.titleRes(): Int = when (this) {
    TriggerRequirement.ACCESSIBILITY_CONSENT -> R.string.trg_req_consent
    TriggerRequirement.ACCESSIBILITY_SERVICE_ENABLED -> R.string.trg_req_enabled
    TriggerRequirement.ACCESSIBILITY_SERVICE_CONNECTED -> R.string.trg_req_connected
    TriggerRequirement.GESTURE_DISPATCH -> R.string.trg_req_gestures
}

private fun TriggerRequirement.whyRes(restricted: Boolean): Int = when (this) {
    TriggerRequirement.ACCESSIBILITY_CONSENT -> R.string.trg_req_consent_why
    TriggerRequirement.ACCESSIBILITY_SERVICE_ENABLED ->
        if (restricted) R.string.trg_req_enabled_why_restricted else R.string.trg_req_enabled_why
    TriggerRequirement.ACCESSIBILITY_SERVICE_CONNECTED -> R.string.trg_req_connected_why
    TriggerRequirement.GESTURE_DISPATCH -> R.string.trg_req_gestures_why
}
