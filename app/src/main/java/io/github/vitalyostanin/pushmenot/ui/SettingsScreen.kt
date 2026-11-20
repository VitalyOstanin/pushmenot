package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcutState
import io.github.vitalyostanin.pushmenot.sources.InstalledSource

internal enum class SettingsSection(val itemIndex: Int) {
    ACCESS(3), BATTERY(4), BACKGROUND(5), APPS(8),
}

@Composable
internal fun SettingsScreen(
    state: ScreenState,
    callbacks: ScreenCallbacks,
    focusedPackage: String?,
    section: SettingsSection?,
    onBack: (() -> Unit)?,
) {
    var query by rememberSaveable { mutableStateOf(focusedPackage.orEmpty()) }
    var showFields by rememberSaveable { mutableStateOf(false) }
    var editCapacity by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var turnOff by rememberSaveable { mutableStateOf(false) }
    val settings = remember(state.sources) { state.sources.associateBy { it.packageName } }
    val installed = remember(state.installedApps) { state.installedApps.mapTo(hashSetOf()) { it.packageName } }
    val apps = remember(state.installedApps, settings, state.historyApps, query) {
        val known = settings.keys + state.historyApps
        (state.installedApps + (known - installed).sorted().map { InstalledSource(it, it) })
            .filter { it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }
    }
    val capacity = state.journalCapacity
    val retainContent = state.retainContent
    val ready = state.accessGranted && state.listenerConnected
    val target = section ?: SettingsSection.APPS.takeIf { focusedPackage != null }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = target?.itemIndex ?: 0)
    LaunchedEffect(target) {
        if (target != null) scroll.scrollToItem(target.itemIndex)
    }

    Column {
        ScreenHeader(stringResource(R.string.settings), onBack)
        LazyColumn(
            state = scroll,
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("settings_content"),
        ) {
            item { Title(stringResource(R.string.history)) }
            item {
                Panel {
                    SettingSwitch(R.string.retain_content, retainContent, "retain_content") { enable ->
                        if (enable) callbacks.saveContentRetention(true) else turnOff = true
                    }
                    if (retainContent != null) {
                        Help(stringResource(when {
                            !retainContent -> R.string.retain_content_off
                            !ready -> R.string.retain_content_wait
                            else -> R.string.retain_content_on
                        }))
                        TextButton(onClick = { showFields = !showFields }) {
                            Text(stringResource(if (showFields) R.string.saved_fields_hide else R.string.saved_fields))
                        }
                        if (showFields) {
                            Help(stringResource(if (retainContent) R.string.saved_fields_on else R.string.saved_fields_off))
                        }
                    }
                    if (capacity != null) {
                        JournalCapacityButton(true) { editCapacity = true }
                        Help(capacityLabel(capacity))
                    }
                    ActionButton(R.string.clear_history, { clear = true }, primary = false, tag = "clear_history")
                    state.historyShortcut?.let { shortcut ->
                        Title(stringResource(R.string.history_shortcut_setting))
                        val (description, action) = when (shortcut) {
                            HistoryShortcutState.PERMISSION_OFF -> R.string.history_shortcut_permission_off to R.string.history_shortcut_allow
                            HistoryShortcutState.CHANNEL_OFF -> R.string.history_shortcut_channel_off to R.string.history_shortcut_open_system
                            HistoryShortcutState.NOT_SHOWN -> R.string.history_shortcut_not_shown to R.string.history_shortcut_show
                            HistoryShortcutState.SHOWN -> R.string.history_shortcut_shown to null
                        }
                        Help(stringResource(description))
                        if (action != null) {
                            ActionButton(action, callbacks.configureHistoryShortcut, primary = false, tag = "history_shortcut_configure")
                        }
                    }

                }
            }
            item { Title(stringResource(R.string.android_settings)) }
            item {
                Panel {
                    Title(stringResource(R.string.notification_access))
                    Help(stringResource(when {
                        !state.accessGranted -> R.string.history_access_off
                        !ready -> R.string.listener_not_ready_help
                        else -> R.string.access_ready
                    }))
                    if (state.accessGranted && !ready) Help(stringResource(R.string.listener_restart_help))
                    ActionButton(R.string.open_access, callbacks.openAccess, primary = false, tag = "android_access_settings")
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.battery_optimization))
                    Help(stringResource(when (state.backgroundStatus?.ignoresBatteryOptimizations) {
                        true -> R.string.battery_exempt
                        false -> R.string.battery_optimization_help
                        null -> R.string.android_status_unavailable
                    }))
                    ActionButton(
                        R.string.open_battery_optimization_settings, callbacks.openBatteryOptimizations,
                        primary = false, tag = "android_battery_settings",
                    )
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.background_activity))
                    Help(stringResource(when (state.backgroundStatus?.restricted) {
                        true -> R.string.background_restricted_help
                        false -> R.string.background_unrestricted
                        null -> R.string.android_status_unavailable
                    }))
                    ActionButton(
                        R.string.open_background_app_settings, callbacks.openBackgroundAppSettings,
                        primary = false, tag = "android_background_settings",
                    )
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.app_language))
                    ActionButton(R.string.open_language_settings, callbacks.openLanguageSettings, primary = false)
                }
            }
            item {
                UpdateSettings(state, callbacks)
            }
            item {
                Title(stringResource(R.string.apps))
                Help(stringResource(R.string.filter_settings_help))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_apps)) },
                    modifier = Modifier.fillMaxWidth().testTag("source_search"),
                    singleLine = true,
                    shape = buttonShape,
                )
            }
            if (apps.isEmpty()) item { Help(stringResource(R.string.no_apps)) }
            items(apps, key = InstalledSource::packageName) { app ->
                val filtering = settings[app.packageName]?.enabled == true
                val available = app.packageName in installed
                Panel(Modifier.testTag("source_row_${app.packageName}")) {
                    Title(app.label)
                    if (app.label != app.packageName) Help(app.packageName)
                    SettingSwitch(R.string.filter_notifications, filtering, "source_filter_${app.packageName}") {
                        callbacks.updateSource(app.packageName, it)
                    }
                    if (filtering && !ready) Help(stringResource(R.string.filtering_selected_paused))
                    if (available) {
                        ActionButton(
                            R.string.android_notification_settings, { callbacks.openSourceSettings(app.packageName) },
                            primary = false, tag = "source_settings_${app.packageName}",
                        )
                    } else {
                        Help(stringResource(R.string.app_settings_unavailable))
                    }
                }
            }
        }
    }

    if (editCapacity && capacity != null) {
        JournalCapacityDialog(capacity, save = {
            editCapacity = false
            callbacks.saveJournalCapacity(it)
        }, cancel = { editCapacity = false })
    }
    if (clear) {
        Confirmation(R.string.clear_history, R.string.confirm_clear_history, R.string.clear_action,
            confirm = { clear = false; callbacks.clearHistory() }, cancel = { clear = false })
    }
    if (turnOff && retainContent == true) {
        Confirmation(R.string.confirm_retention_off, R.string.confirm_retention_off_help, R.string.retention_off_action,
            confirm = { turnOff = false; callbacks.saveContentRetention(false) }, cancel = { turnOff = false })
    }
}

@Composable
private fun SettingSwitch(title: Int, checked: Boolean?, tag: String, toggle: (Boolean) -> Unit) {
    val label = stringResource(title)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked = checked == true,
            enabled = checked != null,
            onCheckedChange = toggle,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label },
        )
    }
}
