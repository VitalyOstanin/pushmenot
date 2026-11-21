package io.github.vitalyostanin.pushmenot.transfer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.TextCondition
import io.github.vitalyostanin.pushmenot.rules.ModeScope
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.rules.unmatchedAction
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleTransferTest {
    @Test
    fun exportsRulesAndAppliesOnlyAConfirmedCurrentPreview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val transfer = RuleTransfer(context.contentResolver, storage)
        val original = storage.rules().rulesJson
        val document = File.createTempFile("rules", ".json", context.cacheDir)
        try {
            val rule = FilterRule(
                id = "example",
                revision = 1,
                packageName = "example.shop",
                action = Action.HIDE,
                title = TextCondition("Discount"),
            )
            val from = Uri.fromFile(document)
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(listOf(rule))))
            transfer.export(from)
            assertEquals(listOf(rule), RuleJson.decodeImport(document.readText()))

            val empty = RuleJson.encode(emptyList())
            assertNotNull(storage.replaceRules(storage.rules().version, empty))
            val preview = transfer.prepareImport(from)
            assertEquals(listOf("example"), preview.addedIds)
            assertEquals(empty, storage.rules().rulesJson)
            assertNotNull(transfer.applyImport(preview))
            // Reintroducing a removed rule reserves a fresh revision for journal references.
            assertEquals(listOf(rule.copy(revision = 2)), RuleJson.decode(storage.rules().rulesJson))
            assertNull(transfer.applyImport(preview))
        } finally {
            storage.replaceRules(storage.rules().version, original)
            document.delete()
        }
    }

    @Test
    fun transfersEnabledAppsEvenWithoutLocalRulesOrInstalledPackages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val transfer = RuleTransfer(context.contentResolver, storage)
        val absentApp = "example.transfer.absent"
        val unrelatedApp = "example.transfer.local"
        val document = File.createTempFile("rules-app-settings", ".json", context.cacheDir)
        try {
            storage.setSource(absentApp, true)
            val uri = Uri.fromFile(document)
            transfer.export(uri)
            assertTrue(absentApp in requireNotNull(RuleJson.decodeDocument(document.readText()).enabledApps))
            storage.setSource(absentApp, false)
            storage.setSource(unrelatedApp, true)

            val preview = transfer.prepareImport(uri)
            assertNotNull(transfer.applyImport(preview, ImportMode.APPEND, importApps = false))
            assertEquals(false, storage.source(absentApp)?.enabled)
            assertNotNull(transfer.applyImport(preview, ImportMode.APPEND, importApps = true))
            assertEquals(true, storage.source(absentApp)?.enabled)
            assertEquals(true, storage.source(unrelatedApp)?.enabled)
        } finally {
            storage.setSource(absentApp, false)
            storage.setSource(unrelatedApp, false)
            document.delete()
        }
    }

    @Test
    fun restoresAppAndChannelModesFromRuleVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val transfer = RuleTransfer(context.contentResolver, storage)
        val original = storage.rules().rulesJson
        val name = "example.transfer.mode"
        val modes = mapOf(ModeScope(name) to Action.HIDE, ModeScope(name, "orders") to Action.KEEP)
        try {
            val saved = requireNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(emptyList(), modes = modes)))
            val snapshot = requireNotNull(saved.activeRevisionId)
            assertNotNull(storage.replaceRules(saved.version, RuleJson.encode(emptyList())))

            val preview = requireNotNull(transfer.prepareRestore(snapshot))
            assertTrue(preview.modesChanged)
            val restored = requireNotNull(transfer.applyImport(preview))
            val settings = RuleJson.decodeStored(restored.rulesJson).modes
            assertEquals(Action.HIDE, settings.unmatchedAction(name, "promos"))
            assertEquals(Action.KEEP, settings.unmatchedAction(name, "orders"))
        } finally {
            storage.replaceRules(storage.rules().version, original)
        }
    }

    @Test
    fun changingScopeStartsANewRevisionAndDoesNotSelectAnApp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = PushDatabase.open(context).storage
        val transfer = RuleTransfer(context.contentResolver, storage)
        val original = storage.rules().rulesJson
        val document = File.createTempFile("rules-scoped", ".json", context.cacheDir)
        val app = FilterRule("scope-test", 6, "example.shop", Action.HIDE)
        val global = app.copy(packageName = null, revision = 1)
        val originalSource = storage.source(requireNotNull(app.packageName))
        try {
            assertNotNull(storage.replaceRules(storage.rules().version, RuleJson.encode(listOf(app))))
            document.writeText(RuleJson.encode(listOf(global)))
            val importPreview = transfer.prepareImport(Uri.fromFile(document))
            assertEquals(listOf(app.id), importPreview.scopeChangedIds)
            assertEquals(setOf(app.packageName), importPreview.affectedPackageNames)
            assertTrue(importPreview.allSelectedAppsAffected)
            assertNotNull(transfer.applyImport(importPreview))

            val updated = storage.rules()
            assertEquals(listOf(global.copy(revision = 7)), RuleJson.decode(updated.rulesJson))
            assertEquals(originalSource, storage.source(requireNotNull(app.packageName)))
            val second = transfer.prepareImport(Uri.fromFile(document))
            assertTrue(second.changedIds.isEmpty())
            assertEquals(updated.version, transfer.applyImport(second)?.version)
        } finally {
            storage.replaceRules(storage.rules().version, original)
            document.delete()
        }
    }
}
