package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.NotificationStatistics
import io.github.vitalyostanin.pushmenot.storage.SourceSettings

@Composable
internal fun AccessScreen(statistics: NotificationStatistics?, open: () -> Unit) {
    Column {
        ScreenHeader(stringResource(R.string.access_title))
        Column(Modifier.padding(screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            statistics?.let { JournalStatistics(it) }
            Notice(R.string.access_off, warning = true) { Help(stringResource(R.string.access_description)) }
            ActionButton(R.string.settings, onClick = open, tag = "access_settings")
        }
    }
}

@Composable
internal fun SelectedAppsScreen(
    settings: Map<String, SourceSettings>,
    labels: Map<String, String>,
    installed: Set<String>,
    add: () -> Unit,
    open: (String) -> Unit,
) {
    val packages = settings.values.filter(SourceSettings::enabled).map(SourceSettings::packageName)
        .sortedBy { labels[it] ?: it }
    Column {
        ScreenHeader(stringResource(R.string.apps))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Title(stringResource(R.string.selected_app)) }
            item { ActionButton(R.string.add_app, add, tag = "add_app") }
            if (packages.isEmpty()) item { Help(stringResource(R.string.no_selected_apps)) }
            items(packages, key = { it }) { packageName ->
                SourceRow(packageName, labels, installed, open)
            }
        }
    }
}

@Composable
private fun SourceRow(
    packageName: String,
    labels: Map<String, String>,
    installed: Set<String>,
    open: (String) -> Unit,
) {
    val label = labels[packageName] ?: packageName
    Panel(Modifier.testTag("source_row_$packageName").clickable { open(packageName) }) {
        Title(label)
        if (packageName !in installed) Help(stringResource(R.string.app_not_installed))
        if (labels.values.count { it == label } > 1) Help(packageName)
    }
}

@Composable
internal fun SourceDetail(
    packageName: String,
    label: String,
    settings: SourceSettings?,
    openSettings: () -> Unit,
    openHistory: () -> Unit,
    openRules: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        ScreenHeader(label, onBack)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Help(packageName)
                Help(stringResource(if (settings?.enabled == true) R.string.selected_app else R.string.filtering_off))
            }
            item { ActionButton(R.string.app_history, openHistory, primary = false, tag = "app_history") }
            item { ActionButton(R.string.app_rules, openRules, primary = false, tag = "app_rules") }
            item { ActionButton(R.string.filtering_settings, openSettings, primary = false, tag = "filtering_settings") }
        }
    }
}
