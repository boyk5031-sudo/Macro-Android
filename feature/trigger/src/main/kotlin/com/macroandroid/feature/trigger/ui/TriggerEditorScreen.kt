package com.macroandroid.feature.trigger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroandroid.automation.trigger.CoordinateConverter
import com.macroandroid.automation.trigger.CoordinateSpace
import com.macroandroid.automation.trigger.DisplayCompatibility
import com.macroandroid.automation.trigger.ExecutionMode
import com.macroandroid.automation.trigger.TargetActionType
import com.macroandroid.automation.trigger.TargetPoint
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.platform.DisplayGeometryReader
import com.macroandroid.core.ui.component.LoadingState
import com.macroandroid.core.ui.component.MacroTopBar
import com.macroandroid.core.ui.error.ErrorMessages
import com.macroandroid.feature.trigger.R
import com.macroandroid.feature.trigger.presentation.TestPhase
import com.macroandroid.feature.trigger.presentation.TriggerEditorEvent
import com.macroandroid.feature.trigger.presentation.TriggerEditorUiState
import com.macroandroid.feature.trigger.presentation.TriggerEditorViewModel
import com.macroandroid.feature.trigger.presentation.TriggerEditorViewModel.Companion.px

@Composable
fun TriggerEditorRoute(
    onOpenDisclosure: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TriggerEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        DisplayGeometryReader.read(context)?.let(viewModel::onGeometry)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is TriggerEditorEvent.Saved -> onBack()
                is TriggerEditorEvent.Error -> snackbar.showSnackbar(context.getString(ErrorMessages.titleRes(e.error)))
                TriggerEditorEvent.TestSucceeded -> snackbar.showSnackbar(context.getString(R.string.trg_test_done))
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                MacroTopBar(
                    title = stringResource(if (state.isNew) R.string.trg_editor_new else R.string.trg_editor_edit),
                    onBack = onBack,
                ) {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.canSave,
                        modifier = Modifier.testTag(TriggerTestTags.EDITOR_SAVE),
                    ) { Text(stringResource(R.string.trg_save)) }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val config = state.config
            val geometry = state.geometry
            if (config == null || geometry == null) {
                LoadingState(Modifier.padding(padding))
            } else {
                EditorContent(state, config, viewModel, onOpenDisclosure, Modifier.padding(padding))
            }
        }
        TestScrim(state.testPhase, state.receivedTaps, onTap = viewModel::onScrimTap, onCancel = viewModel::cancelTest)
    }
}

@Composable
private fun EditorContent(
    state: TriggerEditorUiState,
    config: TriggerConfiguration,
    viewModel: TriggerEditorViewModel,
    onOpenDisclosure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val geometry = checkNotNull(state.geometry)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { state.access?.let { AccessChecklist(it, onOpenDisclosure, Modifier.padding(16.dp)) } }
        item { CompatibilityNote(state.compatibility) }
        item {
            TriggerCanvas(
                config = config,
                geometry = geometry,
                selectedId = state.selectedPointId,
                callbacks = CanvasCallbacks(
                    onSelect = viewModel::selectPoint,
                    onAddPoint = { fx, fy -> viewModel.addPoint(fx, fy) },
                    onMoveArea = viewModel::moveArea,
                    onResizeArea = viewModel::resizeArea,
                    onMovePoint = viewModel::movePoint,
                ),
                areaLabel = stringResource(R.string.trg_canvas_area_label),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                tapAddsPoint = state.canAddPoint,
            )
        }
        item {
            Text(
                stringResource(R.string.trg_canvas_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item { Toolbar(state, viewModel) }
        item { ErrorsCard(state) }
        item { GeneralSection(state, config, viewModel) }
        item { AreaSection(config, viewModel) }
        item { ExecutionSection(config, viewModel) }
        item { SectionTitle(stringResource(R.string.trg_points_title, config.targetPoints.size)) }
        itemsIndexed(config.targetPoints, key = { _, p -> p.id.value }) { index, point ->
            PointRow(index, point, config, state.selectedPointId == point.id, viewModel)
        }
    }
}

@Composable
private fun Toolbar(state: TriggerEditorUiState, viewModel: TriggerEditorViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { viewModel.addPoint() }, enabled = state.canAddPoint) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.trg_add_point))
        }
        OutlinedButton(onClick = viewModel::testHere, enabled = state.canTest) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.trg_test_here))
        }
        Button(onClick = viewModel::testInGame, enabled = state.canTest) {
            Icon(Icons.Outlined.SportsEsports, contentDescription = null)
            Text(stringResource(R.string.trg_test_in_game))
        }
    }
}

