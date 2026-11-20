package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import java.text.NumberFormat

private val capacities = listOf(1_000, 5_000, PushStorage.DEFAULT_JOURNAL_CAPACITY, 25_000, PushStorage.MAX_JOURNAL_CAPACITY)

@Composable
internal fun JournalCapacityButton(enabled: Boolean, open: () -> Unit) {
    OutlinedButton(
        onClick = open,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag("journal_capacity"),
    ) {
        Text(stringResource(R.string.journal_capacity))
    }
}

@Composable
internal fun capacityLabel(capacity: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    return stringResource(R.string.journal_capacity_entries, format.format(capacity))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun JournalCapacityDialog(capacity: Int, save: (Int) -> Unit, cancel: () -> Unit) {
    var selected by rememberSaveable(capacity) { mutableIntStateOf(capacity) }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    AlertDialog(
        onDismissRequest = cancel,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(stringResource(R.string.journal_capacity)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Help(stringResource(R.string.journal_capacity_help))
                Column(Modifier.selectableGroup()) {
                    for (option in capacities) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("journal_capacity_$option")
                                .selectable(selected == option, role = Role.RadioButton, onClick = { selected = option }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected == option, onClick = null)
                            Text(stringResource(R.string.journal_capacity_entries, format.format(option)))
                        }
                    }
                }
                if (selected < capacity) {
                    Notice(R.string.journal_capacity_reduce, warning = true) {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { save(selected) },
                enabled = selected != capacity,
                modifier = Modifier.testTag("journal_capacity_save"),
            ) {
                Text(stringResource(R.string.save_journal_capacity))
            }
        },
        dismissButton = {
            TextButton(onClick = cancel, modifier = Modifier.testTag("journal_capacity_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
