package io.github.vitalyostanin.pushmenot.rules

import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.FrequencyCondition
import com.pushmenot.core.FrequencyGroup
import com.pushmenot.core.TextCondition
import com.pushmenot.core.TextOperation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleJsonTest {
    @Test
    fun roundTripsRulesInOrder() {
        val rules = listOf(
            defaultRule.copy(
                channelId = "orders",
                title = TextCondition("скидка"),
                body = TextCondition("Example\nmessage", TextOperation.EQUALS, ignoreCase = false),
                sender = TextCondition("store"),
                frequency = FrequencyCondition(2, 5_000, FrequencyGroup.SENDER),
                enabled = false,
            ),
            defaultRule.copy(id = "exception", action = Action.KEEP, channelId = "orders"),
        )

        assertEquals(rules, RuleJson.decodeImport(RuleJson.encode(rules)))
    }

    @Test
    fun emptyStorageDefaultIsAcceptedAndImportRequiresASchema() {
        assertEquals(emptyList<FilterRule>(), RuleJson.decode("[]"))
        assertEquals(emptyList<FilterRule>(), RuleJson.decodeImport(RuleJson.encode(emptyList())))
        assertRejected("[]")
    }

    @Test
    fun keepsAppOnlyFilesCompatibleAndEncodesGlobalScopeInVersionTwo() {
        val legacy = """{"version":1,"rules":[{"id":"promos","revision":1,"packageName":"example.shop","action":"HIDE"}]}"""
        assertEquals(listOf(defaultRule), RuleJson.decodeImport(legacy))
        val legacyExport = JSONObject(RuleJson.encode(listOf(defaultRule)))
        assertEquals(1, legacyExport.getInt("version"))
        assertEquals("example.shop", legacyExport.getJSONArray("rules").getJSONObject(0).getString("packageName"))

        val ordered = listOf(defaultRule.copy(id = "global", packageName = null), defaultRule)
        val scoped = RuleJson.encode(ordered)
        val document = JSONObject(scoped)
        assertEquals(2, document.getInt("version"))
        val entries = document.getJSONArray("rules")
        assertEquals("allSelectedApps", entries.getJSONObject(0).getJSONObject("scope").getString("type"))
        assertEquals("app", entries.getJSONObject(1).getJSONObject("scope").getString("type"))
        assertEquals(ordered, RuleJson.decodeImport(scoped))
    }

    @Test
    fun requiresAnExplicitUnambiguousScopeInVersionTwo() {
        val prefix = """{"version":2,"rules":[{"id":"r","revision":1,"action":"HIDE", """
        val suffix = "}]} "
        assertRejected(prefix + """"scope":{"type":"allSelectedApps","packageName":"example.shop"}""" + suffix)
        assertRejected(prefix + """"scope":{"type":"app"}""" + suffix)
        assertRejected(prefix + """"scope":{"type":"app","packageName":""}""" + suffix)
        assertRejected(prefix + """"scope":{"type":"unknown"}""" + suffix)
        assertRejected(prefix + """"packageName":"example.shop"""" + suffix)
        assertRejected(prefix + """"packageName":"example.shop","scope":{"type":"allSelectedApps"}""" + suffix)
    }

    @Test
    fun rejectsInvalidSchemaRulesAndMalformedJson() {
        val encoded = RuleJson.encode(listOf(defaultRule))
        val entry = encoded.substringAfter('[').substringBeforeLast(']')

        assertRejected(encoded.replace("\"version\":1", "\"version\":3"))
        assertRejected(encoded.replace("\"version\":1", "\"version\":\"1\""))
        assertRejected(encoded.replace("\"HIDE\"", "\"REMOVE\""))
        assertRejected(encoded.replace("\"enabled\":true", "\"extra\":true"))
        assertRejected(encoded.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"))
        assertRejected("""{"version":1,"rules":[$entry,$entry]}""")
        assertRejected("{version:1,rules:[]}")
        assertRejected("""{"version":1,"rules":[]} {}""")
        assertRejected(" ".repeat(RuleJson.MAX_JSON_CHARS + 1))
    }

    private fun assertRejected(json: String) {
        assertTrue(runCatching { RuleJson.decodeImport(json) }.exceptionOrNull() is IllegalArgumentException)
    }

    private val defaultRule = FilterRule("promos", 1, "example.shop", Action.HIDE)
}