@Composable
private fun CompatibilityNote(compatibility: DisplayCompatibility?) {
    val text = when (compatibility) {
        DisplayCompatibility.ORIENTATION_MISMATCH -> stringResource(R.string.trg_compat_orientation)
        DisplayCompatibility.ASPECT_DIFFERS -> stringResource(R.string.trg_compat_aspect)
        DisplayCompatibility.EXACT, null -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ErrorsCard(state: TriggerEditorUiState) {
    val errors = state.errors.filter { it.code != ErrorCode.NAME_INVALID || state.dirty }
    if (errors.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            errors.distinctBy { it.code }.forEach { e ->
                val target = e.context["target"]
                val base = stringResource(ErrorMessages.titleRes(e))
                Text(
                    if (target != null) stringResource(R.string.trg_error_for_target, target, base) else base,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun GeneralSection(state: TriggerEditorUiState, config: TriggerConfiguration, viewModel: TriggerEditorViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.trg_section_general))
        OutlinedTextField(
            value = config.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.trg_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        AppBindingPicker(state, config.packageName, viewModel::setPackage)
        SwitchRow(stringResource(R.string.trg_enabled), config.enabled, viewModel::setEnabled)
    }
}

@Composable
private fun AppBindingPicker(state: TriggerEditorUiState, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected == null -> stringResource(R.string.trg_any_app)
        else -> state.apps.firstOrNull { it.packageName == selected }?.label ?: selected
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.trg_bind_app)) },
            supportingText = { Text(stringResource(R.string.trg_bind_app_help)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trg_any_app)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            state.apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.label) },
                    onClick = {
                        onSelect(app.packageName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AreaSection(config: TriggerConfiguration, viewModel: TriggerEditorViewModel) {
    val d = config.authoredDisplay
    val a = config.triggerArea
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.trg_section_area, d.widthPx, d.heightPx))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(stringResource(R.string.trg_x), px(a.x, d.widthPx), Modifier.weight(1f)) {
                viewModel.setAreaPx(it, null, null, null)
            }
            IntField(stringResource(R.string.trg_y), px(a.y, d.heightPx), Modifier.weight(1f)) {
                viewModel.setAreaPx(null, it, null, null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(stringResource(R.string.trg_width), px(a.width, d.widthPx), Modifier.weight(1f)) {
                viewModel.setAreaPx(null, null, it, null)
            }
            IntField(stringResource(R.string.trg_height), px(a.height, d.heightPx), Modifier.weight(1f)) {
                viewModel.setAreaPx(null, null, null, it)
            }
        }
    }
}

@Composable
private fun ExecutionSection(config: TriggerConfiguration, viewModel: TriggerEditorViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.trg_section_execution))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ExecutionMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = config.executionMode == mode,
                    onClick = { viewModel.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, ExecutionMode.entries.size),
                ) {
                    val res = if (mode == ExecutionMode.SEQUENTIAL) R.string.trg_mode_sequential else R.string.trg_mode_multitouch
                    Text(stringResource(res))
                }
            }
        }
        val sequential = config.executionMode == ExecutionMode.SEQUENTIAL
        Text(
            stringResource(if (sequential) R.string.trg_mode_sequential_help else R.string.trg_mode_multitouch_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(stringResource(R.string.trg_cooldown_ms), config.cooldownMs.toInt(), Modifier.weight(1f)) {
                viewModel.setCooldown(it.toLong())
            }
            IntField(stringResource(R.string.trg_repeat_count), config.repeatCount, Modifier.weight(1f)) {
                viewModel.setRepeatCount(it)
            }
            IntField(stringResource(R.string.trg_repeat_delay_ms), config.repeatDelayMs.toInt(), Modifier.weight(1f)) {
                viewModel.setRepeatDelay(it.toLong())
            }
        }
        Text(stringResource(R.string.trg_indicator_override), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(
                null to R.string.trg_indicator_follow,
                true to R.string.trg_indicator_show,
                false to R.string.trg_indicator_hide,
            )
            options.forEach { (value, res) ->
                FilterChip(
                    selected = config.showIndicatorInGameplay == value,
                    onClick = { viewModel.setIndicatorOverride(value) },
                    label = { Text(stringResource(res)) },
                )
            }
        }
    }
}

