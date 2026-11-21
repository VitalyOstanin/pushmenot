package io.github.vitalyostanin.pushmenot

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.Action
import com.pushmenot.core.DecisionReason
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphiteThemeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsAndRecordedJournalDecisionsUseBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiMode = context.getSystemService(UiModeManager::class.java)
        val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))

        val storage = PushDatabase.open(context).storage
        val recordedCards = runBlocking {
            val time = System.currentTimeMillis()
            val entry = JournalItem(time, packageName = "example.theme.shop", title = "Theme test")
            val revision = storage.contentRevision()
            listOf(
                storage.appendNotification(
                    entry.copy(verdict = Action.HIDE.name, decisionReason = DecisionReason.UNMATCHED_MODE.name),
                    revision,
                ),
                storage.appendNotification(
                    entry.copy(verdict = Action.KEEP.name, decisionReason = DecisionReason.RULE_MATCH.name),
                    revision,
                ),
                storage.appendNotification(
                    entry.copy(verdict = Action.KEEP.name, decisionReason = DecisionReason.NO_MATCH.name),
                    revision,
                ),
            )
        }

        try {
            val expectedBackgrounds = mapOf(
                UiModeManager.MODE_NIGHT_NO to 0xfff7f8fc.toInt(),
                UiModeManager.MODE_NIGHT_YES to 0xff171923.toInt(),
            )
            for ((mode, expectedBackground) in expectedBackgrounds) {
                uiMode.setApplicationNightMode(mode)
                ActivityScenario.launch<MainActivity>(launch).use {
                    val expectedCards = if (mode == UiModeManager.MODE_NIGHT_YES) {
                        listOf(0xff39272f, 0xff23352e, 0xff252936)
                    } else {
                        listOf(0xfffff0f1, 0xffe8f5ec, 0xffffffff)
                    }
                    for ((entryId, expectedCard) in recordedCards.zip(expectedCards)) {
                        val cardTag = "history_entry_$entryId"
                        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag(cardTag))
                        val card = compose.onNodeWithTag(cardTag, useUnmergedTree = true).captureToImage().toPixelMap()
                        assertEquals(
                            "Journal entry $entryId in night mode $mode",
                            expectedCard.toInt(),
                            card[card.width / 2, 8].toArgb(),
                        )
                    }
                    compose.onNodeWithTag("settings_tab").performClick()
                    val screen = compose.onNodeWithTag("settings_content").captureToImage().toPixelMap()
                    assertEquals("Settings background for Android night mode $mode", expectedBackground, screen[2, 2].toArgb())
                }
            }
        } finally {
            uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }
}
