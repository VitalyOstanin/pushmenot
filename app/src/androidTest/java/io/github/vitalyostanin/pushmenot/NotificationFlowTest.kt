package io.github.vitalyostanin.pushmenot

import android.app.ActivityManager
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.TextCondition
import com.pushmenot.core.TextOperation
import io.github.vitalyostanin.pushmenot.listener.PushMeNotListenerService
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.sources.AndroidSources
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcut
import io.github.vitalyostanin.pushmenot.storage.HistoryQuery
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationFlowTest {
    @Test
    fun leavesUsefulCardInSameChannelAfterSourceIsEnabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val service = "${context.packageName}/.listener.PushMeNotListenerService"
        shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $service")
        try {
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            fixture("RESET")
            storage.setSource(publisher, enabled = true)
            storage.setContentRetention(true)
            storage.clearHistory()
            assertTrue(AndroidSources(context).installedApps().any { it.packageName == publisher })
            assertTrue(storage.historyPage(HistoryQuery(packageName = publisher)).isEmpty())

            post(100, "Blocked by Android permission")
            assertTrue("Android rejected the card before the listener", activeFixtureIds().isEmpty())
            assertTrue(storage.historyPage(HistoryQuery(packageName = publisher)).isEmpty())

            shell("pm grant $publisher android.permission.POST_NOTIFICATIONS")
            val oldRules = storage.rules()
            assertNotNull(
                storage.replaceRules(
                    oldRules.version,
                    RuleJson.encode(
                        listOf(
                            FilterRule(
                                id = "hide-promos",
                                revision = 1,
                                packageName = null,
                                channelId = "orders",
                                action = Action.HIDE,
                            ),
                            FilterRule(
                                id = "keep-orders",
                                revision = 1,
                                packageName = publisher,
                                channelId = "orders",
                                title = TextCondition("Order"),
                                action = Action.KEEP,
                            ),
                        ),
                    ),
                ),
            )
            storage.setSource(publisher, enabled = false)
            post(99, "Discount with filtering off")
            await("unfiltered card stays active and is recorded with text by default") {
                val entry = storage.historyPage(HistoryQuery(packageName = publisher)).singleOrNull()
                entry != null && entry.verdict == null && entry.contentRetained &&
                    entry.title == "Discount with filtering off" && entry.dismissalRequestedAtMillis == null && 99 in activeFixtureIds()
            }
            fixture("RESET")
            storage.clearHistory()
            storage.setSource(publisher, enabled = true)
            post(101, "Order handed to courier")
            post(102, "Discount on delivery")

            try {
                await("useful card remains and promo removal is observed") {
                    val entries = storage.historyPage(HistoryQuery(packageName = publisher))
                    entries.any {
                        it.title == "Order handed to courier" && it.verdict == Action.KEEP.name &&
                            it.ruleId == "keep-orders" && it.ruleScope == "APP"
                    } &&
                        entries.any {
                            it.title == "Discount on delivery" && it.ruleId == "hide-promos" &&
                                it.ruleScope == "ALL_SELECTED_APPS" &&
                                it.dismissalRequestedAtMillis != null && it.removedAtMillis != null
                        } && activeFixtureIds().let { 101 in it && 102 !in it }
                }
            } catch (failure: AssertionError) {
                val entries = storage.historyPage(HistoryQuery(packageName = publisher)).joinToString {
                    "${it.title}: verdict=${it.verdict}, rule=${it.ruleId}, " +
                        "requested=${it.dismissalRequestedAtMillis != null}, removed=${it.removalReason}"
                }
                throw AssertionError("Synthetic journal: [$entries]. Publisher: ${fixture("ACTIVE")}", failure)
            }

            val ruleVersion = storage.rules().version
            shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
            assertTrue(storage.source(publisher)?.enabled == true)
            assertTrue(storage.rules().version == ruleVersion)
            assertFalse(activeFixtureIds().contains(102))
        } finally {
            fixture("RESET")
            shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun archivesRichUnfilteredCardsAndStillMatchesLiveTextAfterOptOut() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val service = "${context.packageName}/.listener.PushMeNotListenerService"
        shell("pm grant $publisher android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $service")
        try {
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            fixture("RESET")
            storage.setSource(publisher, false)
            storage.setContentRetention(true)
            storage.clearHistory()
            post(301, "Synthetic conversation", "messages")
            post(302, "Synthetic picture", "image")
            val publisherHistory = HistoryQuery(packageName = publisher)
            await("both messages and an image are archived") {
                val rows = storage.historyPage(publisherHistory)
                val picture = rows.firstOrNull { it.title == "Synthetic picture" }
                picture != null && storage.journalImage(picture.id) != null &&
                    rows.any { it.verdict == null && it.archivedText.orEmpty().let { text ->
                        "First synthetic message" in text && "Second synthetic message" in text &&
                            "Synthetic sender" in text
                    } }
            }

            storage.setSource(publisher, true)
            val rules = listOf(FilterRule(
                id = "mute-synthetic-promotion", revision = 1, packageName = publisher,
                title = TextCondition("Archive opt-out promotion"), action = Action.HIDE,
            ))
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(rules)))
            storage.setContentRetention(false)
            assertTrue(storage.historyPage(publisherHistory).all { !it.contentRetained && it.archivedText == null })
            // Wait for the service to observe the settings before checking the live decision.
            delay(500)
            post(303, "Archive opt-out promotion")
            await("text rule hides a card without storing its content") {
                storage.historyPage(publisherHistory).any {
                    it.ruleId == "mute-synthetic-promotion" && !it.contentRetained && it.title == null &&
                        it.removedAtMillis != null
                }
            }
        } finally {
            storage.setContentRetention(true)
            fixture("RESET")
            shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun opensTheSelectedPublishersAndroidNotificationSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        try {
            assertTrue(AndroidSources(context).openNotificationSettings(publisher))
            await("Android notification settings for the fixture app") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                root?.packageName?.toString() == "com.android.settings" &&
                    root.findAccessibilityNodeInfosByText("Push Me Not Test Publisher").isNotEmpty() &&
                    root.findAccessibilityNodeInfosByText("Notifications").isNotEmpty()
            }
        } finally {
            shell("input keyevent KEYCODE_BACK")
        }
    }

    @Test
    fun batteryWarningOpensSettingsAndDisappearsAfterExemption() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val service = "$packageName/.listener.PushMeNotListenerService"
        val power = context.getSystemService(PowerManager::class.java)
        val originallyExempt = power.isIgnoringBatteryOptimizations(packageName)
        try {
            shell("cmd deviceidle whitelist -$packageName")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                clickUi("battery_optimization_settings")
                clickUi("android_battery_settings")
                await("Android battery settings") {
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
                }
                shell("cmd deviceidle whitelist +$packageName")
                await("battery exemption granted") { power.isIgnoringBatteryOptimizations(packageName) }
                shell("input keyevent KEYCODE_BACK")
                clickUi("history_tab")
                await("journal active without the battery warning") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.packageName?.toString() == packageName &&
                        findNode(root, "history_apps") != null && findNode(root, "battery_optimization_settings") == null
                }
            }
        } finally {
            val sign = if (originallyExempt) "+" else "-"
            shell("cmd deviceidle whitelist $sign$packageName")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun backgroundRestrictionWarningOpensPushMeNotAppSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val service = "$packageName/.listener.PushMeNotListenerService"
        val power = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val originallyExempt = power.isIgnoringBatteryOptimizations(packageName)
        val appOp = "RUN_ANY_IN_BACKGROUND"
        val originalMode = Regex("$appOp: ([a-z]+)")
            .find(shell("cmd appops get $packageName $appOp"))?.groupValues?.get(1) ?: "default"
        try {
            shell("cmd deviceidle whitelist +$packageName")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            shell("cmd appops set $packageName $appOp ignore")
            await("background restriction applied") { activityManager.isBackgroundRestricted }
            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                clickUi("background_app_settings")
                clickUi("android_background_settings")
                try {
                    await("Android app settings for Push Me Not") {
                        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" &&
                            shell("dumpsys activity activities").contains("act=android.settings.APPLICATION_DETAILS_SETTINGS")
                    }
                } catch (failure: AssertionError) {
                    val activities = shell("dumpsys activity activities").lineSequence().filter {
                        "topResumedActivity" in it || "android.settings." in it
                    }.joinToString("\n")
                    throw AssertionError("App settings activity was not detected:\n$activities", failure)
                }
                shell("cmd appops set $packageName $appOp allow")
                await("background restriction removed") { !activityManager.isBackgroundRestricted }
                shell("input keyevent KEYCODE_BACK")
                clickUi("history_tab")
                await("journal active without the background restriction warning") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.packageName?.toString() == packageName &&
                        findNode(root, "history_apps") != null && findNode(root, "background_app_settings") == null
                }
            }
        } finally {
            shell("cmd appops set $packageName $appOp $originalMode")
            val sign = if (originallyExempt) "+" else "-"
            shell("cmd deviceidle whitelist $sign$packageName")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun changesJournalCapacityAndRestoresItWhenTheActivityReopens() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val storage = PushDatabase.open(context).storage
        val service = "$packageName/.listener.PushMeNotListenerService"
        val original = storage.journalCapacity()
        val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
        try {
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            ActivityScenario.launch<MainActivity>(launch).use {
                clickUi("settings_tab")
                clickUi("journal_capacity")
                clickUi("journal_capacity_5000")
                clickUi("journal_capacity_save")
                await("new capacity saved") { storage.journalCapacity() == 5_000 }
            }
            ActivityScenario.launch<MainActivity>(launch).use {
                clickUi("settings_tab")
                clickUi("journal_capacity")
                await("saved capacity selected") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.let { findNode(it, "journal_capacity_5000") }?.let { it.isSelected || it.isChecked } == true
                }
                clickUi("journal_capacity_1000")
                clickUi("journal_capacity_cancel")
                assertTrue(storage.journalCapacity() == 5_000)
            }
        } finally {
            storage.setJournalCapacity(original)
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun userSelectsSourceGrantsPermissionAndCreatesRuleFromHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = PushDatabase.open(context).storage
        val service = "${context.packageName}/.listener.PushMeNotListenerService"
        val locale = context.getSystemService(LocaleManager::class.java)
        val previousLocale = locale.applicationLocales
        val power = context.getSystemService(PowerManager::class.java)
        val originallyExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        try {
            shell("cmd deviceidle whitelist +${context.packageName}")
            locale.applicationLocales = LocaleList.forLanguageTags("en")
            shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            fixture("RESET")
            storage.setSource(publisher, enabled = false)
            storage.setContentRetention(true)
            storage.clearHistory()
            val startVersion = storage.rules().version
            assertNotNull(storage.replaceRules(startVersion, RuleJson.encode(emptyList())))
            shell("am start -W -n ${context.packageName}/.MainActivity")
            clickUi("sources_tab")
            clickUi("add_app")
            awaitVisible("Rules apply to apps with filtering turned on.")
            clickUi("source_search")
            shell("input text $publisher")
            shell("input keyevent KEYCODE_BACK")
            clickUi("source_filter_$publisher", scroll = true)
            await("filter enabled") { storage.source(publisher)?.enabled == true }
            assertTrue(storage.historyPage(HistoryQuery(packageName = publisher)).isEmpty())
            clickUi("source_settings_$publisher", scroll = true)
            await("Android notification settings") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                root?.packageName?.toString() == "com.android.settings" &&
                    root.findAccessibilityNodeInfosByText("Push Me Not Test Publisher").isNotEmpty()
            }
            clickUi("switch_widget", within = "main_switch_bar")
            await("publisher notification permission granted") {
                context.packageManager.checkPermission("android.permission.POST_NOTIFICATIONS", publisher) ==
                    PackageManager.PERMISSION_GRANTED
            }
            shell("input keyevent KEYCODE_BACK")
            clickUi("history_tab")
            post(110, "Discount on delivery")
            var entryId = 0L
            await("promo recorded") {
                entryId = storage.historyPage(HistoryQuery(packageName = publisher))
                    .firstOrNull { it.title == "Discount on delivery" }?.id ?: 0
                entryId > 0
            }
            clickUi("entry_new_rule", within = "history_entry_$entryId", scroll = true)
            clickUi("rule_save")
            await("rule saved") { RuleJson.decode(storage.rules().rulesJson).size == 1 }
            clickUi("OK")
            clickUi("history_tab")
            fixture("RESET")
            post(111, "Order handed to courier")
            post(112, "Discount on delivery")
            await("next promo removed and order kept") {
                activeFixtureIds().let { 111 in it && 112 !in it } &&
                    storage.historyPage(HistoryQuery(packageName = publisher)).any {
                        it.title == "Discount on delivery" && it.removedAtMillis != null &&
                            it.dismissalRequestedAtMillis != null
                    }
            }
            clickUi("sources_tab")
            clickUi("source_row_$publisher")
            clickUi("app_history")
            awaitVisible("App history · Push Me Not Test Publisher")
            shell("input keyevent KEYCODE_BACK")
            clickUi("app_rules")
            awaitVisible("App rules · Push Me Not Test Publisher")
            clickUi("rules_tab")
            clickUi("global_rules")
            awaitVisible("Global rules")

            val second = storage.setSource("example.second", true)
            storage.appendNotification(
                JournalItem(System.currentTimeMillis(), packageName = second.packageName, title = "Second source"),
                storage.contentRevision(),
            )
            clickUi("history_tab")
            clickUi("history_apps")
            clickUi("Push Me Not Test Publisher")
            clickUi("example.second")
            clickUi("Apply filters")
            awaitVisible("Push Me Not Test Publisher, example.second")
            clickUi("history_reset")
            awaitVisible("All apps", scrollBackward = true)
            locale.applicationLocales = LocaleList.forLanguageTags("ru")
            clickUi("Настройки", within = "settings_tab")
            awaitVisible("Сохранять содержимое уведомлений")
        } finally {
            locale.applicationLocales = previousLocale
            val sign = if (originallyExempt) "+" else "-"
            shell("cmd deviceidle whitelist $sign${context.packageName}")
            fixture("RESET")
            shell("pm revoke $publisher android.permission.POST_NOTIFICATIONS")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun opensARuleFileFromAnExternalProviderAndRequiresConfirmation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val original = storage.rules().rulesJson
        val before = storage.rules().version
        try {
            val resolved = shell(
                "cmd package query-activities --brief -a android.intent.action.VIEW " +
                    "-d content://test.pushmenot.rules/rules.pushmenot -t application/octet-stream",
            )
            assertTrue(resolved, resolved.contains(context.packageName + "/"))
            val launch = shell("am start -W -n $publisher/.FixtureActivity --es rule_delivery view")
            try {
                awaitVisible("Review import")
            } catch (failure: AssertionError) {
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                val packageName = root?.packageName
                val labels = listOf("Could not open the rules file", "Choose a file exported from Push Me Not.")
                    .filter { root != null && findNode(root, it) != null }
                val problems = shell("logcat -d -t 150 AndroidRuntime:E ActivityTaskManager:I '*:S'")
                    .lineSequence().filter { it.contains(publisher) || it.contains(context.packageName) }.toList().takeLast(15)
                val details = listOf("External launch: $launch", "Foreground: $packageName, $labels") + problems
                throw AssertionError(details.joinToString(System.lineSeparator()), failure)
            }
            assertTrue(storage.rules().version == before)
            clickUi("rule_append")
            await("rule appended by confirmation") {
                RuleJson.decode(storage.rules().rulesJson).any { it.id == "opened-external-rule" }
            }
            clickUi("OK")
            val version = storage.rules().version
            val second = shell("am start -W -n $publisher/.FixtureActivity -a $publisher.REOPEN_RULES --es rule_delivery send")
            try {
                awaitVisible("Review import")
                await("append is disabled for a repeated file") {
                    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    root?.let { findNode(it, "rule_append") }?.isEnabled == false
                }
            } catch (failure: AssertionError) {
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                val labels = listOf("Review import", "Could not open the rules file", "All rules in this file are already present.")
                    .filter { root != null && findNode(root, it) != null }
                val problems = shell("logcat -d -t 150 AndroidRuntime:E ActivityTaskManager:I '*:S'")
                    .lineSequence().filter { it.contains(publisher) || it.contains(context.packageName) }.toList().takeLast(15)
                val details = listOf("Shared file launch: $second", "Foreground: ${root?.packageName}, $labels") + problems
                throw AssertionError(details.joinToString(System.lineSeparator()), failure)
            }
            clickUi("import_mode_replace")
            awaitVisible("Replace rules")
            clickUi("Cancel", scroll = true)
            assertTrue(storage.rules().version == version)
        } finally {
            storage.replaceRules(storage.rules().version, original)
        }
    }

    @Test
    fun tappingTheQuietShortcutOpensTheGeneralJournalFromAnotherTab() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val storage = PushDatabase.open(context).storage
        val pkg = context.packageName
        val service = "$pkg/.listener.PushMeNotListenerService"
        val permission = "android.permission.POST_NOTIFICATIONS"
        try {
            shell("pm grant $pkg $permission")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            shell("am start -W -n $pkg/.MainActivity")
            await("quiet journal shortcut") {
                manager.activeNotifications.any { it.id == HistoryShortcut.ID }
            }
            clickUi("rules_tab")
            manager.activeNotifications.single { it.id == HistoryShortcut.ID }.notification.contentIntent.send()
            awaitVisible("History")
            clickUi("history_apps")
            assertTrue(storage.historyPage(HistoryQuery(packageName = pkg)).isEmpty())
        } finally {
            manager.cancel(HistoryShortcut.ID)
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun showsStatisticsAndRestoresAnEarlierVersionFromTheRulesTab() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val original = storage.rules().rulesJson
        val old = FilterRule("screen-version", 1, "example.shop", Action.HIDE, title = TextCondition("Old offer"))
        try {
            val prior = requireNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(listOf(old))))
            val edited = RuleJson.decode(prior.rulesJson).single().let {
                it.copy(revision = it.revision + 1, title = TextCondition("New offer"))
            }
            val current = requireNotNull(storage.replaceRules(prior.version, RuleJson.encode(listOf(edited))))
            shell("am start -W -n ${context.packageName}/.MainActivity")
            clickUi("history_tab")
            await("statistics in the journal") {
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                root?.let { findNode(it, "journal_statistics") } != null
            }
            clickUi("rules_tab")
            clickUi("rule_versions", scroll = true)
            awaitVisible("Version history")
            await("latest rules loaded into version history") {
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                root?.let { findNode(it, "version_${current.activeRevisionId}") } != null
            }
            clickUi("compare_${prior.activeRevisionId}", scroll = true)
            awaitVisible("To update: 1")
            clickUi("version_restore", scroll = true)
            await("the previous rule is active") {
                RuleJson.decode(storage.rules().rulesJson).singleOrNull()?.title == old.title
            }
            clickUi("OK")
            awaitVisible("Version history")
        } finally {
            storage.replaceRules(storage.rules().version, original)
        }
    }

    @Test
    fun suggestsAnExactRuleForAnActiveCardAndEnablesItsSourceWhenSaved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val service = "${context.packageName}/.listener.PushMeNotListenerService"
        val permission = "android.permission.POST_NOTIFICATIONS"
        val original = storage.rules().rulesJson
        val originalSource = storage.source(publisher)
        val title = "Synthetic promotion from the shade"
        try {
            shell("pm grant $publisher $permission")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            fixture("RESET")
            storage.setSource(publisher, false)
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(emptyList())))
            val startingVersion = storage.rules().version
            post(531, title)
            post(533, "Synthetic protected card", "ongoing")
            await("the notifications are present before the app opens") {
                activeFixtureIds().containsAll(listOf(531, 533))
            }

            val launch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {
                awaitVisible("In the notification shade")
                awaitVisible("Push Me Not Test Publisher")
                clickUi("shade_rule_$publisher", scroll = true)
                awaitVisible("Turn on filtering and save")
                assertFalse(storage.source(publisher)?.enabled == true)
                assertTrue(storage.rules().version == startingVersion)
                post(532, "$title with a different ending")
                clickUi("rule_save")
                await("the exact rule and its source are saved") {
                    val rule = RuleJson.decode(storage.rules().rulesJson).singleOrNull()
                    storage.source(publisher)?.enabled == true && rule?.packageName == publisher &&
                        rule.channelId == "orders" && rule.title?.value == title &&
                        rule.title?.operation == TextOperation.EQUALS
                }
                await("only the matching notification is dismissed") {
                    val active = activeFixtureIds()
                    531 !in active && 532 in active && 533 in active &&
                        storage.historyPage(HistoryQuery(packageName = publisher)).any {
                            it.title == title && it.verdict == Action.HIDE.name && it.removedAtMillis != null
                        }
                }
            }
        } finally {
            fixture("RESET")
            storage.replaceRules(storage.rules().version, original)
            storage.setSource(publisher, originalSource?.enabled == true)
            shell("pm revoke $publisher $permission")
            shell("cmd notification disallow_listener $service")
        }
    }

    @Test
    fun appliesASavedRuleToAnAlreadyVisibleNotificationWithoutCountingItAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val service = "${context.packageName}/.listener.PushMeNotListenerService"
        val original = storage.rules().rulesJson
        val permission = "android.permission.POST_NOTIFICATIONS"
        val history = HistoryQuery(packageName = publisher)
        try {
            shell("pm grant $publisher $permission")
            shell("cmd notification allow_listener $service")
            await("listener connected") { PushMeNotListenerService.isConnected.value }
            fixture("RESET")
            storage.setSource(publisher, true)
            storage.clearHistory()
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(emptyList())))
            post(521, "Synthetic seller promotion")
            post(522, "Synthetic order is ready")
            post(523, "Synthetic seller promotion protected", "ongoing")
            var originalLifecycle: String? = null
            await("the three cards were already visible before the rule was saved") {
                originalLifecycle = storage.historyPage(history).firstOrNull {
                    it.title == "Synthetic seller promotion" && it.countAsNew
                }?.lifecycleId
                originalLifecycle != null && activeFixtureIds().containsAll(listOf(521, 522, 523))
            }
            val hide = FilterRule(
                "recheck-active-synthetic", 1, publisher, Action.HIDE,
                title = TextCondition("Synthetic seller promotion"),
            )
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(listOf(hide))))
            await("only the existing unprotected promotion is dismissed") {
                val active = activeFixtureIds()
                521 !in active && 522 in active && 523 in active && storage.historyPage(history).any {
                    it.ruleId == hide.id && it.removedAtMillis != null &&
                        it.lifecycleId == originalLifecycle && !it.countAsNew
                }
            }
            assertTrue(storage.historyPage(history).count { it.lifecycleId == originalLifecycle && it.countAsNew } == 1)
        } finally {
            fixture("RESET")
            storage.replaceRules(storage.rules().version, original)
            shell("pm revoke $publisher $permission")
            shell("cmd notification disallow_listener $service")
        }
    }

    private suspend fun awaitVisible(text: String, scrollBackward: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        await("visible label $text") {
            val root = automation.rootInActiveWindow ?: return@await false
            if (findNode(root, text) != null) return@await true
            if (scrollBackward) scrollBack(root)
            false
        }
    }

    private fun scrollBack(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (scrollBack(child)) return true
        }
        return false
    }

    private fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until node.childCount) {
            if (node.getChild(index)?.let(::scrollForward) == true) return true
        }
        return false
    }

    private suspend fun clickUi(identifier: String, within: String? = null, scroll: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            await("clickable element $identifier") {
                val root = automation.rootInActiveWindow ?: return@await false
                val container = if (within == null) root else findNode(root, within) ?: return@await false
                val node = findNode(container, identifier) ?: run {
                    if (scroll) scrollForward(root)
                    return@await false
                }
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } catch (failure: AssertionError) {
            val visible = mutableListOf<String>()
            fun capture(node: AccessibilityNodeInfo) {
                if (visible.size >= 25) return
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.viewIdResourceName
                if (label != null) visible += label
                for (index in 0 until node.childCount) node.getChild(index)?.let(::capture)
            }
            automation.rootInActiveWindow?.let(::capture)
            throw AssertionError("Element $identifier is unavailable. Visible labels: $visible", failure)
        }
    }

    private fun findNode(node: AccessibilityNodeInfo, identifier: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.substringAfterLast('/') == identifier || node.text?.toString() == identifier) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNode(child, identifier)?.let { return it }
        }
        return null
    }

    private suspend fun await(description: String, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(150)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun activeFixtureIds(): Set<Int> =
        Regex("""data="([0-9,]*)"""").find(fixture("ACTIVE"))?.groupValues?.get(1)
            .orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()

    private fun post(id: Int, title: String, style: String = "standard") {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(title.toByteArray())
        fixture("POST --ei id $id --es title64 $encoded --es style $style")
    }

    private fun fixture(actionAndArgs: String): String {
        val result = shell(
            "am broadcast -f 0x20 -n $publisher/.FixtureReceiver -a $publisher.$actionAndArgs",
        )
        assertTrue(result, result.contains("Broadcast completed"))
        return result
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private companion object {
        const val publisher = "io.github.vitalyostanin.pushmenot.fixture"
    }
}
