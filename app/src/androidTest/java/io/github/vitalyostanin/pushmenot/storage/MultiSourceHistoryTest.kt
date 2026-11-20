package io.github.vitalyostanin.pushmenot.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiSourceHistoryTest {
    @Test
    fun retainsUnfilteredRowsAndKeepsTheLastEntriesAtTheConfiguredCapacity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val storage = db.storage
            assertEquals(10_000, storage.journalCapacity())
            val old = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
            val unselected = JournalItem(old, packageName = "example.chat", title = "Synthetic title")
            storage.appendNotification(unselected, storage.contentRevision())
            val recorded = storage.historyPage().single()
            assertEquals("example.chat", recorded.packageName)
            assertEquals("Synthetic title", recorded.title)
            assertNull(recorded.verdict)

            storage.setSource("example.chat", enabled = false)
            storage.appendNotification(unselected, storage.contentRevision())
            assertEquals("Synthetic title", storage.historyPage().first().title)
            storage.appendGap(old, "test")
            storage.setJournalCapacity(2)
            assertEquals(2, storage.journalCapacity())
            val cap = HistoryQuery(includeGaps = true)
            assertEquals(listOf(JournalItem.KIND_GAP, JournalItem.KIND_NOTIFICATION), storage.historyPage(cap).map { it.kind })

            storage.appendNotification(unselected, storage.contentRevision())
            assertEquals(listOf(JournalItem.KIND_NOTIFICATION, JournalItem.KIND_GAP), storage.historyPage(cap).map { it.kind })
            storage.setContentRetention(false)
            assertNull(storage.historyPage().single().title)
        } finally {
            db.close()
        }
    }

    @Test
    fun purgesContentAndRejectsQueuedContentFromBeforeAnOptOut() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val storage = db.storage
            val item = JournalItem(1, packageName = "example.chat", title = "Synthetic",
                sender = "Sender", senderKey = "Opaque", archivedText = "Second message", verdict = "HIDE")
            val revision = storage.contentRevision()
            val first = storage.appendNotification(item, revision, byteArrayOf(1))
            assertNotNull(storage.journalImage(first))
            assertEquals(first, storage.historyPage(HistoryQuery(search = "Second message")).single().id)

            storage.setContentRetention(false)
            storage.setJournalCapacity(5_000)
            assertEquals(5_000, storage.journalCapacity())
            assertNull(storage.contentRevision())
            assertNull(storage.journalImage(first))
            val metadata = storage.historyPage().single()
            assertEquals("HIDE", metadata.verdict)
            assertNull(metadata.title)
            assertNull(metadata.sender)
            assertNull(metadata.senderKey)
            assertNull(metadata.archivedText)
            assertTrue(storage.historyPage(HistoryQuery(search = "Second message")).isEmpty())

            storage.setContentRetention(true)
            val stale = storage.appendNotification(item, revision, byteArrayOf(2))
            val fresh = storage.appendNotification(item, storage.contentRevision(), byteArrayOf(3))
            assertNull(storage.journalImage(stale))
            assertNull(storage.historyPage().first { it.id == stale }.title)
            assertNotNull(storage.journalImage(fresh))
            storage.setJournalCapacity(1)
            storage.appendGap(2, "test")
            assertNull(storage.journalImage(fresh))
        } finally {
            db.close()
        }
    }

    @Test
    fun combinesSelectedSourcesAndRetainsOverlappingGapsAcrossPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val storage = db.storage
            for ((source, age) in listOf("shop" to 300L, "bank" to 200L, "other" to 100L)) {
                val selected = storage.setSource("example.$source", true)
                storage.appendNotification(
                    JournalItem(now - age, packageName = selected.packageName),
                    storage.contentRevision(),
                )
            }
            storage.appendGap(now - 1000L, "test", endedAtMillis = now - 350L)
            val query = HistoryQuery(
                packageNames = setOf("example.shop", "example.bank"),
                sinceMillis = now - 400L,
                includeGaps = true,
                limit = 1,
            )
            val first = storage.historyPage(query).single()
            assertEquals("example.bank", first.packageName)
            val second = storage.historyPage(
                query.copy(before = HistoryCursor(first.observedAtMillis, first.id), limit = 5),
            )
            assertEquals(listOf("example.shop", null), second.map(JournalItem::packageName))
            assertEquals(JournalItem.KIND_GAP, second.last().kind)
        } finally {
            db.close()
        }
    }
}
