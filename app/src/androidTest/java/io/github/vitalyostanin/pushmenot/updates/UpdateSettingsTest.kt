package io.github.vitalyostanin.pushmenot.updates

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vitalyostanin.pushmenot.MainActivity
import org.junit.Rule
import org.junit.Test

class UpdateSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsCheckStartsWithInstalledVersionAndShowsResultAfterTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = context.getSystemService(LocaleManager::class.java)
        val previousLocale = locale.applicationLocales
        try {
            locale.applicationLocales = LocaleList.forLanguageTags("en")
            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                compose.onNodeWithTag("settings_tab").performClick()
                val settings = compose.onNodeWithTag("settings_content")
                val idle = "Tap to check GitHub for a new version."
                settings.performScrollToNode(hasText(idle))
                val version = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0),
                ).versionName
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
            locale.applicationLocales = previousLocale
        }
    }
}
