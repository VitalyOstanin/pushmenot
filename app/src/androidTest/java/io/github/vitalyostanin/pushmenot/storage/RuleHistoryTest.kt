package io.github.vitalyostanin.pushmenot.storage

import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.TextCondition
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.transfer.ImportMode
import io.github.vitalyostanin.pushmenot.transfer.RuleArchive
import io.github.vitalyostanin.pushmenot.transfer.RuleTransfer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun snapshotsAppendRestoreAndRepeatedArchiveRestore() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        val receiver = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        val document = File.createTempFile("rules", ".json", context.cacheDir)
        val backup = File.createTempFile("versions", ".backup", context.cacheDir)
        try {
            val store = database.storage
            val transfer = RuleTransfer(context.contentResolver, store)
            val one = FilterRule("one", 1L, "example.shop", Action.HIDE, title = TextCondition("Promo"))
            val two = one.copy(id = "two", title = TextCondition("Survey"))
            val first = requireNotNull(store.replaceRules(0, RuleJson.encode(listOf(one))))
            val initial = store.ruleHistory.observeVersions().first()
            assertEquals(listOf(1, 0), initial.map(RuleVersionSummary::count))

            document.writeText(RuleJson.encode(listOf(one.copy(enabled = false), one.copy(id = "duplicate"), two)))
            val preview = transfer.prepareImport(Uri.fromFile(document))
            assertEquals(listOf("two"), preview.appendedIds)
            assertEquals(2, preview.skippedCount)
            val second = requireNotNull(transfer.applyImport(preview, ImportMode.APPEND))
            assertEquals(listOf(one, two), RuleJson.decode(second.rulesJson))
            assertEquals(3, store.ruleHistory.observeVersions().first().size)

            val restoreFirst = requireNotNull(transfer.prepareRestore(requireNotNull(first.activeRevisionId)))
            assertEquals(listOf("two"), restoreFirst.removedIds)
            assertEquals(listOf(one), RuleJson.decode(requireNotNull(transfer.applyImport(restoreFirst)).rulesJson))
            val restoreSecond = requireNotNull(transfer.prepareRestore(requireNotNull(second.activeRevisionId)))
            val restoredRules = listOf(one, two.copy(revision = 2))
            assertEquals(restoredRules, RuleJson.decode(requireNotNull(transfer.applyImport(restoreSecond)).rulesJson))
            assertEquals(5, store.ruleHistory.observeVersions().first().size)

            val backupUri = Uri.fromFile(backup)
            RuleArchive(context.contentResolver, store).save(backupUri)
            val restoredStore = receiver.storage
            val restoring = RuleArchive(context.contentResolver, restoredStore)
            assertNotNull(restoredStore.replaceRules(0, RuleJson.encode(listOf(one.copy(id = "local")))))
            val incoming = restoring.prepare(backupUri)
            assertEquals(5, incoming.missingVersions)
            assertEquals(2, incoming.activeCount)
            val result = requireNotNull(restoring.restore(incoming))
            assertEquals(restoredRules, RuleJson.decode(result.rulesJson))
            val history = restoredStore.ruleHistory.observeVersions().first()
            assertEquals(8, history.size)
            assertEquals(0, restoring.prepare(backupUri).missingVersions)
            assertEquals(result.version, restoring.restore(restoring.prepare(backupUri))?.version)
            assertEquals(history, restoredStore.ruleHistory.observeVersions().first())
            val stable = restoring.prepare(backupUri)
            backup.appendText("\ninvalid version\n")
            assertTrue(runCatching { restoring.restore(stable) }.isFailure)
            assertEquals(history, restoredStore.ruleHistory.observeVersions().first())
        } finally {
            database.close()
            receiver.close()
            document.delete()
            backup.delete()
        }
    }

    @Test
    fun archiveKeepsItsOpeningRevisionWhileNewChangesAreCommitted() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            withTimeout(5_000) {
                val store = db.storage
                val first = FilterRule("one", 1, "example.shop", Action.HIDE, title = TextCondition("Before export"))
                val original = requireNotNull(store.replaceRules(0, RuleJson.encode(listOf(first))))
                val opening = CompletableDeferred<String>()
                val changed = CompletableDeferred<Unit>()
                val exporting = async {
                    val snapshots = mutableListOf<RuleSnapshot>()
                    store.ruleHistory.writeArchive(
                        start = { activeId ->
                            opening.complete(activeId)
                            changed.await()
                        },
                        writeVersion = { snapshots.add(it) },
                    )
                    snapshots
                }

                assertEquals(original.activeRevisionId, opening.await())
                val second = first.copy(revision = 2, title = TextCondition("During export"))
                val updated = requireNotNull(store.replaceRules(original.version, RuleJson.encode(listOf(second))))
                changed.complete(Unit)

                val snapshots = exporting.await()
                assertEquals(listOf(0, 1), snapshots.map(RuleSnapshot::count))
                assertEquals(original.activeRevisionId, snapshots.last().revisionId)
                assertEquals(updated.activeRevisionId, store.rules().activeRevisionId)
                assertEquals(listOf(first), RuleJson.decode(snapshots.last().rulesJson))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun restoringAnOlderRuleCannotReuseTheRevisionOfAnotherDefinition() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val store = db.storage
            val first = FilterRule("example", 1, "example.shop", Action.HIDE, title = TextCondition("First"))
            val second = first.copy(revision = 2, title = TextCondition("Second"))
            assertNotNull(store.replaceRules(0, RuleJson.encode(listOf(first))))
            assertNotNull(store.replaceRules(1, RuleJson.encode(listOf(second))))
            val restored = requireNotNull(store.replaceRules(2, RuleJson.encode(listOf(first)), RuleChange.RESTORE))
            assertEquals(first.copy(revision = 3), RuleJson.decode(restored.rulesJson).single())
            val third = first.copy(revision = 4, title = TextCondition("Third"))
            assertNotNull(store.replaceRules(restored.version, RuleJson.encode(listOf(third))))
            assertEquals(third, RuleJson.decode(store.rules().rulesJson).single())
        } finally {
            db.close()
        }
    }

    @Test
    fun migratesThePreviousAndCurrentRuleSetsWithDistinctSnapshotIds() {
        val previous = FilterRule("before", 1, "example.shop", Action.HIDE, title = TextCondition("Before"))
        val active = previous.copy(id = "active", title = TextCondition("Active"))
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE journal (ruleId TEXT, ruleRevision INTEGER)")
                        db.execSQL("INSERT INTO journal VALUES ('active', 9)")
                        db.execSQL("CREATE TABLE rule_set (version INTEGER NOT NULL, rulesJson TEXT NOT NULL, previousRulesJson TEXT, singleton INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            "INSERT INTO rule_set VALUES (7, ?, ?, 0)",
                            arrayOf(RuleJson.encode(listOf(active)), RuleJson.encode(listOf(previous))),
                        )
                        PushDatabase.addHistoryAndStatistics.migrate(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            val revisions = db.query("SELECT revisionId, rulesJson FROM rule_history ORDER BY sequence").use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0) to RuleJson.decode(rows.getString(1))) }
            }
            assertEquals(listOf(listOf(previous), listOf(active)), revisions.map { it.second })
            assertEquals(2, revisions.map { it.first }.distinct().size)
            db.query("SELECT revision FROM rule_revision_floor WHERE ruleId='active'").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(9L, rows.getLong(0))
            }
            db.query("SELECT version, activeRevisionId FROM rule_set").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(7L, rows.getLong(0))
                assertEquals(revisions.last().first, rows.getString(1))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun countsUniqueReceiptsAcrossLocalDayAndWeekEvenAfterPruningJournal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val storage = db.storage
            val zone = ZoneId.of("Europe/Moscow")
            fun time(value: String) = Instant.parse(value).toEpochMilli()
            suspend fun post(lifecycle: String, at: String, fresh: Boolean = true): Long =
                storage.appendNotification(
                    JournalItem(time(at), packageName = "example.shop", lifecycleId = lifecycle, countAsNew = fresh), null,
                )

            storage.setJournalCapacity(1)
            post("sunday", "2025-11-09T20:59:59Z")
            val earlier = post("monday", "2025-11-09T21:00:00Z")
            post("monday", "2025-11-10T00:00:00Z", fresh = false)
            val latest = post("today", "2025-11-11T08:00:00Z")
            storage.recordDismissalRequested(earlier, time("2025-11-11T09:00:00Z"), "monday")
            storage.recordRemoval(earlier, time("2025-11-11T09:00:01Z"), 10, "monday")
            storage.recordRemoval(latest, time("2025-11-11T09:01:00Z"), 10, "today")
            storage.clearHistory()
            val totals = storage.notificationStatistics(time("2025-11-11T11:00:00Z"), zone)
            assertEquals(NotificationTotals(1, 0), totals.today)
            assertEquals(NotificationTotals(2, 1), totals.week)
        } finally {
            db.close()
        }
    }
}
