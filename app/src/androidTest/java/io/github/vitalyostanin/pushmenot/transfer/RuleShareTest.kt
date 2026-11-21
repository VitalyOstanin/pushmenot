package io.github.vitalyostanin.pushmenot.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
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
                clickSystemText(
                    "Rule file test receiver", alternateLabel = "Push Me Not Test Publisher", scroll = true,
                )
                clickSystemText("Open received rules", beforeClick = {
                    assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(emptyList())))
                    storage.setSource(packageName, false)
                })

                try {
                    compose.waitUntil(20_000) {
                        compose.onAllNodesWithTag("import_preview_list")
                            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                    }
                } catch (error: ComposeTimeoutException) {
                    shell("screencap -p /data/local/tmp/pushmenot-e2e-share.png")
                    throw error
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

    private suspend fun clickSystemText(
        label: String,
        alternateLabel: String? = null,
        scroll: Boolean = false,
        beforeClick: suspend () -> Unit = {},
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val configuration = automation.serviceInfo
        configuration.flags = configuration.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = configuration
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var root: AccessibilityNodeInfo? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            root = automation.rootInActiveWindow ?: automation.windows.firstNotNullOfOrNull {
                if (it.isActive || it.isFocused) it.root else null
            }
            val node = listOfNotNull(label, alternateLabel).firstNotNullOfOrNull { name ->
                root?.findAccessibilityNodeInfosByText(name)?.firstOrNull {
                    it.isVisibleToUser && it.isEnabled &&
                        (it.text?.toString()?.equals(name, ignoreCase = true) == true ||
                            it.contentDescription?.contains(name, ignoreCase = true) == true)
                }
            }
            if (node != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    beforeClick()
                    val target = generateSequence(node) { it.parent }.firstOrNull {
                        it.isClickable && it.isEnabled
                    }
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    }
                    return
                }
            } else if (scroll && root != null) {
                scrollSystemWindow(root)
            }
            kotlinx.coroutines.delay(150)
        }
        val visible = mutableListOf<String>()
        fun capture(node: AccessibilityNodeInfo) {
            if (visible.size >= 30) return
            (node.text ?: node.contentDescription ?: node.viewIdResourceName)?.let { visible += it.toString() }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::capture)
        }
        root?.let(::capture)
        shell("screencap -p /data/local/tmp/pushmenot-e2e-share.png")
        throw AssertionError("Could not click $label in ${root?.packageName}. Visible labels: $visible")
    }

    private fun scrollSystemWindow(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let(::scrollSystemWindow) == true) return true
        }
        return false
    }

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
}
