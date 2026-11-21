package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.transfer.HistoryExportOptions
import kotlinx.coroutines.CancellationException

@Composable
internal fun HistoryExportScreen(
    packages: Set<String>,
    labels: Map<String, String>,
    count: suspend (HistoryExportOptions) -> Int,
    save: (HistoryExportOptions) -> Unit,
    back: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(1) }
    var titles by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(false) }
    var images by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val untilMillis = rememberSaveable { System.currentTimeMillis() }
    val options = HistoryExportOptions(selected.takeIf(String::isNotEmpty), days, titles, text, images, untilMillis)
    val countResult by produceState<Result<Int>?>(null, selected, days, untilMillis) {
        value = null
        value = try {
            Result.success(count(options))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    Column {
        ScreenHeader(stringResource(R.string.history_export), back)
        LazyColumn(
            modifier = Modifier.testTag("history_export_list"),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel {
                    Choice(
                        R.string.apps, selected, listOf("") + packages.sortedBy { labels[it] ?: it },
                        { if (it.isEmpty()) stringResource(R.string.all_apps) else labels[it] ?: it },
                        { selected = it },
                    )
                    Choice(R.string.period_field, days, listOf(1, 7), {
                        stringResource(if (it == 1) R.string.history_export_day else R.string.history_export_week)
                    }) { days = it }
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.history_export_fields))
                    Help(stringResource(R.string.history_export_metadata))
                    SettingSwitch(R.string.history_export_titles, titles, "export_titles") { titles = it }
                    SettingSwitch(R.string.history_export_text, text, "export_text") { text = it }
                    SettingSwitch(R.string.history_export_images, images, "export_images") { images = it }
                }
            }
            if (options.includesContent) {
                item { Notice(R.string.history_export_sensitive, warning = true) {} }
            }
            item {
                Panel {
                    Title(stringResource(R.string.history_export_preview))
                    val result = countResult
                    if (result == null) {
                        Help(stringResource(R.string.history_export_counting))
                    } else {
                        val total = result.getOrNull()
                        Text(if (total == null) stringResource(R.string.operation_failed)
                            else stringResource(R.string.history_export_count, total))
                    }
                }
            }
            item {
                ActionButton(
                    R.string.history_export_choose_file,
                    { if (options.includesContent) confirming = true else save(options) },
                    enabled = (countResult?.getOrNull() ?: 0) > 0,
                    tag = "history_export_save",
                )
            }
        }
    }
    if (confirming) {
        Confirmation(
            R.string.history_export_confirm_title,
            R.string.history_export_sensitive,
            R.string.history_export_choose_file,
            confirm = {
                confirming = false
                save(options)
            },
            cancel = { confirming = false },
        )
    }
}
