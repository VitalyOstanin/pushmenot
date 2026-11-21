package io.github.vitalyostanin.pushmenot.transfer

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.MainActivity
import io.github.vitalyostanin.pushmenot.TestAppLocale
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleShareTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun receiverOpensSharedRulesAndAppSettingsForImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val original = storage.rules().rulesJson
        val locale = TestAppLocale(context)
        val packageName = "example.shared.app"
        val rule = FilterRule("shared-file-test", 1, packageName, Action.HIDE)
        try {
            locale.set("en")
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(listOf(rule))))
            storage.setSource(packageName, true)

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("rules_tab").performClick()
                compose.onNode(hasTestTag("rule_export")).performClickWhenVisible()
                compose.onNodeWithTag("rule_export_cancel").performClick()
                compose.onNodeWithTag("rule_export_share").assertDoesNotExist()

                compose.onNodeWithTag("rule_export").performClick()
                compose.onNodeWithTag("rule_export_share").performClick()
                clickSystemText("Rule file test receiver")
                clickSystemText("Open received rules", beforeClick = {
                    assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(emptyList())))
                    storage.setSource(packageName, false)
                })

                compose.waitUntil(20_000) {
                    compose.onAllNodesWithTag("import_preview_list").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("import_preview_list").performScrollToNode(hasTestTag("rule_append"))
                compose.onNodeWithTag("rule_append").performClick()
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (storage.source(packageName)?.enabled != true && SystemClock.elapsedRealtime() < deadline) {
                    kotlinx.coroutines.delay(50)
                }
                assertTrue(storage.source(packageName)?.enabled == true)
                assertTrue(RuleJson.decode(storage.rules().rulesJson).any { it.id == rule.id })
            }
        } finally {
            storage.replaceRules(storage.rules().version, original)
            storage.setSource(packageName, false)
            locale.restore()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickWhenVisible() {
        if (compose.onAllNodesWithTag("rule_export").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("rules_list").performScrollToNode(hasTestTag("rule_export"))
        }
        performClick()
    }

    private suspend fun clickSystemText(label: String, beforeClick: suspend () -> Unit = {}) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (node != null) {
                beforeClick()
                var button: AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $label" }
                return
            }
            kotlinx.coroutines.delay(100)
        }
        throw AssertionError("Not visible: $label")
    }
}
