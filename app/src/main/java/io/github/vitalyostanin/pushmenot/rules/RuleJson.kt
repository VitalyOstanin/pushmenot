package io.github.vitalyostanin.pushmenot.rules

import android.util.JsonReader
import android.util.JsonToken
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.FrequencyCondition
import com.pushmenot.core.FrequencyGroup
import com.pushmenot.core.TextCondition
import com.pushmenot.core.TextOperation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader

data class ModeScope(val packageName: String, val channelId: String? = null)

internal data class RuleDocument(
    val rules: List<FilterRule>,
    val enabledApps: Set<String>? = null,
    val modes: Map<ModeScope, Action> = emptyMap(),
)

internal fun Map<ModeScope, Action>.unmatchedAction(packageName: String, channelId: String?): Action =
    get(ModeScope(packageName, channelId)) ?: get(ModeScope(packageName)) ?: Action.KEEP

object RuleJson {
    const val MAX_JSON_CHARS = 512 * 1024

    private const val APP_ONLY_VERSION = 1L
    private const val SCOPED_VERSION = 2L
    private const val APP_SETTINGS_VERSION = 3L
    private const val MODES_VERSION = 4L
    private const val MAX_RULES = 256
    private const val MAX_ID_CHARS = 128
    private const val MAX_SOURCE_CHARS = 256
    private const val MAX_TEXT_CHARS = 1024
    private const val MAX_DEPTH = 4
    private const val MAX_OBJECT_FIELDS = 16

    fun encode(rules: List<FilterRule>): String = encode(rules, modes = emptyMap())

