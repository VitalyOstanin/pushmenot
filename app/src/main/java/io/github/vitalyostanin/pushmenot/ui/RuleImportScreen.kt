package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.transfer.ImportMode
import io.github.vitalyostanin.pushmenot.transfer.ImportPreview

@Composable
internal fun ImportScreen(
    preview: ImportPreview,
    labels: Map<String, String>,
    enabledApps: Set<String>,
    confirm: (ImportMode, Boolean) -> Unit,
    cancel: () -> Unit,
) {
    var mode by remember(preview) { mutableStateOf(ImportMode.APPEND) }
    val append = mode == ImportMode.APPEND
    val packages = preview.enabledApps ?: preview.legacyApps
    val legacy = preview.enabledApps == null
    var importApps by remember(preview) { mutableStateOf(!legacy) }
    val willEnableApps = importApps && packages.any { it !in enabledApps }
    Column {
        ScreenHeader(stringResource(R.string.review_import))
        LazyColumn(
            modifier = Modifier.testTag("import_preview_list"),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel {
                    Title(stringResource(R.string.import_mode))
                    Column(Modifier.selectableGroup()) {
                        ImportChoice(R.string.import_append_mode, R.string.import_append_help,
                            append, "import_mode_append") { mode = ImportMode.APPEND }
                        ImportChoice(R.string.import_replace_mode, R.string.import_replace_help,
                            !append, "import_mode_replace") { mode = ImportMode.REPLACE }
                    }
                }
            }
            item {
                Panel {
                    Text(stringResource(R.string.import_add, if (append) preview.appendedIds.size else preview.addedIds.size))
                    if (append) {
                        Text(stringResource(R.string.import_skipped, preview.skippedCount))
                        if (preview.appendedModes > 0) Help(stringResource(R.string.import_append_modes, preview.appendedModes))
                        if (!preview.appendFits) Help(stringResource(R.string.import_append_limit))
                        else if (!preview.canAppend) Help(stringResource(R.string.import_no_new))
                    } else {
                        RuleDifference(preview, labels)
                    }
                }
            }
            if (packages.isNotEmpty()) {
                item {
                    Panel {
                        Title(stringResource(if (legacy) R.string.import_legacy_apps else R.string.import_file_apps))
                        if (legacy) Help(stringResource(R.string.import_legacy_apps_help))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(importApps, role = Role.Checkbox, onValueChange = { importApps = it }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = importApps, onCheckedChange = null)
                            Text(stringResource(if (legacy) R.string.import_enable_legacy_apps else R.string.import_enable_apps))
                        }
                    }
                }
                if (importApps) {
                    items(packages.sorted()) { packageName ->
                        Panel {
                            Text(labels[packageName] ?: packageName)
                            Help(stringResource(when {
                                packageName !in labels -> R.string.import_app_pending_install
                                packageName in enabledApps -> R.string.import_app_already_enabled
                                else -> R.string.import_app_will_enable
                            }))
                        }
                    }
                }
            }
            item { Help(stringResource(R.string.import_active_review_help)) }
            item {
                ActionButton(
                    if (append) R.string.append_rules else R.string.replace_rules,
                    { confirm(mode, importApps) },
                    enabled = !append || (preview.appendFits && (preview.canAppend || willEnableApps)),
                    tag = if (append) "rule_append" else "rule_replace",
                )
            }
            item { ActionButton(R.string.cancel, cancel, primary = false) }
        }
    }
}

@Composable
private fun ImportChoice(title: Int, help: Int, checked: Boolean, tag: String, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .selectable(checked, role = Role.RadioButton, onClick = select),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = checked, onClick = null)
        Column {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Help(stringResource(help))
        }
    }
}

@Composable
internal fun RuleDifference(preview: ImportPreview, labels: Map<String, String>) {
    Text(stringResource(R.string.import_update, preview.changedIds.size))
    if (preview.modesChanged) Help(stringResource(R.string.import_replace_modes))
    Text(stringResource(R.string.import_remove, preview.removedIds.size))
    if (preview.orderChanged) Help(stringResource(R.string.import_order))
    if (preview.scopeChangedIds.isNotEmpty()) Help(stringResource(R.string.import_scope_changes, preview.scopeChangedIds.size))
    if (preview.allSelectedAppsAffected) Help(stringResource(R.string.import_global_impact))
    if (preview.affectedPackageNames.isNotEmpty()) {
        val apps = preview.affectedPackageNames.map { labels[it] ?: it }.sorted()
        Help(stringResource(R.string.import_local_impact, apps.joinToString(", ")))
    }
}
