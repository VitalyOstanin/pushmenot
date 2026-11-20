package io.github.vitalyostanin.pushmenot.transfer

import android.content.ContentResolver
import android.net.Uri
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import io.github.vitalyostanin.pushmenot.storage.RuleChange
import io.github.vitalyostanin.pushmenot.storage.RuleSet
import io.github.vitalyostanin.pushmenot.storage.RuleSnapshot
import java.io.Reader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ArchivePreview internal constructor(
    internal val uri: Uri,
    internal val version: Long,
    internal val digest: String,
    val missingVersions: Int,
    val activeCount: Int,
)

class RuleArchive(private val resolver: ContentResolver, private val storage: PushStorage) {
    suspend fun prepare(uri: Uri): ArchivePreview = withContext(Dispatchers.IO) {
        val version = storage.rules().version
        var missing = 0
        val archive = read(uri) { snapshot ->
            if (storage.ruleHistory.isMissing(snapshot)) missing++
        }
        ArchivePreview(uri, version, archive.digest, missing, archive.active.count)
    }

    suspend fun restore(preview: ArchivePreview): RuleSet? = withContext(Dispatchers.IO) {
        storage.ruleHistory.restoreArchive(preview.version) { register ->
            val restored = read(preview.uri, register)
            require(restored.digest == preview.digest) { "Backup changed after preview" }
            restored.active
        }
    }

    suspend fun save(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val stream = requireNotNull(resolver.openOutputStream(uri, "wt"))
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            storage.ruleHistory.writeArchive(
                start = { id ->
                    val header = JSONObject().put("format", FORMAT).put("version", VERSION).put("active", id)
                    writer.appendLine(header.toString())
                },
                writeVersion = { snapshot ->
                    val row = JSONObject()
                        .put("id", snapshot.revisionId)
                        .put("createdAt", snapshot.createdAtMillis ?: JSONObject.NULL)
                        .put("reason", snapshot.reason)
                        .put("rules", snapshot.rulesJson)
                    writer.appendLine(row.toString())
                },
            )
        }
    }

    private suspend fun read(
        uri: Uri,
        onSnapshot: suspend (RuleSnapshot) -> Unit,
    ): ParsedArchive {
        val hash = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(resolver.openInputStream(uri))
        return DigestInputStream(input, hash).bufferedReader(Charsets.UTF_8).use { reader ->
            val header = JSONObject(requireNotNull(reader.boundedLine(512)))
            require(header.keys().asSequence().toSet() == setOf("format", "version", "active"))
            require(header.get("format") == FORMAT && header.get("version") == VERSION)
            val activeId = validateId(header.get("active") as String)
            val seen = HashSet<String>()
            var active: RuleSnapshot? = null
            while (true) {
                val line = reader.boundedLine(MAX_RECORD_CHARS) ?: break
                val row = JSONObject(line)
                require(row.keys().asSequence().toSet() == setOf("id", "createdAt", "reason", "rules"))
                val id = validateId(row.get("id") as String)
                require(seen.add(id)) { "Duplicate backup version" }
                val reason = RuleChange.valueOf(row.get("reason") as String)
                val date = if (row.isNull("createdAt")) null else (row.get("createdAt") as Number).let {
                    require(it is Int || it is Long)
                    it.toLong().also { time -> require(time >= 0) }
                }
                val rules = RuleJson.decodeImport(row.get("rules") as String)
                val snapshot = RuleSnapshot(id, date, reason.name, rules.size, RuleJson.encode(rules))
                if (id == activeId) active = snapshot
                onSnapshot(snapshot)
            }
            ParsedArchive(requireNotNull(active), hash.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun validateId(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value)
        return value
    }

    private fun Reader.boundedLine(maxLength: Int): String? {
        val line = StringBuilder()
        while (true) {
            when (val char = read()) {
                -1 -> return line.toString().takeIf { it.isNotEmpty() }
                '\n'.code -> return line.toString()
                else -> {
                    require(line.length < maxLength) { "Backup record is too large" }
                    line.append(char.toChar())
                }
            }
        }
    }

    private data class ParsedArchive(val active: RuleSnapshot, val digest: String)

    companion object {
        const val MIME = "application/vnd.pushmenot.backup"
        const val NAME = "push-me-not-history.pushmenot-backup"
        private const val FORMAT = "push-me-not-rule-history"
        private const val VERSION = 1
        private const val MAX_RECORD_CHARS = RuleJson.MAX_JSON_CHARS * 6 + 512
    }
}
