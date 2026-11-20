package io.github.vitalyostanin.pushmenot.transfer

import android.content.ContentResolver
import android.net.Uri
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import io.github.vitalyostanin.pushmenot.storage.RuleChange
import io.github.vitalyostanin.pushmenot.storage.RuleSet
import io.github.vitalyostanin.pushmenot.storage.RuleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ImportMode { APPEND, REPLACE }

class ImportPreview internal constructor(
    val version: Long,
    val addedIds: List<String>,
    val changedIds: List<String>,
    val removedIds: List<String>,
    val orderChanged: Boolean,
    val scopeChangedIds: List<String>,
    val affectedPackageNames: Set<String>,
    val allSelectedAppsAffected: Boolean,
    internal val replacement: String,
    val appendedIds: List<String>,
    val skippedCount: Int,
    internal val addition: String?,
    val restoreSnapshot: RuleSnapshot? = null,
) {
    val canAppend: Boolean get() = addition != null && appendedIds.isNotEmpty()
    val appendFits: Boolean get() = addition != null
}

class RuleTransfer(
    private val resolver: ContentResolver,
    private val storage: PushStorage,
) {
    suspend fun prepareImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val json = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break
                require(text.length + count <= RuleJson.MAX_JSON_CHARS) { "Rule set is too large" }
                text.append(buffer, 0, count)
            }
            text.toString()
        } ?: throw IOException("Unable to open the selected document")

        preview(storage.rules(), RuleJson.decodeImport(json))
    }

    /** A null result means the rules changed while the preview was open. */
    suspend fun applyImport(preview: ImportPreview, mode: ImportMode = ImportMode.REPLACE): RuleSet? {
        val snapshot = preview.restoreSnapshot
        return when {
            snapshot != null -> storage.replaceRules(preview.version, snapshot.rulesJson, RuleChange.RESTORE)
            mode == ImportMode.APPEND -> storage.replaceRules(preview.version, requireNotNull(preview.addition), RuleChange.APPEND)
            else -> storage.replaceRules(preview.version, preview.replacement, RuleChange.REPLACE)
        }
    }

    suspend fun prepareRestore(revisionId: String): ImportPreview? {
        val snapshot = storage.ruleHistory.snapshot(revisionId) ?: return null
        return preview(storage.rules(), RuleJson.decode(snapshot.rulesJson), snapshot)
    }

    suspend fun export(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val json = RuleJson.encode(RuleJson.decode(storage.rules().rulesJson))
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open the selected document")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
    }

    private fun preview(current: RuleSet, imported: List<FilterRule>, restore: RuleSnapshot? = null): ImportPreview {
        val previous = RuleJson.decode(current.rulesJson)
        val knownIds = previous.mapTo(mutableSetOf(), FilterRule::id)
        val knownSemantics = previous.mapTo(mutableSetOf(), ::identity)
        val fresh = if (restore == null) imported.filter { rule ->
            rule.id !in knownIds && knownSemantics.add(identity(rule)) && knownIds.add(rule.id)
        } else emptyList()
        val previousById = previous.associateBy(FilterRule::id)
        val importedIds = imported.map(FilterRule::id)
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val replacement = imported.map { incoming ->
            val existing = previousById[incoming.id]
            when {
                existing == null -> incoming.also { added += it.id }
                incoming.copy(revision = existing.revision) == existing -> existing
                else -> {
                    val revision = maxOf(existing.revision, incoming.revision)
                    require(revision < Long.MAX_VALUE) { "Rule revision is out of range" }
                    changed += incoming.id
                    incoming.copy(revision = revision + 1)
                }
            }
        }
        val importedSet = importedIds.toSet()
        val previousIds = previous.map(FilterRule::id)
        val removed = previousIds.filterNot(importedSet::contains)
        val sharedBefore = previousIds.filter(importedSet::contains)
        val sharedAfter = importedIds.filter(previousById::containsKey)
        val moved = sharedAfter.filterIndexed { index, id -> sharedBefore[index] != id }
        val scopeChanged = imported.filter { incoming ->
            previousById[incoming.id]?.let { it.packageName != incoming.packageName } == true
        }.map(FilterRule::id)
        val affectedIds = (added + changed + removed + moved).toSet()
        val affectedRules = (previous + replacement).filter { it.id in affectedIds }
        return ImportPreview(
            version = current.version,
            addedIds = added.toList(),
            changedIds = changed.toList(),
            removedIds = removed,
            orderChanged = moved.isNotEmpty(),
            scopeChangedIds = scopeChanged,
            affectedPackageNames = affectedRules.mapNotNull(FilterRule::packageName).toSet(),
            allSelectedAppsAffected = affectedRules.any { it.packageName == null },
            replacement = if (replacement == previous) current.rulesJson else RuleJson.encode(replacement),
            appendedIds = fresh.map(FilterRule::id),
            skippedCount = if (restore == null) imported.size - fresh.size else 0,
            addition = if (fresh.isEmpty()) current.rulesJson else try {
                RuleJson.encode(previous + fresh)
            } catch (_: IllegalArgumentException) {
                null
            },
            restoreSnapshot = restore,
        )
    }

    private fun identity(rule: FilterRule): FilterRule = rule.copy(id = "same", revision = 1, enabled = true)

    companion object {
        const val MIME = "application/vnd.pushmenot.rules+json"
        const val NAME = "push-me-not-rules.pushmenot"
    }
}
