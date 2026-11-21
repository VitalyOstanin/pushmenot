package io.github.vitalyostanin.pushmenot.storage

import androidx.room.withTransaction
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.rules.RuleDocument
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

enum class RuleChange { BASELINE, MANUAL, APPEND, REPLACE, RESTORE, ARCHIVE }

class RuleHistory internal constructor(private val database: PushDatabase) {
    private val dao = database.data()

    fun observeVersions(): Flow<List<RuleVersionSummary>> = flow {
        database.withTransaction { ensureBaseline() }
        emitAll(dao.observeRuleHistory())
    }

    suspend fun snapshot(id: String): RuleSnapshot? = dao.snapshot(id)

    internal suspend fun replace(expectedVersion: Long, json: String, reason: RuleChange): RuleSet? =
        database.withTransaction {
            val current = dao.rules() ?: RuleSet()
            if (current.version != expectedVersion) return@withTransaction null
            commit(current, RuleJson.decodeStored(json), reason)
        }

    internal suspend fun undo(expectedVersion: Long): RuleSet? = database.withTransaction {
        val current = dao.rules() ?: return@withTransaction null
        val previous = current.previousRulesJson ?: return@withTransaction null
        if (current.version != expectedVersion) return@withTransaction null
        commit(current, RuleJson.decodeStored(previous), RuleChange.RESTORE)
    }

    internal suspend fun isMissing(incoming: RuleSnapshot): Boolean {
        val existing = dao.snapshot(incoming.revisionId)
        require(existing == null || incoming == existing.copy(sequence = 0)) { "Conflicting snapshot ID" }
        return existing == null
    }

    internal suspend fun restoreArchive(
        expectedVersion: Long,
        readActive: suspend (suspend (RuleSnapshot) -> Unit) -> RuleSnapshot,
    ): RuleSet? = database.withTransaction {
        val current = dao.rules() ?: RuleSet()
        if (current.version != expectedVersion) return@withTransaction null
        val anchored = ensureBaseline()
        val active = readActive { snapshot ->
            if (isMissing(snapshot)) dao.insertSnapshot(snapshot)
        }
        commit(anchored, RuleJson.decodeStored(active.rulesJson), RuleChange.ARCHIVE)
    }

    internal suspend fun writeArchive(
        start: suspend (String) -> Unit,
        writeVersion: (RuleSnapshot) -> Unit,
    ) {
        val (activeId, through) = database.withTransaction {
            checkNotNull(ensureBaseline().activeRevisionId) to dao.latestSnapshotSequence()
        }
        // Snapshots are immutable. New versions can be written while the export is streamed.
        start(activeId)
        var after = 0L
        while (after < through) {
            val page = dao.snapshotPage(after, through, 16)
            check(page.isNotEmpty())
            for (snapshot in page) writeVersion(snapshot)
            after = page.last().sequence
        }
    }

    private suspend fun ensureBaseline(): RuleSet {
        val head = dao.rules() ?: RuleSet()
        if (head.activeRevisionId != null) return head
        val snapshot = createSnapshot(RuleJson.decodeStored(head.rulesJson), RuleChange.BASELINE)
        dao.insertSnapshot(snapshot)
        return head.copy(activeRevisionId = snapshot.revisionId).also { dao.saveRules(it) }
    }

    private suspend fun commit(current: RuleSet, next: RuleDocument, reason: RuleChange): RuleSet {
        val before = RuleJson.decodeStored(current.rulesJson)
        val old = before.rules
        val previousById = old.associateBy(FilterRule::id)
        val stable = next.rules.map { requested ->
            val before = previousById[requested.id]
            if (before != null && requested.copy(revision = before.revision) == before) {
                before
            } else {
                val floor = dao.ruleRevisionFloor(requested.id) ?: 0L
                check(floor < Long.MAX_VALUE)
                val revision = maxOf(requested.revision, floor + 1)
                dao.saveRuleRevisionFloor(RuleRevisionFloor(requested.id, revision))
                requested.copy(revision = revision)
            }
        }
        if (old == stable && before.modes == next.modes) return current
        check(current.version < Long.MAX_VALUE)
        ensureBaseline()
        val snapshot = createSnapshot(next.copy(rules = stable), reason)
        dao.insertSnapshot(snapshot)
        val updated = RuleSet(
            version = current.version + 1,
            rulesJson = snapshot.rulesJson,
            previousRulesJson = current.rulesJson,
            activeRevisionId = snapshot.revisionId,
        )
        if (reason == RuleChange.RESTORE || reason == RuleChange.ARCHIVE) resetChangedFrequency(old, stable)
        dao.saveRules(updated)
        return updated
    }

    private suspend fun resetChangedFrequency(old: List<FilterRule>, next: List<FilterRule>) {
        val stored = dao.frequency() ?: return
        val before = old.associateBy(FilterRule::id)
        val after = next.associateBy(FilterRule::id)
        val shared = before.keys intersect after.keys
        val reordered = old.map(FilterRule::id).filter(shared::contains) != next.map(FilterRule::id).filter(shared::contains)
        val state = FrequencyJson.decode(stored.payload)
        val windows = state.windows.filterKeys { key ->
            val id = key.rule.id
            id in shared && before[id] == after[id] && !reordered
        }
        if (windows.size == state.windows.size) return
        val encoded = FrequencyJson.encode(state.copy(windows = windows))
        if (encoded == null) dao.clearFrequency() else dao.saveFrequency(FrequencySnapshot(encoded))
    }

    private fun createSnapshot(document: RuleDocument, reason: RuleChange) = RuleSnapshot(
        revisionId = UUID.randomUUID().toString(),
        createdAtMillis = System.currentTimeMillis(),
        reason = reason.name,
        count = document.rules.size,
        rulesJson = RuleJson.encode(document.rules, modes = document.modes),
    )
}
