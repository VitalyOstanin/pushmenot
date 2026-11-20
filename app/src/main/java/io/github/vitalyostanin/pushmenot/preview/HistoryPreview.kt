package io.github.vitalyostanin.pushmenot.preview

import com.pushmenot.core.Decision
import com.pushmenot.core.FilterRule
import com.pushmenot.core.NotificationEvent
import com.pushmenot.core.Protection
import com.pushmenot.core.ReplayInput
import com.pushmenot.core.RuleEngine
import io.github.vitalyostanin.pushmenot.storage.JournalItem

data class PreviewEntry(
    val journalId: Long,
    val decision: Decision,
    val complete: Boolean,
    val previousDecision: Decision? = null,
)

data class RulePreview(val entries: List<PreviewEntry>, val limitedTo: Int? = null)

object HistoryPreview {
    /** Rows should include any gaps in the selected time range. */
    fun evaluate(rows: List<JournalItem>, rules: List<FilterRule>): List<PreviewEntry> {
        val ids = mutableListOf<Long>()
        val inputs = buildList {
            for (row in rows.sortedBy(JournalItem::id)) {
                if (row.kind == JournalItem.KIND_GAP) {
                    add(ReplayInput.Reset)
                    continue
                }
                val profile = row.profileId ?: continue
                val source = row.packageName ?: continue
                val elapsed = row.elapsedMillis ?: continue
                val protection = row.protection?.let { Protection.valueOf(it) }
                ids += row.id
                add(
                    ReplayInput.Card(
                        NotificationEvent(
                            profileId = profile,
                            packageName = source,
                            elapsedMillis = elapsed,
                            channelId = row.channelId,
                            title = row.title,
                            body = row.body,
                            sender = row.sender,
                            senderKey = row.senderKey,
                            countAsNew = row.countAsNew,
                            protection = protection,
                            contentAvailable = row.contentAvailable && row.contentRetained,
                        ),
                    ),
                )
            }
        }
        return ids.zip(RuleEngine.replay(inputs, rules)) { id, result ->
            PreviewEntry(id, result.decision, result.complete)
        }
    }
}
