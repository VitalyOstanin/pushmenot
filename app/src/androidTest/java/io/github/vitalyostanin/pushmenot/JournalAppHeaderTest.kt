package io.github.vitalyostanin.pushmenot

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalAppHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun appIdentityAppearsAboveNotificationTextWithFallbackForUnavailableApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val originalRetention = runBlocking { storage.contentRevision() != null }
        val title = "Synthetic journal title"
        val body = "Synthetic notification without a title"
        val unavailablePackage = "example.uninstalled"
        try {
            val (installedId, unavailableId) = runBlocking {
                storage.setContentRetention(true)
                val revision = storage.contentRevision()
                val now = System.currentTimeMillis()
                val installed = storage.appendNotification(JournalItem(now, packageName = context.packageName, title = title), revision)
                val unavailable = storage.appendNotification(JournalItem(now - 1, packageName = unavailablePackage, body = body), revision)
                installed to unavailable
            }
            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                val history = compose.onNodeWithTag("history_list")
                val installedCard = hasTestTag("history_entry_$installedId")
                val unavailableCard = hasTestTag("history_entry_$unavailableId")
                history.performScrollToNode(installedCard)
                val installed = hasAnyAncestor(installedCard)
                val icon = installed and hasTestTag("journal_app_icon")
                compose.waitUntil(5_000) {
                    compose.onAllNodes(icon, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(icon, useUnmergedTree = true).assertIsDisplayed()
                val nameBounds = compose.onNode(installed and hasText("Push Me Not"), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val titleBounds = compose.onNode(installed and hasText(title), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(nameBounds.bottom <= titleBounds.top)

                history.performScrollToNode(unavailableCard)
                val unavailable = hasAnyAncestor(unavailableCard)
                compose.onNode(unavailable and hasTestTag("journal_app_placeholder"), useUnmergedTree = true)
                    .assertIsDisplayed()
                val packageBounds = compose.onNode(unavailable and hasText(unavailablePackage), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val bodyBounds = compose.onNode(unavailable and hasText(body), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(packageBounds.bottom <= bodyBounds.top)
            }
        } finally {
            runBlocking { storage.setContentRetention(originalRetention) }
        }
    }
}