    internal fun encode(
        rules: List<FilterRule>,
        enabledApps: Set<String>? = null,
        modes: Map<ModeScope, Action>,
    ): String {
        validate(rules)
        val version = when {
            modes.isNotEmpty() -> MODES_VERSION
            enabledApps != null -> APP_SETTINGS_VERSION
            rules.all { it.packageName != null } -> APP_ONLY_VERSION
            else -> SCOPED_VERSION
        }
        val items = JSONArray()
        for (rule in rules) {
            items.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("revision", rule.revision)
                    .also {
                        if (version == APP_ONLY_VERSION) {
                            it.put("packageName", requireNotNull(rule.packageName))
                        } else {
                            it.put("scope", rule.packageName.toScopeJson())
                        }
                    }
                    .put("action", rule.action.name)
                    .put("channelId", rule.channelId)
                    .put("title", rule.title?.toJson())
                    .put("body", rule.body?.toJson())
                    .put("sender", rule.sender?.toJson())
                    .put("frequency", rule.frequency?.toJson())
                    .put("enabled", rule.enabled),
            )
        }
        return JSONObject().put("version", version).put("rules", items)
            .also { document ->
                if (modes.isNotEmpty()) {
                    val entries = JSONArray()
                    for ((scope, action) in modes) {
                        validatePackageName(scope.packageName)
                        require(scope.channelId?.let { it.isNotBlank() && it.length <= MAX_SOURCE_CHARS } != false)
                        entries.put(JSONObject().put("packageName", scope.packageName)
                            .put("channelId", scope.channelId).put("action", action.name))
                    }
                    document.put("unmatchedModes", entries)
                }
                if (enabledApps != null) {
                    enabledApps.forEach(::validatePackageName)
                    document.put("enabledApps", JSONArray(enabledApps.sorted()))
                }
            }
            .toString()
            .also { require(it.length <= MAX_JSON_CHARS) { "Rule set is too large" } }
    }

    /** The storage default is an empty array until the first rule-set write. */
    fun decode(json: String): List<FilterRule> = decodeStored(json).rules

    internal fun decodeStored(json: String): RuleDocument =
        if (json == "[]") RuleDocument(emptyList()) else decodeDocument(json)

    fun decodeImport(json: String): List<FilterRule> = decodeDocument(json).rules

    internal fun decodeDocument(json: String): RuleDocument {
        require(json.length <= MAX_JSON_CHARS) { "Rule set is too large" }
        val document = parse(json)
        val version = document.required<Long>("version")
        require(version in APP_ONLY_VERSION..MODES_VERSION) { "Unsupported rule schema version" }
        val appList = when (version) {
            APP_SETTINGS_VERSION -> {
                document.checkFields("version", "rules", "enabledApps")
                document.required<JSONArray>("enabledApps")
            }
            MODES_VERSION -> {
                document.checkFields("version", "rules", "enabledApps", "unmatchedModes")
                document.optional<JSONArray>("enabledApps")
            }
            else -> {
                document.checkFields("version", "rules")
                null
            }
        }
        val enabledApps = appList?.let { apps ->
            buildSet<String> {
                for (index in 0 until apps.length()) {
                    val name = apps.opt(index) as? String ?: throw IllegalArgumentException("Expected a package name")
                    validatePackageName(name)
                    require(add(name)) { "Duplicate package name" }
                }
            }
        }
        val modes = if (version == MODES_VERSION) {
            val entries = document.required<JSONArray>("unmatchedModes")
            buildMap<ModeScope, Action> {
                for (index in 0 until entries.length()) {
                    val item = entries.opt(index) as? JSONObject ?: throw IllegalArgumentException("Expected a mode")
                    item.checkFields("packageName", "channelId", "action")
                    val name = item.required<String>("packageName")
                    validatePackageName(name)
                    val channel = item.optional<String>("channelId")
                    require(channel?.let { it.isNotBlank() && it.length <= MAX_SOURCE_CHARS } != false)
                    require(put(ModeScope(name, channel), enumValueOf<Action>(item.required("action"))) == null)
                }
            }
        } else emptyMap()
        val items = document.required<JSONArray>("rules")
        val rules = List(items.length()) { index ->
            val rule = items.opt(index) as? JSONObject ?: throw IllegalArgumentException("Expected a rule object")
            rule.toRule(version)
        }
        validate(rules)
        return RuleDocument(rules, enabledApps, modes)
    }

    private fun validatePackageName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_SOURCE_CHARS) { "Invalid package name" }
    }

    private fun JSONObject.toRule(version: Long): FilterRule {
        val appOnly = version == APP_ONLY_VERSION
        val scopeField = if (appOnly) "packageName" else "scope"
        checkFields("id", "revision", scopeField, "action", "channelId", "title", "body", "sender", "frequency", "enabled")
        return FilterRule(
            id = required("id"),
            revision = required("revision"),
            packageName = if (appOnly) required<String>("packageName") else required<JSONObject>("scope").scopePackage(),
            action = enumValueOf<Action>(required("action")),
            channelId = optional("channelId"),
            title = optional<JSONObject>("title")?.toTextCondition(),
            body = optional<JSONObject>("body")?.toTextCondition(),
            sender = optional<JSONObject>("sender")?.toTextCondition(),
            frequency = optional<JSONObject>("frequency")?.toFrequencyCondition(),
            enabled = optional<Boolean>("enabled") ?: true,
        )
    }

    private fun String?.toScopeJson(): JSONObject = if (this == null) {
        JSONObject().put("type", "allSelectedApps")
    } else {
        JSONObject().put("type", "app").put("packageName", this)
    }

    private fun JSONObject.scopePackage(): String? = when (required<String>("type")) {
        "app" -> {
            checkFields("type", "packageName")
            required<String>("packageName")
        }
        "allSelectedApps" -> {
            checkFields("type")
            null
        }
        else -> throw IllegalArgumentException("Unknown rule scope")
    }

    private fun TextCondition.toJson() = JSONObject()
        .put("value", value)
        .put("operation", operation.name)
        .put("ignoreCase", ignoreCase)

    private fun FrequencyCondition.toJson() = JSONObject()
        .put("allowedCount", allowedCount)
        .put("windowMillis", windowMillis)
        .put("groupBy", groupBy.name)

    private fun JSONObject.toTextCondition(): TextCondition {
        checkFields("value", "operation", "ignoreCase")
        return TextCondition(
            value = required("value"),
            operation = optional<String>("operation")?.let { enumValueOf<TextOperation>(it) } ?: TextOperation.CONTAINS,
            ignoreCase = optional<Boolean>("ignoreCase") ?: true,
        )
    }

    private fun JSONObject.toFrequencyCondition(): FrequencyCondition {
        checkFields("allowedCount", "windowMillis", "groupBy")
        val allowedCount = required<Long>("allowedCount")
        require(allowedCount in 1L until Int.MAX_VALUE.toLong()) { "Invalid frequency count" }
        return FrequencyCondition(
            allowedCount = allowedCount.toInt(),
            windowMillis = required("windowMillis"),
            groupBy = optional<String>("groupBy")?.let { enumValueOf<FrequencyGroup>(it) } ?: FrequencyGroup.APP,
        )
    }

    private fun validate(rules: List<FilterRule>) {
        require(rules.size <= MAX_RULES) { "Too many rules" }
        val ids = HashSet<String>()
        for (rule in rules) {
            require(rule.id.length <= MAX_ID_CHARS) { "Rule ID is too long" }
            require(ids.add(rule.id)) { "Duplicate rule ID" }
            require((rule.packageName?.length ?: 0) <= MAX_SOURCE_CHARS) { "Package name is too long" }
            require((rule.channelId?.length ?: 0) <= MAX_SOURCE_CHARS) { "Channel ID is too long" }
            for (condition in listOfNotNull(rule.title, rule.body, rule.sender)) {
                require(condition.value.length <= MAX_TEXT_CHARS) { "Rule condition is too long" }
            }
        }
    }

    private inline fun <reified T> JSONObject.required(field: String): T =
        opt(field) as? T ?: throw IllegalArgumentException("Missing or invalid field: $field")

    private inline fun <reified T> JSONObject.optional(field: String): T? =
        if (has(field)) required<T>(field) else null

    private fun JSONObject.checkFields(vararg accepted: String) {
        require(keys().asSequence().all { it in accepted }) { "Unknown rule field" }
    }

    private fun parse(json: String): JSONObject {
        try {
            return JsonReader(StringReader(json)).use { reader ->
                val document = reader.readValue(0) as? JSONObject
                    ?: throw IllegalArgumentException("Expected a rule document")
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after the rule document" }
                document
            }
        } catch (exception: IOException) {
            throw IllegalArgumentException("Invalid rule JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IllegalArgumentException("Invalid rule JSON", exception)
        } catch (exception: JSONException) {
            throw IllegalArgumentException("Invalid rule JSON", exception)
        }
    }

    private fun JsonReader.readValue(depth: Int): Any {
        require(depth <= MAX_DEPTH) { "Rule document is too deeply nested" }
        return when (peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(depth)
            JsonToken.BEGIN_ARRAY -> readArray(depth)
            JsonToken.STRING -> nextString()
            JsonToken.NUMBER -> nextString().toLongOrNull() ?: throw IllegalArgumentException("Expected an integer")
            JsonToken.BOOLEAN -> nextBoolean()
            else -> throw IllegalArgumentException("Unexpected value in rule document")
        }
    }

    private fun JsonReader.readObject(depth: Int): JSONObject {
        val result = JSONObject()
        beginObject()
        while (hasNext()) {
            require(result.length() < MAX_OBJECT_FIELDS) { "Too many rule fields" }
            val field = nextName()
            require(!result.has(field)) { "Duplicate JSON field" }
            result.put(field, readValue(depth + 1))
        }
        endObject()
        return result
    }

    private fun JsonReader.readArray(depth: Int): JSONArray {
        val result = JSONArray()
        beginArray()
        while (hasNext()) {
            require(result.length() < MAX_RULES) { "Too many rules" }
            result.put(readValue(depth + 1))
        }
        endArray()
        return result
    }
}
