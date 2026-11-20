package io.github.vitalyostanin.pushmenot.reliability

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundSettingsTest {
    @Test
    fun detectsBatteryExemptionAndOpensAndroidSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = BackgroundSettings(context)
        val original = settings.read().ignoresBatteryOptimizations
        assertNotNull(original)
        try {
            shell("cmd deviceidle whitelist -${context.packageName}")
            await("battery optimization enabled") {
                settings.read().ignoresBatteryOptimizations == false
            }
            shell("cmd deviceidle whitelist +${context.packageName}")
            await("battery optimization exemption") {
                settings.read().ignoresBatteryOptimizations == true
            }

            assertNotNull(settings.openBatteryOptimizations())
            await("Android settings activity") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
            }
        } finally {
            shell("input keyevent KEYCODE_BACK")
            val sign = if (original == true) "+" else "-"
            shell("cmd deviceidle whitelist $sign${context.packageName}")
        }
    }

    private suspend fun await(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            delay(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
}
