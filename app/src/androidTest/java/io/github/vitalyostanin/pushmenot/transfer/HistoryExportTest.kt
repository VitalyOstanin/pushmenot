package io.github.vitalyostanin.pushmenot.transfer

import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.pushmenot.MainActivity
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import java.io.File
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