@Composable
private fun PointRow(
    index: Int,
    point: TargetPoint,
    config: TriggerConfiguration,
    selected: Boolean,
    viewModel: TriggerEditorViewModel,
) {
    val d = config.authoredDisplay
    val (fx, fy) = CoordinateConverter.toDisplayFraction(point, config.triggerArea)
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Card(
        onClick = { viewModel.selectPoint(point.id) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.trg_point_n, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.reorderPoint(point.id, -1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.trg_move_up))
                }
                val notLast = index < config.targetPoints.lastIndex
                IconButton(onClick = { viewModel.reorderPoint(point.id, +1) }, enabled = notLast) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.trg_move_down))
                }
                IconButton(onClick = { viewModel.deletePoint(point.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.trg_delete_point))
                }
                Switch(checked = point.enabled, onCheckedChange = { viewModel.setPointEnabled(point.id, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField(stringResource(R.string.trg_x), px(fx, d.widthPx), Modifier.weight(1f)) {
                    viewModel.setPointPx(point.id, it, null)
                }
                IntField(stringResource(R.string.trg_y), px(fy, d.heightPx), Modifier.weight(1f)) {
                    viewModel.setPointPx(point.id, null, it)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TargetActionType.entries.forEach { action ->
                    FilterChip(
                        selected = point.actionType == action,
                        onClick = { viewModel.setPointAction(point.id, action) },
                        label = {
                            val res = if (action == TargetActionType.TAP) R.string.trg_action_tap else R.string.trg_action_long_press
                            Text(stringResource(res))
                        },
                    )
                }
                FilterChip(
                    selected = point.coordinateSpace == CoordinateSpace.TRIGGER_RELATIVE,
                    onClick = {
                        val next = if (point.coordinateSpace == CoordinateSpace.TRIGGER_RELATIVE) {
                            CoordinateSpace.DISPLAY
                        } else {
                            CoordinateSpace.TRIGGER_RELATIVE
                        }
                        viewModel.setPointSpace(point.id, next)
                    },
                    label = { Text(stringResource(R.string.trg_space_relative)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (config.executionMode == ExecutionMode.SEQUENTIAL) {
                    IntField(stringResource(R.string.trg_delay_before_ms), point.delayBeforeMs.toInt(), Modifier.weight(1f)) {
                        viewModel.setPointDelay(point.id, it.toLong())
                    }
                }
                IntField(stringResource(R.string.trg_hold_ms), point.effectiveHoldMs.toInt(), Modifier.weight(1f)) {
                    viewModel.setPointHold(point.id, it.toLong())
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** Integer field that follows external changes (drags) and pushes every valid keystroke back. */
@Composable
private fun IntField(label: String, value: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(MAX_DIGITS)
            text = digits
            digits.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.widthIn(min = 72.dp),
    )
}

/**
 * Covers the whole editor while a test runs (§17). Injected taps land here instead of on editor controls, and
 * every pointer-down is drawn as a ring – an end-to-end proof that the coordinates reach the display.
 */
@Composable
private fun TestScrim(
    phase: TestPhase,
    taps: List<Pair<Float, Float>>,
    onTap: (Float, Float) -> Unit,
    onCancel: () -> Unit,
) {
    if (phase == TestPhase.Idle) return
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTap(down.position.x, down.position.y)
                }
            }
            .drawBehind {
                taps.forEach { (x, y) ->
                    drawCircle(ring, radius = RING_RADIUS_DP.dp.toPx(), center = Offset(x, y), alpha = RING_ALPHA)
                    drawCircle(ring, radius = RING_RADIUS_DP.dp.toPx() / 3, center = Offset(x, y))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val text = when (phase) {
                    is TestPhase.Countdown -> pluralStringResource(R.plurals.trg_test_countdown, phase.secondsLeft, phase.secondsLeft)
                    TestPhase.RunningHere -> stringResource(R.string.trg_test_running_here)
                    TestPhase.RunningInGame -> stringResource(R.string.trg_test_running_game)
                    TestPhase.Idle -> ""
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
                if (taps.isNotEmpty()) {
                    val received = pluralStringResource(R.plurals.trg_test_received, taps.size, taps.size)
                    Text(received, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}

private const val MAX_DIGITS = 5
private const val SCRIM_ALPHA = 0.55f
private const val RING_ALPHA = 0.35f
private const val RING_RADIUS_DP = 28
