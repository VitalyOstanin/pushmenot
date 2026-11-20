package io.github.vitalyostanin.pushmenot.storage

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.FrequencyKey
import com.pushmenot.core.FrequencyState
import com.pushmenot.core.RuleVersion
import io.github.vitalyostanin.pushmenot.controller.JournalWindow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalWindowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun incrementalPagesFollowStableOrderingAndUpdatePreviouslyLoadedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val storage = db.storage
            storage.setJournalCapacity(10)
            val older = storage.appendNotification(JournalItem(1, packageName = "example.shop"), null)
            val middle = storage.appendNotification(JournalItem(5, packageName = "example.shop"), null)
            val newest = storage.appendNotification(JournalItem(5, packageName = "example.shop"), null)
            val query = HistoryQuery(packageName = "example.shop")
            val first = JournalWindow.load(db, query, null, 1)
            assertEquals(listOf(newest, middle), first.entries.map(JournalItem::id))
            assertTrue(first.hasUnloadedRows)
            val second = JournalWindow.load(db, query, first, 3)
            assertEquals(listOf(newest, middle, older), second.entries.map(JournalItem::id))
            storage.recordRemoval(older, 6, 2)
            storage.appendNotification(JournalItem(7, packageName = "example.other"), null)
            val changed = JournalWindow.load(db, query, second, 3)
            assertEquals(6L, changed.entries.last().removedAtMillis)
            assertEquals(second.entries.map(JournalItem::id), changed.entries.map(JournalItem::id))
            storage.setJournalCapacity(2)
            val shrunk = JournalWindow.load(db, query, changed, 3)
            assertEquals(listOf(newest), shrunk.entries.map(JournalItem::id))
            storage.clearHistory()
            assertTrue(JournalWindow.load(db, query, shrunk, 3).entries.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun recordingOneNotificationDoesNotReadPreviouslyLoadedPages() = runBlocking {
        val pageReads = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java)
            .setQueryCallback({ query, _ ->
                if (query.contains("ORDER BY observedAtMillis DESC, id DESC")) pageReads.incrementAndGet()
            }, Executor(Runnable::run))
            .build()
        try {
            db.withTransaction {
                val dao = db.data()
                repeat(5_100) { index ->
                    dao.insertJournalItem(JournalItem(index.toLong(), packageName = "example.shop"))
                }
                dao.saveJournalState(JournalState(actualCount = 5_100))
            }
            val query = HistoryQuery(packageName = "example.shop")
            val initial = JournalWindow.load(db, query, null, 5_000)
            assertTrue(initial.hasUnloadedRows)
            assertTrue(pageReads.getAndSet(0) > 1)
            val newest = db.storage.appendNotification(JournalItem(5_100, packageName = "example.shop"), null)
            val updated = JournalWindow.load(db, query, initial, 5_000)
            assertEquals(newest, updated.entries.first().id)
            assertEquals(0, pageReads.getAndSet(0))

            val next = JournalWindow.load(db, query, updated, 5_100)
            assertEquals(5_101, next.entries.size)
            assertEquals(1, pageReads.get())
        } finally {
            db.close()
        }
    }

    @Test
    fun disablingOneSourcePreservesAnotherSourcesFrequencyWindow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val store = db.storage
            val a = FrequencyKey(RuleVersion("one", 1), "profile", "example.a", null)
            val b = a.copy(packageName = "example.b")
            store.setSource(a.packageName, true)
            store.setSource(b.packageName, true)
            store.saveFrequency(checkNotNull(FrequencyJson.encode(FrequencyState(mapOf(a to listOf(1L), b to listOf(2L)), 3))))
            store.setSource(a.packageName, false)
            assertEquals(FrequencyState(mapOf(b to listOf(2L)), 3), FrequencyJson.decode(store.frequency()))
        } finally {
            db.close()
        }
    }
}
