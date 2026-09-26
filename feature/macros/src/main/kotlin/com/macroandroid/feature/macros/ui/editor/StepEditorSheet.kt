package com.macroandroid.feature.macros.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Backoff
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.RetryPolicy
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.validation.ValidationIssue
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.macros.R
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private enum class FailureKind { ABORT, CONTINUE, JUMP }

/**
 * Bottom sheet editing one step: action parameters plus the common options (label, timeout, retry, on-failure).
 * Changes are local until "Done"; secure values are encrypted immediately through [storeSecret] because the
 * plaintext must never live in the macro model.
 */
@Composable
internal fun StepEditorSheet(
    step: MacroStep,
    issues: List<ValidationIssue>,
    variables: List<String>,
    labels: List<String>,
    storeSecret: suspend (param: String, plaintext: String, existing: SecureValueRef?) -> AppResult<SecureValueRef>,
    onDone: (MacroStep) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by remember(step.id) { mutableStateOf(step) }
    var secureDialog by remember { mutableStateOf(false) }
    var secureError by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(actionKindLabel(ActionKind.of(draft.action)), style = MaterialTheme.typography.titleLarge)
            issues.forEach { issue ->
                Text(
                    stringResource(ErrorMessages.titleRes(issue.code)) + (issue.detail?.let { ": $it" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ActionForm(
                action = draft.action,
                onChange = { draft = draft.copy(action = it) },
                variables = variables,
                onRequestSecure = { secureDialog = true },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.macro_step_options), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                draft.label.orEmpty(),
                { draft = draft.copy(label = it.take(MacroLimits.LABEL_MAX).ifBlank { null }) },
                label = { Text(stringResource(R.string.macro_step_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!draft.action.isContainer) {
                DurationField(
                    stringResource(R.string.macro_step_timeout),
                    draft.timeout,
                    { draft = draft.copy(timeout = it) },
                    allowEmpty = true,
                    minMillis = 1.seconds.inWholeMilliseconds,
                    maxMillis = 30.minutes.inWholeMilliseconds,
                )
                RetryEditor(draft.retry) { draft = draft.copy(retry = it) }
            }
            FailureEditor(draft.onFailure, labels) { draft = draft.copy(onFailure = it) }
            LabeledSwitch(stringResource(R.string.macro_step_continue_on_cancel), draft.continueOnCancel) {
                draft = draft.copy(continueOnCancel = it)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = {
                    scope.launch { sheet.hide() }.invokeOnCompletion { onDone(draft) }
                }) { Text(stringResource(R.string.macro_done)) }
            }
        }
    }

    if (secureDialog) {
        val existing = (draft.action as? ActionParameters.EnterText)?.text as? TextValue.Secure
        SecureValueDialog(
            error = secureError,
            onDismiss = { secureDialog = false; secureError = null },
            onSubmit = { plaintext ->
                scope.launch {
                    when (val r = storeSecret("text", plaintext, existing?.ref)) {
                        is AppResult.Ok -> {
                            val a = draft.action
                            if (a is ActionParameters.EnterText) {
                                draft = draft.copy(action = a.copy(text = TextValue.Secure(r.value), sensitive = true))
                            }
                            secureDialog = false
                            secureError = null
                        }
                        is AppResult.Err -> secureError = ErrorMessages.titleRes(r.error)
                    }
                }
            },
        )
    }
}

@Composable
private fun SecureValueDialog(error: Int?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.macro_secure_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.macro_secure_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value,
                    { value = it.take(MacroLimits.LITERAL_MAX) },
                    label = { Text(stringResource(R.string.macro_secure_value)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }, enabled = value.isNotEmpty()) {
                Text(stringResource(R.string.macro_secure_store))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun RetryEditor(retry: RetryPolicy?, onChange: (RetryPolicy?) -> Unit) {
    LabeledSwitch(stringResource(R.string.macro_step_retry), retry != null) {
        onChange(if (it) RetryPolicy(maxAttempts = 2) else null)
    }
    val r = retry ?: return
    IntField(
        stringResource(R.string.macro_step_attempts),
        r.maxAttempts,
        { onChange(r.copy(maxAttempts = it ?: 1)) },
        range = 1..MacroLimits.RETRY_ATTEMPTS_MAX,
    )
    EnumDropdown(stringResource(R.string.macro_step_backoff), r.backoff, Backoff.entries, { onChange(r.copy(backoff = it)) })
    DurationField(stringResource(R.string.macro_step_initial_delay), r.initialDelay, {
        it?.let { d -> onChange(r.copy(initialDelay = d)) }
    }, minMillis = 100, maxMillis = 60.seconds.inWholeMilliseconds)
}

@Composable
private fun FailureEditor(behavior: FailureBehavior, labels: List<String>, onChange: (FailureBehavior) -> Unit) {
    val kind = when (behavior) {
        FailureBehavior.AbortMacro -> FailureKind.ABORT
        FailureBehavior.Continue -> FailureKind.CONTINUE
        is FailureBehavior.JumpToLabel -> FailureKind.JUMP
    }
    EnumDropdown(stringResource(R.string.macro_step_on_failure), kind, FailureKind.entries, { k ->
        onChange(
            when (k) {
                FailureKind.ABORT -> FailureBehavior.AbortMacro
                FailureKind.CONTINUE -> FailureBehavior.Continue
                FailureKind.JUMP -> FailureBehavior.JumpToLabel(labels.firstOrNull() ?: "")
            },
        )
    }) {
        stringResource(
            when (it) {
                FailureKind.ABORT -> R.string.macro_failure_abort
                FailureKind.CONTINUE -> R.string.macro_failure_continue
                FailureKind.JUMP -> R.string.macro_failure_jump
            },
        )
    }
    if (behavior is FailureBehavior.JumpToLabel) {
        VariableNameField(stringResource(R.string.macro_step_jump_label), behavior.label, labels) {
            onChange(FailureBehavior.JumpToLabel(it))
        }
    }
}
