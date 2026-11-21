package io.github.vitalyostanin.pushmenot.updates

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.pushmenot.MainActivity
import io.github.vitalyostanin.pushmenot.TestAppLocale
import org.junit.Rule
import org.junit.Test

class UpdateSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @SdkSuppress(maxSdkVersion = 32)
    fun opensPhoneLanguageSettingsWhenPerAppLanguageIsUnavailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("settings_tab").performClick()
            val button = "Open phone language settings"
            compose.onNodeWithTag("settings_content").performScrollToNode(hasText(button))
            compose.onNodeWithText("On this Android version, Push Me Not uses the phone language.").assertExists()
            compose.onNodeWithText(button).performClick()
            compose.waitUntil(10_000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
            }
        }
    }

    @Test
    fun settingsCheckStartsWithInstalledVersionAndShowsResultAfterTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = TestAppLocale(context)
        try {
            locale.set("en")
            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                compose.onNodeWithTag("settings_tab").performClick()
                val settings = compose.onNodeWithTag("settings_content")
                val idle = "Tap to check GitHub for a new version."
                settings.performScrollToNode(hasText(idle))
                @Suppress("DEPRECATION")
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                compose.onNodeWithText("Installed version $version").assertExists()
                compose.onNodeWithText(idle).assertIsDisplayed()
                settings.performScrollToNode(hasTestTag("check_for_updates"))
                compose.onNodeWithTag("check_for_updates").performClick()
                val result = hasText("Could not check for updates. Try again.") or
                    hasText("A public release is currently unavailable on GitHub.") or
                    hasText("GitHub has no newer version.") or hasTestTag("open_update_release")
                compose.waitUntil(20_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            locale.restore()
        }
    }
}
