package io.github.vitalyostanin.pushmenot.controller

import androidx.room.withTransaction
import io.github.vitalyostanin.pushmenot.storage.HistoryCursor
import io.github.vitalyostanin.pushmenot.storage.HistoryQuery
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import io.github.vitalyostanin.pushmenot.storage.includes

internal data class JournalWindow(
    val query: HistoryQuery,
    val revision: Long,
    val entries: List<JournalItem>,
    val hasUnloadedRows: Boolean,
) {
    companion object {
        private val order = compareByDescending(JournalItem::observedAtMillis).thenByDescending(JournalItem::id)
        private const val PAGE_SIZE = 100

        suspend fun load(
            database: PushDatabase,
            query: HistoryQuery,
            previous: JournalWindow?,
            size: Int,
        ): JournalWindow = database.withTransaction {
            val storage = database.storage
            val previousSince = previous?.query?.sinceMillis ?: Long.MIN_VALUE
            val since = query.sinceMillis ?: Long.MIN_VALUE
            val reusable = previous != null && previous.query.copy(sinceMillis = query.sinceMillis) == query &&
                since >= previousSince
            val delta = if (reusable) storage.historyDelta(checkNotNull(previous).revision, query) else null
            val carry = delta != null && !delta.requiresReload
            val entries = if (carry) {
                val old = checkNotNull(previous)
                val changes = checkNotNull(delta)
                val oldest = changes.oldestId
                val tail = old.entries.lastOrNull()
                val kept = if (oldest == null) emptyList() else old.entries.filter {
                    it.id >= oldest && it.id !in changes.changedIds && query.includes(it)
                }
                val added = changes.matchingRows.filter {
                    !old.hasUnloadedRows || tail == null || order.compare(it, tail) <= 0
                }
                (kept + added).sortedWith(order).toMutableList()
            } else mutableListOf()
            val needLookAhead = !carry || checkNotNull(previous).hasUnloadedRows
            while (entries.size <= size && needLookAhead) {
                val tail = entries.lastOrNull()
                val cursor = tail?.let { HistoryCursor(it.observedAtMillis, it.id) }
                val limit = minOf(size + 1 - entries.size, PAGE_SIZE + 1)
                val rows = storage.historyPage(query.copy(before = cursor, limit = limit))
                entries.addAll(rows)
                if (rows.size < limit) break
            }
            JournalWindow(query, delta?.revision ?: storage.historyRevision(), entries.take(size + 1), entries.size > size)
        }
    }
}
