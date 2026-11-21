package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import io.github.vitalyostanin.pushmenot.transfer.RuleTransfer

@Composable
internal fun RulesScreen(
    rules: List<FilterRule>,
    labels: Map<String, String>,
    settings: Map<String, SourceSettings>,
    installed: Set<String>,
    canUndo: Boolean,
    versions: () -> Unit,
    help: () -> Unit,
    globals: () -> Unit,
    appRules: (String) -> Unit,
    callbacks: ScreenCallbacks,
) {
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    val packages = (
        settings.values.filter(SourceSettings::enabled).map(SourceSettings::packageName) +
            rules.mapNotNull(FilterRule::packageName)
        ).distinct().sortedBy { labels[it] ?: it }
    Column {
        ScreenHeader(stringResource(R.string.rules), action = {
            TextButton(onClick = help, modifier = Modifier.testTag("rules_help")) {
                Text(stringResource(R.string.rules_help))
            }
        })
        LazyColumn(
            modifier = Modifier.testTag("rules_list"),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ActionButton(R.string.global_rules, globals, primary = false, tag = "global_rules") }
            item { Title(stringResource(R.string.rules_by_app)) }
            if (packages.isEmpty()) item { Help(stringResource(R.string.no_app_rules_sources)) }
            items(packages, key = { it }) { packageName ->
                val label = labels[packageName] ?: packageName
                Panel(Modifier.clickable { appRules(packageName) }.testTag("rules_source_$packageName")) {
                    Title(label)
                    if (labels.values.count { it == label } > 1) Help(packageName)
                    if (packageName !in installed) Help(stringResource(R.string.app_not_installed))
                    if (settings[packageName]?.enabled != true) Help(stringResource(R.string.filtering_off))
                }
            }
            item { ActionButton(R.string.rule_version_history, versions, primary = false, tag = "rule_versions") }
            item { ActionButton(R.string.import_rules, callbacks.beginImport, primary = false, tag = "rule_import") }
            item { ActionButton(R.string.export_rules, { exportOpen = true }, primary = false, tag = "rule_export") }
            if (canUndo) item { ActionButton(R.string.undo_rules, callbacks.undoImport, primary = false) }
            item { Help(stringResource(R.string.rule_files_explanation)) }
        }
    }
    if (exportOpen) {
        Dialog(onDismissRequest = { exportOpen = false }) {
            Panel {
                Title(stringResource(R.string.export_rules_title))
                Text(RuleTransfer.NAME, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.export_rules_contents))
                ActionButton(R.string.export_rules_share, {
                    exportOpen = false
                    callbacks.shareRules()
                }, tag = "rule_export_share")
                ActionButton(R.string.export_rules_save, {
                    exportOpen = false
                    callbacks.beginExport()
                }, primary = false, tag = "rule_export_save")
                ActionButton(R.string.cancel, { exportOpen = false }, primary = false, tag = "rule_export_cancel")
            }
        }
    }
}

@Composable
internal fun RulesHelpScreen(back: () -> Unit) {
    Column {
        ScreenHeader(stringResource(R.string.rules_help_title), back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Title(stringResource(R.string.rules_help_applies))
                    Text(stringResource(R.string.rules_help_applies_body))
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.rules_help_priority))
                    listOf(
                        R.string.rules_help_app_keep,
                        R.string.rules_help_global_keep,
                        R.string.rules_help_app_hide,
                        R.string.rules_help_global_hide,
                    ).forEachIndexed { index, label ->
                        Text(stringResource(R.string.rules_help_priority_item, index + 1, stringResource(label)))
                    }
                    Help(stringResource(R.string.rules_help_ties))
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.rules_help_example))
                    Text(stringResource(R.string.rules_help_example_body))
                }
            }
        }
    }
}

@Composable
internal fun ScopedRulesScreen(
    packageName: String?,
    rules: List<FilterRule>,
    labels: Map<String, String>,
    selectedPackages: Set<String>,
    edit: (FilterRule) -> Unit,
    openUnmatchedMode: () -> Unit,
    create: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val globals = rules.filter { it.packageName == null }
    val localRules = rules.filter { packageName != null && it.packageName == packageName }
    val title = if (packageName == null) stringResource(R.string.global_rules) else {
        stringResource(R.string.app_rules_title, labels[packageName] ?: packageName)
    }
    Column {
        ScreenHeader(title, onBack)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (packageName != null) {
                item { ActionButton(R.string.unmatched_title, openUnmatchedMode, primary = false, tag = "unmatched_mode") }
                item { Title(stringResource(R.string.app_rules)) }
                item { ActionButton(R.string.new_rule, { create(packageName) }, tag = "rule_new") }
                if (localRules.isEmpty()) item { Help(stringResource(R.string.no_rules)) }
                items(localRules, key = FilterRule::id) { RuleRow(it, edit) }
                item { Title(stringResource(R.string.global_rules)) }
                item {
                    Help(stringResource(
                        if (packageName in selectedPackages) R.string.global_scope_help
                        else R.string.global_rules_when_filtering,
                    ))
                }
                if (globals.isEmpty()) {
                    item {
                        Help(stringResource(R.string.no_rules))
                        ActionButton(R.string.new_global_rule, { create(null) }, primary = false)
                    }
                }
            } else {
                item { Help(stringResource(R.string.global_scope_help)) }
                item { ActionButton(R.string.new_rule, { create(null) }, tag = "rule_new") }
                if (globals.isEmpty()) item { Help(stringResource(R.string.no_rules)) }
            }
            items(globals, key = FilterRule::id) { RuleRow(it, edit) }
        }
    }
}

@Composable
private fun RuleRow(rule: FilterRule, edit: (FilterRule) -> Unit) {
    Panel(Modifier.clickable { edit(rule) }) {
        RuleSummary(rule)
        if (!rule.enabled) Help(stringResource(R.string.rule_disabled))
    }
}
