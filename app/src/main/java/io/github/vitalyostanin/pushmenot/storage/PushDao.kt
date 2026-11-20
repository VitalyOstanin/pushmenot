package io.github.vitalyostanin.pushmenot.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface PushDao {
    @Query("SELECT * FROM sources WHERE packageName = :packageName")
    suspend fun source(packageName: String): SourceSettings?

    @Query("SELECT * FROM sources ORDER BY packageName")
    fun observeSources(): Flow<List<SourceSettings>>

    @Upsert
    suspend fun saveSource(source: SourceSettings)

    @Insert
    suspend fun insertJournalItem(item: JournalItem): Long

    @Query(
        """
        UPDATE journal
        SET title = NULL, body = NULL, sender = NULL, senderKey = NULL, searchText = NULL, senderSearch = NULL,
            archivedText = NULL, contentRetained = 0
        WHERE contentRetained = 1
        """,
    )
    suspend fun eraseJournalContent()

    @Insert
    suspend fun insertMedia(media: JournalMedia)

    @Query("DELETE FROM journal_media WHERE journalId <= (SELECT journalId FROM journal_media ORDER BY journalId DESC LIMIT 1 OFFSET :limit)")
    suspend fun pruneMedia(limit: Int)

    @Query("SELECT image FROM journal_media WHERE journalId = :journalId")
    suspend fun media(journalId: Long): ByteArray?

    @Query("DELETE FROM journal_media")
    suspend fun clearMedia()

    @Query(
        """
        UPDATE journal SET dismissalRequestedAtMillis = :atMillis, changedRevision = :revision
        WHERE id = :id AND kind = 'notification' AND dismissalRequestedAtMillis IS NULL
        """,
    )
    suspend fun markDismissalRequested(id: Long, atMillis: Long, revision: Long): Int

    @Query(
        """
        UPDATE journal SET removedAtMillis = :atMillis, removalReason = :reason, changedRevision = :revision
        WHERE id = :id AND kind = 'notification' AND removedAtMillis IS NULL
        """,
    )
    suspend fun markRemoval(id: Long, atMillis: Long, reason: Int, revision: Long): Int

    @Query(
        """
        UPDATE journal SET gapEndedAtMillis = :atMillis, changedRevision = :revision
        WHERE id = :id AND kind = 'gap' AND gapEndedAtMillis IS NULL
          AND :atMillis >= observedAtMillis
        """,
    )
    suspend fun closeGap(id: Long, atMillis: Long, revision: Long): Int

    @Query(
        """
        SELECT * FROM journal
        WHERE (observedAtMillis >= :since OR (:includeGaps AND kind = 'gap'
               AND (gapEndedAtMillis IS NULL OR gapEndedAtMillis >= :since)))
          AND observedAtMillis <= :until
          AND (:beforeTime IS NULL OR observedAtMillis < :beforeTime
               OR (observedAtMillis = :beforeTime AND id < :beforeId))
          AND ((:includeGaps AND kind = 'gap') OR (
               kind = 'notification'
               AND (:allPackages OR packageName IN (:packages))
               AND (:channelId IS NULL OR channelId = :channelId)
               AND (:sender IS NULL OR senderSearch = :sender)
               AND (:verdict IS NULL OR verdict = :verdict)
               AND (:search IS NULL OR instr(searchText, :search) > 0)))
        ORDER BY observedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun historyPage(
        since: Long,
        until: Long,
        beforeTime: Long?,
        beforeId: Long?,
        packages: List<String>,
        allPackages: Boolean,
        includeGaps: Boolean,
        channelId: String?,
        sender: String?,
        verdict: String?,
        search: String?,
        limit: Int,
    ): List<JournalItem>

    @Query("SELECT DISTINCT packageName FROM journal WHERE packageName IS NOT NULL")
    suspend fun historyPackages(): List<String>

    @Query("SELECT * FROM journal_settings WHERE singleton = 0")
    suspend fun journalSettings(): JournalSettings?

    @Query("SELECT * FROM journal_settings WHERE singleton = 0")
    fun observeJournalSettings(): Flow<JournalSettings?>

    @Upsert
    suspend fun saveJournalSettings(settings: JournalSettings)

    @Query("SELECT * FROM journal_state WHERE singleton = 0")
    suspend fun journalState(): JournalState?

    @Upsert
    suspend fun saveJournalState(state: JournalState)

    @Query("SELECT COALESCE(MIN(id), 0) FROM journal")
    suspend fun oldestJournalId(): Long

    @Query("SELECT id FROM journal WHERE changedRevision > :revision ORDER BY changedRevision LIMIT :limit")
    suspend fun journalChangedIds(revision: Long, limit: Int): List<Long>

    @Query("SELECT * FROM journal WHERE id IN (:ids)")
    suspend fun journalRowsById(ids: List<Long>): List<JournalItem>

    @Query("DELETE FROM journal WHERE id IN (SELECT id FROM journal ORDER BY id LIMIT :excess)")
    suspend fun evictOldestJournalRows(excess: Int)

    @Query("DELETE FROM journal")
    suspend fun clearJournal()

    @Query("SELECT * FROM rule_set WHERE singleton = 0")
    suspend fun rules(): RuleSet?

    @Query("SELECT * FROM rule_set WHERE singleton = 0")
    fun observeRules(): Flow<RuleSet?>

    @Upsert
    suspend fun saveRules(ruleSet: RuleSet)

    @Query("SELECT * FROM frequency_snapshot WHERE singleton = 0")
    suspend fun frequency(): FrequencySnapshot?

    @Upsert
    suspend fun saveFrequency(snapshot: FrequencySnapshot)

    @Query("DELETE FROM frequency_snapshot")
    suspend fun clearFrequency()

    @Insert
    suspend fun insertSnapshot(snapshot: RuleSnapshot)

    @Query("SELECT * FROM rule_history WHERE revisionId = :id")
    suspend fun snapshot(id: String): RuleSnapshot?

    @Query("SELECT revisionId, createdAtMillis, reason, count FROM rule_history ORDER BY sequence DESC")
    fun observeRuleHistory(): Flow<List<RuleVersionSummary>>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM rule_history")
    suspend fun latestSnapshotSequence(): Long

    @Query("SELECT * FROM rule_history WHERE sequence > :after AND sequence <= :through ORDER BY sequence LIMIT :limit")
    suspend fun snapshotPage(after: Long, through: Long, limit: Int): List<RuleSnapshot>

    @Query("SELECT revision FROM rule_revision_floor WHERE ruleId = :id")
    suspend fun ruleRevisionFloor(id: String): Long?

    @Upsert
    suspend fun saveRuleRevisionFloor(floor: RuleRevisionFloor)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registerReceipt(receipt: NotificationCount)

    @Query("UPDATE notification_counts SET dismissalRequested = 1 WHERE lifecycleId = :id")
    suspend fun markCountDismissal(id: String)

    @Query("UPDATE notification_counts SET hidden = 1 WHERE lifecycleId = :id AND dismissalRequested = 1")
    suspend fun markCountHidden(id: String)

    @Query("DELETE FROM notification_counts WHERE receivedAtMillis < :before")
    suspend fun pruneCounts(before: Long)

    @Query("SELECT COUNT(*) AS received, COALESCE(SUM(hidden), 0) AS hidden FROM notification_counts WHERE receivedAtMillis >= :since AND receivedAtMillis < :until")
    suspend fun notificationTotals(since: Long, until: Long): NotificationTotals

    @Query("SELECT startedAtMillis FROM statistics_start WHERE singleton = 0")
    suspend fun statisticsStart(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatisticsStart(start: StatisticsStart)
}
