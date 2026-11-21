package io.github.vitalyostanin.pushmenot.storage

import androidx.room.withTransaction
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PushStorage(private val database: PushDatabase) {
    private val dao = database.data()
    val ruleHistory = RuleHistory(database)

    suspend fun source(packageName: String): SourceSettings? = dao.source(packageName)

    fun observeSources(): Flow<List<SourceSettings>> = dao.observeSources()

    suspend fun setSource(packageName: String, enabled: Boolean): SourceSettings =
        database.withTransaction {
            require(packageName.isNotBlank())
            val previous = dao.source(packageName)
            if (previous != null && previous.enabled == enabled) return@withTransaction previous
            val next = SourceSettings(
                packageName = packageName,
                enabled = enabled,
                consentRevision = (previous?.consentRevision ?: 0) + 1,
            )
            dao.saveSource(next)
            if (previous?.enabled == true && !enabled) clearSourceFrequency(packageName)
            next
        }

    private suspend fun clearSourceFrequency(packageName: String) {
        val stored = dao.frequency() ?: return
        val state = FrequencyJson.decode(stored.payload)
        val remaining = state.windows.filterKeys { it.packageName != packageName }
        if (remaining.size == state.windows.size) return
        val payload = FrequencyJson.encode(state.copy(windows = remaining))
        if (payload == null) dao.clearFrequency() else dao.saveFrequency(FrequencySnapshot(payload))
    }

    suspend fun appendNotification(
        item: JournalItem,
        retentionRevision: Long?,
        image: ByteArray? = null,
    ): Long = database.withTransaction {
        require(item.id == 0L && item.kind == JournalItem.KIND_NOTIFICATION)
        requireNotNull(item.packageName)
        require(image == null || image.size <= MAX_MEDIA_BYTES)
        val settings = journalSettings()
        val state = journalState()
        val keepContent = settings.retainContent && settings.retentionRevision == retentionRevision
        val keepText = keepContent && (item.contentAvailable || item.archivedText != null)
        val archived = item.archivedText.takeIf { keepText }
        val savedImage = image.takeIf { keepContent }
        val title = item.title.takeIf { keepText }
        val body = item.body.takeIf { keepText }
        val sender = item.sender.takeIf { keepText }
        val id = dao.insertJournalItem(
            item.copy(
                title = title,
                body = body,
                sender = sender,
                senderKey = item.senderKey.takeIf { keepText },
                contentRetained = keepText || savedImage != null,
                changedRevision = state.revision + 1,
                archivedText = archived,
                searchText = listOfNotNull(title, body, sender, archived).takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { it.lowercase() },
                senderSearch = sender?.lowercase(),
            ),
        )
        if (item.countAsNew && item.lifecycleId != null) {
            dao.insertStatisticsStart(StatisticsStart(item.observedAtMillis))
            dao.registerReceipt(NotificationCount(item.lifecycleId, item.observedAtMillis))
            dao.pruneCounts(item.observedAtMillis - COUNT_RETENTION_MILLIS)
        }
        if (savedImage != null) {
            dao.insertMedia(JournalMedia(id, savedImage))
            dao.pruneMedia(MAX_MEDIA_ITEMS)
        }
        updateJournalSize(state, state.actualCount + 1, settings.capacity)
        id
    }

    suspend fun appendGap(atMillis: Long, reason: String, endedAtMillis: Long? = null): Long =
        database.withTransaction {
            require(endedAtMillis == null || endedAtMillis >= atMillis)
            val state = journalState()
            val id = dao.insertJournalItem(
                JournalItem(
                    observedAtMillis = atMillis,
                    changedRevision = state.revision + 1,
                    kind = JournalItem.KIND_GAP,
                    decisionReason = reason,
                    gapEndedAtMillis = endedAtMillis,
                ),
            )
            updateJournalSize(state, state.actualCount + 1, journalCapacity())
            id
        }

    private suspend fun journalState(): JournalState = dao.journalState() ?: JournalState()

    private suspend fun updateJournalSize(state: JournalState, count: Int, capacity: Int) {
        val excess = count - capacity
        if (excess > 0) dao.evictOldestJournalRows(excess)
        dao.saveJournalState(state.copy(actualCount = minOf(count, capacity), revision = state.revision + 1))
    }

    private suspend fun changeJournalRow(update: suspend (Long) -> Int): Boolean {
        val state = journalState()
        val revision = state.revision + 1
        if (update(revision) == 0) return false
        dao.saveJournalState(state.copy(revision = revision))
        return true
    }

    suspend fun closeGap(id: Long, atMillis: Long): Boolean = database.withTransaction {
        changeJournalRow { dao.closeGap(id, atMillis, it) }
    }

    suspend fun recordDismissalRequested(id: Long, atMillis: Long, lifecycleId: String? = null): Boolean =
        database.withTransaction {
            lifecycleId?.let { dao.markCountDismissal(it) }
            changeJournalRow { dao.markDismissalRequested(id, atMillis, it) }
        }

    suspend fun recordRemoval(id: Long?, atMillis: Long, reason: Int, hiddenLifecycle: String? = null): Boolean =
        database.withTransaction {
            hiddenLifecycle?.let { dao.markCountHidden(it) }
            id != null && changeJournalRow { dao.markRemoval(id, atMillis, reason, it) }
        }

    fun observeStatisticsChanges(): Flow<Set<String>> =
        database.invalidationTracker.createFlow("notification_counts", "statistics_start")

    suspend fun notificationStatistics(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): NotificationStatistics = database.withTransaction {
        dao.insertStatisticsStart(StatisticsStart(now))
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val tomorrow = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        NotificationStatistics(
            today = dao.notificationTotals(day.atStartOfDay(zone).toInstant().toEpochMilli(), tomorrow),
            week = dao.notificationTotals(week.atStartOfDay(zone).toInstant().toEpochMilli(), tomorrow),
            startedAtMillis = checkNotNull(dao.statisticsStart()),
        )
    }

    fun observeJournalChanges(): Flow<Set<String>> =
        database.invalidationTracker.createFlow("journal_state")

    suspend fun historyPage(query: HistoryQuery = HistoryQuery()): List<JournalItem> =
        filteredHistory(query)

    suspend fun historyRevision(): Long = journalState().revision

    suspend fun historyDelta(previous: Long, query: HistoryQuery): JournalDelta = database.withTransaction {
        val state = journalState()
        val oldest = dao.oldestJournalId().takeIf { it != 0L }
        if (previous < state.resetRevision || previous > state.revision) {
            return@withTransaction JournalDelta(state.revision, oldest, emptySet(), emptyList(), true)
        }
        val ids = dao.journalChangedIds(previous, MAX_PAGE_SIZE + 1)
        if (ids.size > MAX_PAGE_SIZE) {
            return@withTransaction JournalDelta(state.revision, oldest, emptySet(), emptyList(), true)
        }
        val matching = if (ids.isEmpty()) emptyList() else dao.journalRowsById(ids).filter(query::includes)
        JournalDelta(state.revision, oldest, ids.toSet(), matching, false)
    }

    private suspend fun filteredHistory(query: HistoryQuery): List<JournalItem> = dao.historyPage(
        since = query.sinceMillis ?: Long.MIN_VALUE,
        until = minOf(query.untilMillis ?: Long.MAX_VALUE, query.before?.observedAtMillis ?: Long.MAX_VALUE),
        beforeTime = query.before?.observedAtMillis,
        beforeId = query.before?.id,
        packages = (query.packageNames + listOfNotNull(query.packageName)).toList(),
        allPackages = query.packageNames.isEmpty() && query.packageName == null,
        includeGaps = query.includeGaps,
        channelId = query.channelId,
        sender = query.sender?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(),
        verdict = query.verdict,
        search = query.search?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(),
        limit = query.limit.coerceIn(1, MAX_PAGE_SIZE),
    )

    internal suspend fun countHistoryExport(query: HistoryQuery): Int =
        dao.countHistoryExport(requireNotNull(query.sinceMillis), requireNotNull(query.untilMillis), query.packageName)

    internal suspend fun readHistoryExport(
        query: HistoryQuery,
        write: suspend (JournalItem, suspend () -> ByteArray?) -> Unit,
    ) = database.withTransaction {
        var cursor: HistoryCursor? = null
        while (true) {
            val page = historyPage(query.copy(before = cursor))
            if (page.isEmpty()) break
            for (entry in page) write(entry) { journalImage(entry.id) }
            val last = page.last()
            cursor = HistoryCursor(last.observedAtMillis, last.id)
        }
    }

    suspend fun historyPackages(): Set<String> = dao.historyPackages().toSet()

    private suspend fun journalSettings(): JournalSettings = dao.journalSettings() ?: JournalSettings()

    internal fun observeJournalSettings(): Flow<JournalSettings> =
        dao.observeJournalSettings().map { it ?: JournalSettings() }

    suspend fun journalCapacity(): Int = journalSettings().capacity

    internal suspend fun contentRevision(): Long? =
        journalSettings().let { it.retentionRevision.takeIf { _ -> it.retainContent } }

    suspend fun journalImage(journalId: Long): ByteArray? = dao.media(journalId)

    suspend fun setContentRetention(enabled: Boolean) = database.withTransaction {
        val current = journalSettings()
        if (current.retainContent == enabled) return@withTransaction
        dao.saveJournalSettings(current.copy(
            retainContent = enabled,
            retentionRevision = current.retentionRevision + 1,
        ))
        if (!enabled) {
            dao.eraseJournalContent()
            dao.clearMedia()
            val state = journalState()
            dao.saveJournalState(state.copy(revision = state.revision + 1, resetRevision = state.revision + 1))
        }
    }

    suspend fun setJournalCapacity(capacity: Int) = database.withTransaction {
        require(capacity in 1..MAX_JOURNAL_CAPACITY)
        dao.saveJournalSettings(journalSettings().copy(capacity = capacity))
        val state = journalState()
        updateJournalSize(state, state.actualCount, capacity)
    }

    suspend fun clearHistory() = database.withTransaction {
        dao.clearJournal()
        dao.clearFrequency()
        val state = journalState()
        dao.saveJournalState(state.copy(actualCount = 0, revision = state.revision + 1, resetRevision = state.revision + 1))
    }

    suspend fun rules(): RuleSet = dao.rules() ?: RuleSet()

    fun observeRules(): Flow<RuleSet> = dao.observeRules().map { it ?: RuleSet() }

    suspend fun replaceRules(
        expectedVersion: Long,
        rulesJson: String,
        reason: RuleChange = RuleChange.MANUAL,
    ): RuleSet? = ruleHistory.replace(expectedVersion, rulesJson, reason)

    internal suspend fun exportRuleSettings(): Pair<RuleSet, Set<String>> = database.withTransaction {
        rules() to dao.enabledPackages().toSet()
    }

    internal suspend fun importRuleSettings(
        expectedVersion: Long,
        rulesJson: String,
        reason: RuleChange,
        enablePackages: Set<String>,
    ): RuleSet? = database.withTransaction {
        val result = replaceRules(expectedVersion, rulesJson, reason) ?: return@withTransaction null
        for (packageName in enablePackages) setSource(packageName, true)
        result
    }

    suspend fun undoRules(expectedVersion: Long): RuleSet? = ruleHistory.undo(expectedVersion)

    suspend fun frequency(): String? = dao.frequency()?.payload

    suspend fun saveFrequency(payload: String) = dao.saveFrequency(FrequencySnapshot(payload))

    suspend fun clearFrequency() = dao.clearFrequency()

    companion object {
        const val DEFAULT_JOURNAL_CAPACITY = 10_000
        const val MAX_JOURNAL_CAPACITY = 50_000
        internal const val MAX_MEDIA_BYTES = 32 * 1024
        private const val MAX_MEDIA_ITEMS = 2048
        private const val COUNT_RETENTION_MILLIS = 35L * 24 * 60 * 60 * 1000
        private const val MAX_PAGE_SIZE = 200
    }
}
