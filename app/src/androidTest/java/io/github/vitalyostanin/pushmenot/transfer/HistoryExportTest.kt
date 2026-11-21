package io.github.vitalyostanin.pushmenot.transfer

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.JsonReader
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.pushmenot.MainActivity
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import io.github.vitalyostanin.pushmenot.storage.writeTransaction
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryExportTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun requiresConfirmationBeforeSavingNotificationContent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        storage.appendNotification(
            JournalItem(System.currentTimeMillis(), packageName = "example.export", title = "Synthetic notification"),
            retentionRevision = storage.contentRevision(),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("settings_tab").performClick()
                compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("history_export"))
                compose.onNodeWithTag("history_export").performClick()

                val list = compose.onNodeWithTag("history_export_list")
                list.performScrollToNode(hasTestTag("export_titles"))
                compose.onNodeWithTag("export_titles").performClick()
                list.performScrollToNode(hasTestTag("history_export_save"))
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("history_export_save") and isEnabled())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("history_export_save").performClick()
                val warning = compose.onNodeWithText(context.getString(R.string.history_export_confirm_title))
                warning.assertExists()
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                warning.assertDoesNotExist()
                compose.onNodeWithTag("history_export_save").assertExists()
            }
        } finally {
            storage.clearHistory()
        }
    }

    @Test
    fun confirmedExportOptionsSurviveActivityStateRestoration() {
        val options = HistoryExportOptions(
            "example.export", 7, titles = true, text = false, images = true, untilMillis = 1_234_567L,
        )
        assertEquals(options, HistoryExportOptions.fromSavedState(options.toSavedState()))
        assertNull(HistoryExportOptions.fromSavedState(Bundle()))
    }

    @Test
    fun defaultExportIncludesOnlyMetadataAndFiltersByApp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("history-test", ".json", context.cacheDir)
        val database = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val exporter = HistoryExport(context.contentResolver, context.cacheDir, database.storage)
            val now = System.currentTimeMillis()
            for (app in listOf("example.export", "example.other")) {
                database.storage.appendNotification(
                    JournalItem(
                        observedAtMillis = now,
                        packageName = app,
                        title = "Private title",
                        body = "Private text",
                        sender = "private@example.test",
                        channelId = "private-channel",
                    ),
                    retentionRevision = database.storage.contentRevision(),
                )
            }

            val options = HistoryExportOptions(
                "example.export", 1, titles = false, text = false, images = false, untilMillis = now,
            )
            assertEquals(1, exporter.count(options))
            database.storage.appendNotification(
                JournalItem(now + 1, packageName = "example.export", title = "Newer private entry"),
                retentionRevision = database.storage.contentRevision(),
            )
            exporter.write(Uri.fromFile(file), options)
            val json = file.readText()
            val entries = JSONObject(json).getJSONArray("entries")
            assertEquals(1, entries.length())
            val entry = entries.getJSONObject(0)
            assertEquals("example.export", entry.getString("app"))
            assertEquals(now / 3_600_000 * 3_600_000, entry.getLong("receivedHourUtcMillis"))
            assertFalse(json.contains("Private"))
            assertFalse(json.contains("private"))

            exporter.write(Uri.fromFile(file), options.copy(titles = true, text = true))
            val withContent = JSONObject(file.readText()).getJSONArray("entries").getJSONObject(0)
            assertEquals("Private title", withContent.getString("title"))
            assertEquals("Private text", withContent.getString("text"))
            assertFalse(withContent.has("sender"))
            assertFalse(withContent.has("channelId"))
        } finally {
            database.close()
            file.delete()
        }
    }

    @Test
    fun exportsFullJournalWithImagesWhileReceivingANotification(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("history-load-test", ".json", context.cacheDir)
        val exportStarted = CompletableDeferred<Unit>()
        val appendFinished = CountDownLatch(1)
        val name = "history-load-${UUID.randomUUID()}.db"
        val driver = BundledSQLiteDriver()
        val observingDriver = object : SQLiteDriver by driver {
            override fun open(fileName: String): SQLiteConnection {
                val connection = driver.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        if (!sql.contains("ORDER BY observedAtMillis DESC, id DESC")) return statement
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean = statement.step().also { hasRow ->
                                if (hasRow && exportStarted.complete(Unit)) {
                                    check(appendFinished.await(10, TimeUnit.SECONDS)) { "Export blocked the writer" }
                                }
                            }
                        }
                    }
                }
            }
        }
        val database = PushDatabase.builder(context, name).setDriver(observingDriver).build()
        try {
            val storage = database.storage
            val exporter = HistoryExport(context.contentResolver, context.cacheDir, storage)
            val now = System.currentTimeMillis()
            val entry = JournalItem(now, packageName = "example.export", title = "Synthetic notice")
            val revision = storage.contentRevision()
            val image = ByteArray(PushStorage.MAX_MEDIA_BYTES) { 42 }
            val expectedImage = Base64.encodeToString(image, Base64.NO_WRAP)
            val entries = 10_000
            val images = 2_048
            database.writeTransaction {
                repeat(entries) { index ->
                    storage.appendNotification(entry, revision, image.takeIf { index >= entries - images })
                }
            }

            val options = HistoryExportOptions(null, 1, titles = true, text = true, images = true, untilMillis = now)
            val start = SystemClock.elapsedRealtime()
            val export = async(Dispatchers.IO) { exporter.write(Uri.fromFile(file), options) }
            withTimeout(30_000) { exportStarted.await() }
            val writeStart = SystemClock.elapsedRealtime()
            val writeMillis = try {
                storage.appendNotification(entry.copy(observedAtMillis = now + 1, title = "Concurrent"), revision)
                SystemClock.elapsedRealtime() - writeStart
            } finally {
                appendFinished.countDown()
            }
            export.await()
            val durationMillis = SystemClock.elapsedRealtime() - start
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "\nHISTORY_EXPORT_SAMPLE entries=$entries images=$images " +
                    "exportMs=$durationMillis appendMs=$writeMillis bytes=${file.length()}\n")
            })

            var entryCount = 0
            var imageCount = 0
            JsonReader(file.reader()).use { json ->
                json.beginObject()
                assertEquals("version", json.nextName())
                assertEquals(1, json.nextInt())
                assertEquals("entries", json.nextName())
                json.beginArray()
                while (json.hasNext()) {
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "title" -> assertEquals("Synthetic notice", json.nextString())
                            "imageBase64" -> {
                                assertEquals(expectedImage, json.nextString())
                                imageCount++
                            }
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    entryCount++
                }
                json.endArray()
                json.endObject()
            }
            assertEquals(entries, entryCount)
            assertEquals(images, imageCount)
        } finally {
            database.close()
            context.deleteDatabase(name)
            file.delete()
        }
    }

    @Test
    fun masksRecognizableSensitiveDataInAllExportedTextFields() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("history-redaction-test", ".json", context.cacheDir)
        val database = Room.inMemoryDatabaseBuilder(context, PushDatabase::class.java).build()
        try {
            val exporter = HistoryExport(context.contentResolver, context.cacheDir, database.storage)
            val now = System.currentTimeMillis()
            database.storage.appendNotification(
                JournalItem(
                    observedAtMillis = now,
                    packageName = "example.export",
                    title = "Код подтверждения: 4629",
                    body = "Заказ готов. Цена 275 ₽. +7 (900) 123-45-67. user@example.test",
                    archivedText = "https://example.test/order?token=private and 8310 — login code",
                ),
                retentionRevision = database.storage.contentRevision(),
            )
            val options = HistoryExportOptions(null, 1, titles = true, text = true, images = false, untilMillis = now)
            exporter.write(Uri.fromFile(file), options)

            val entry = JSONObject(file.readText()).getJSONArray("entries").getJSONObject(0)
            assertEquals("Код подтверждения: [code]", entry.getString("title"))
            assertEquals("Заказ готов. Цена 275 ₽. [number]. [email]", entry.getString("text"))
            assertEquals("[link] and [code] — login code", entry.getString("additionalText"))
        } finally {
            database.close()
            file.delete()
        }
    }
}
