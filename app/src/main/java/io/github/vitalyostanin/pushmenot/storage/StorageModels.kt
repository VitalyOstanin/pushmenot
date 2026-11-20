package io.github.vitalyostanin.pushmenot.storage

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceSettings(
    @PrimaryKey val packageName: String,
    val enabled: Boolean = true,
    val consentRevision: Long = 0,
)

@Entity(
    tableName = "journal",
    indices = [
        Index(value = ["observedAtMillis", "id"]),
        Index("changedRevision"),
        Index(value = ["packageName", "observedAtMillis", "id"]),
        Index(value = ["packageName", "channelId", "observedAtMillis", "id"]),
        Index(value = ["verdict", "observedAtMillis", "id"]),
    ],
)
data class JournalItem(
    val observedAtMillis: Long,
    val kind: String = KIND_NOTIFICATION,
    val profileId: String? = null,
    val packageName: String? = null,
    val channelId: String? = null,
    // The adapter supplies an opaque token. Android keys can contain app-provided tags.
    val lifecycleId: String? = null,
    val generation: Long? = null,
    val revision: Long? = null,
    val elapsedMillis: Long? = null,
    val countAsNew: Boolean = false,
    val contentAvailable: Boolean = true,
    val contentRetained: Boolean = false,
    val protection: String? = null,
    val title: String? = null,
    val body: String? = null,
    val sender: String? = null,
    val verdict: String? = null,
    val decisionReason: String? = null,
    val senderKey: String? = null,
    val ruleId: String? = null,
    val ruleRevision: Long? = null,
    val ruleScope: String? = null,
    val rulesVersion: Long? = null,
    val dismissalRequestedAtMillis: Long? = null,
    val removedAtMillis: Long? = null,
    val removalReason: Int? = null,
    val gapEndedAtMillis: Long? = null,
    val searchText: String? = null,
    val senderSearch: String? = null,
    val archivedText: String? = null,
    @ColumnInfo(defaultValue = "0") val changedRevision: Long = 0,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
) {
    companion object {
        const val KIND_NOTIFICATION = "notification"
        const val KIND_GAP = "gap"
    }
}

data class HistoryCursor(val observedAtMillis: Long, val id: Long)

data class HistoryQuery(
    val before: HistoryCursor? = null,
    val packageNames: Set<String> = emptySet(),
    val includeGaps: Boolean = false,
    val sinceMillis: Long? = null,
    val untilMillis: Long? = null,
    val packageName: String? = null,
    val channelId: String? = null,
    val sender: String? = null,
    val verdict: String? = null,
    val search: String? = null,
    val limit: Int = 100,
)

internal fun HistoryQuery.includes(row: JournalItem): Boolean {
    if (before != null && (row.observedAtMillis > before.observedAtMillis ||
            row.observedAtMillis == before.observedAtMillis && row.id >= before.id)
    ) return false
    val since = sinceMillis ?: Long.MIN_VALUE
    if (row.observedAtMillis > (untilMillis ?: Long.MAX_VALUE)) return false
    if (row.kind == JournalItem.KIND_GAP) {
        return includeGaps && (row.observedAtMillis >= since || row.gapEndedAtMillis?.let { it >= since } != false)
    }
    if (row.kind != JournalItem.KIND_NOTIFICATION || row.observedAtMillis < since) return false
    val packages = packageNames + listOfNotNull(packageName)
    if (packages.isNotEmpty() && row.packageName !in packages) return false
    if (channelId != null && row.channelId != channelId || verdict != null && row.verdict != verdict) return false
    val senderNeedle = sender?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
    if (senderNeedle != null && row.senderSearch != senderNeedle) return false
    val searchNeedle = search?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
    return searchNeedle == null || row.searchText?.contains(searchNeedle) == true
}

@Entity(tableName = "rule_set")
data class RuleSet(
    val version: Long = 0,
    val rulesJson: String = "[]",
    val previousRulesJson: String? = null,
    val activeRevisionId: String? = null,
    @PrimaryKey val singleton: Int = 0,
)

@Entity(tableName = "frequency_snapshot")
data class FrequencySnapshot(
    // Only counters, monotonic timestamps, and opaque keys belong in the payload.
    val payload: String,
    @PrimaryKey val singleton: Int = 0,
)

@Entity(tableName = "journal_settings")
internal data class JournalSettings(
    val capacity: Int = PushStorage.DEFAULT_JOURNAL_CAPACITY,
    @ColumnInfo(defaultValue = "1") val retainContent: Boolean = true,
    @ColumnInfo(defaultValue = "0") val retentionRevision: Long = 0,
    @PrimaryKey val singleton: Int = 0,
)

@Entity(
    tableName = "journal_media",
    foreignKeys = [ForeignKey(
        entity = JournalItem::class,
        parentColumns = ["id"],
        childColumns = ["journalId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class JournalMedia(
    @PrimaryKey val journalId: Long,
    val image: ByteArray,
)

@Entity(tableName = "rule_history", indices = [Index(value = ["revisionId"], unique = true)])
data class RuleSnapshot(
    val revisionId: String,
    val createdAtMillis: Long?,
    val reason: String,
    val count: Int,
    val rulesJson: String,
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
)

data class RuleVersionSummary(
    val revisionId: String,
    val createdAtMillis: Long?,
    val reason: String,
    val count: Int,
)

@Entity(tableName = "rule_revision_floor")
internal data class RuleRevisionFloor(
    @PrimaryKey val ruleId: String,
    val revision: Long,
)

@Entity(tableName = "notification_counts", indices = [Index("receivedAtMillis")])
internal data class NotificationCount(
    @PrimaryKey val lifecycleId: String,
    val receivedAtMillis: Long,
    val dismissalRequested: Boolean = false,
    val hidden: Boolean = false,
)

@Entity(tableName = "statistics_start")
internal data class StatisticsStart(
    val startedAtMillis: Long,
    @PrimaryKey val singleton: Int = 0,
)

data class NotificationTotals(val received: Int, val hidden: Int)
data class NotificationStatistics(
    val today: NotificationTotals,
    val week: NotificationTotals,
    val startedAtMillis: Long,
)

@Entity(tableName = "journal_state")
internal data class JournalState(
    val actualCount: Int = 0,
    val revision: Long = 0,
    val resetRevision: Long = 0,
    @PrimaryKey val singleton: Int = 0,
)

data class JournalDelta(
    val revision: Long,
    val oldestId: Long?,
    val changedIds: Set<Long>,
    val matchingRows: List<JournalItem>,
    val requiresReload: Boolean,
)
