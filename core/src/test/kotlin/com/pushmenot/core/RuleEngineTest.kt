package com.pushmenot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    @Test
    fun unmatchedHidePreservesExplicitKeepAndUnreadableNotifications() {
        val event = card().copy(title = "Order ready")
        val keep = FilterRule("pickup", 1, SHOP, Action.KEEP, title = TextCondition("Order ready"))
        assertEquals(DecisionReason.UNMATCHED_MODE,
            RuleEngine.evaluate(event, emptyList(), unmatched = Action.HIDE).decision.reason)
        assertEquals(Action.KEEP,
            RuleEngine.evaluate(event, listOf(keep), unmatched = Action.HIDE).decision.action)
        assertEquals(Action.KEEP,
            RuleEngine.evaluate(event.copy(contentAvailable = false), emptyList(), unmatched = Action.HIDE).decision.action)
    }

    @Test
    fun appliesOnlyToMatchingScopeAndText() {
        val rule = FilterRule(
            "promotion",
            1,
            SHOP,
            Action.HIDE,
            channelId = "orders",
            title = TextCondition("discount"),
            body = TextCondition("valid today", TextOperation.EQUALS, ignoreCase = false),
        )
        val matching = card().copy(channelId = "orders", title = "Discount on delivery", body = "valid today")

        assertEquals(Action.HIDE, RuleEngine.evaluate(matching, listOf(rule)).decision.action)
        for (nonMatch in listOf(
            matching.copy(title = "Order handed to courier"),
            matching.copy(body = "Valid today"),
            matching.copy(body = null),
            matching.copy(channelId = "other"),
            matching.copy(packageName = "example.other"),
            matching.copy(channelId = null),
        )) {
            assertEquals(Action.KEEP, RuleEngine.evaluate(nonMatch, listOf(rule)).decision.action)
        }
    }

    @Test
    fun keepExceptionWinsAndOrderBreaksTies() {
        val early = hide("early")
        val late = hide("late")
        val keep = hide("exception").copy(action = Action.KEEP, title = TextCondition("courier"))
        val arrival = card().copy(title = "Courier update")

        val kept = RuleEngine.evaluate(arrival, listOf(early, late, keep)).decision
        assertEquals(Action.KEEP, kept.action)
        assertEquals(keep.version, kept.rule)
        assertEquals(early.version, RuleEngine.evaluate(arrival, listOf(early, late)).decision.rule)
        assertEquals(late.version, RuleEngine.evaluate(arrival, listOf(early.copy(enabled = false), late)).decision.rule)
    }

    @Test
    fun appliesFourPrecedenceLevelsBeforeUsingSavedOrder() {
        val globalHide = hide("global-hide").copy(packageName = null)
        val appHide = hide("app-hide")
        val globalKeep = globalHide.copy(id = "global-keep", action = Action.KEEP)
        val appKeep = appHide.copy(id = "app-keep", action = Action.KEEP)
        val ordered = listOf(globalHide, appHide, globalKeep, appKeep, appKeep.copy(id = "later-app-keep"))

        val expected = listOf(appKeep, globalKeep, appHide, globalHide)
        val scenarios = listOf(ordered, ordered.take(3), ordered.take(2), ordered.take(1))
        for ((rules, winner) in scenarios.zip(expected)) {
            val decision = RuleEngine.evaluate(card(), rules).decision
            assertEquals(winner.action, decision.action)
            assertEquals(winner.version, decision.rule)
            assertEquals(winner.scope, decision.ruleScope)
        }
        assertEquals(globalKeep.version, RuleEngine.evaluate(card().copy(packageName = "other"), ordered).decision.rule)

        val missingKeep = globalKeep.copy(title = TextCondition("payment"))
        val decision = RuleEngine.evaluate(card().copy(contentAvailable = false), listOf(appHide, missingKeep)).decision
        assertEquals(Action.KEEP, decision.action)
        assertEquals(DecisionReason.CONTENT_UNAVAILABLE, decision.reason)
        assertNull(decision.ruleScope)
    }

    @Test
    fun assessmentUsesCurrentRulesWithoutInventingFrequencyHistory() {
        val event = card().copy(title = "Promotion")
        val hide = hide("promotion").copy(title = TextCondition("Promotion"))
        val keep = hide.copy(id = "exception", packageName = null, action = Action.KEEP)
        val frequency = hide.copy(id = "limit", frequency = FrequencyCondition(1, 60_000, FrequencyGroup.APP))

        assertEquals(DecisionReason.NO_MATCH, RuleEngine.assessWithoutFrequencyHistory(event, emptyList())?.reason)
        assertEquals(hide.version, RuleEngine.assessWithoutFrequencyHistory(event, listOf(hide))?.rule)
        assertEquals(keep.version, RuleEngine.assessWithoutFrequencyHistory(event, listOf(frequency, hide, keep))?.rule)
        assertNull(RuleEngine.assessWithoutFrequencyHistory(event, listOf(frequency, hide)))
        assertEquals(hide.version, RuleEngine.assessWithoutFrequencyHistory(event, listOf(hide, frequency))?.rule)
        assertEquals(DecisionReason.NO_MATCH, RuleEngine.assessWithoutFrequencyHistory(
            event.copy(title = "Order ready"), listOf(frequency),
        )?.reason)
    }

    @Test
    fun protectedAndUnreadableCardsAreKept() {
        val event = card()
        val rule = hide()
        for (protection in Protection.entries) {
            val decision = RuleEngine.evaluate(event.copy(protection = protection), listOf(rule)).decision
            assertEquals(Action.KEEP, decision.action)
            assertEquals(DecisionReason.PROTECTED, decision.reason)
            assertEquals(protection, decision.protection)
        }

        val unknown = event.copy(contentAvailable = false)
        val conditional = rule.copy(body = TextCondition("discount"))
        assertEquals(DecisionReason.CONTENT_UNAVAILABLE, RuleEngine.evaluate(unknown, listOf(conditional)).decision.reason)

        val keep = conditional.copy(id = "keep", action = Action.KEEP)
        val result = RuleEngine.evaluate(unknown, listOf(rule, keep)).decision
        assertEquals(Action.KEEP, result.action)
        assertNull(result.rule)
    }

    @Test
    fun countsNewGenerationsAndUsesOpenLeftBoundary() {
        val rule = hide().copy(frequency = FrequencyCondition(1, 100))
        val first = RuleEngine.evaluate(card(1), listOf(rule))
        val update = RuleEngine.evaluate(card(50).copy(countAsNew = false), listOf(rule), first.frequencyState)
        val second = RuleEngine.evaluate(card(100), listOf(rule), update.frequencyState)
        val boundary = RuleEngine.evaluate(card(200), listOf(rule), second.frequencyState)

        assertEquals(Action.KEEP, first.decision.action)
        assertEquals(Action.KEEP, update.decision.action)
        assertEquals(Action.HIDE, second.decision.action)
        assertEquals(Action.KEEP, boundary.decision.action)
        assertEquals(listOf(1L), update.frequencyState.windows.values.single())
        assertEquals(listOf(1L, 100L), second.frequencyState.windows.values.single())
    }

    @Test
    fun groupsFrequencyByProfileAndOpaqueSenderAndStartsRevisionsFresh() {
        val rule = hide().copy(
            sender = TextCondition("store"),
            frequency = FrequencyCondition(1, 100, FrequencyGroup.SENDER),
        )
        val event = card(1).copy(sender = "Store", senderKey = "opaque")
        val first = RuleEngine.evaluate(event, listOf(rule))
        val otherProfile = RuleEngine.evaluate(event.copy(profileId = "other", elapsedMillis = 2), listOf(rule), first.frequencyState)
        val missingKey = RuleEngine.evaluate(event.copy(senderKey = null, elapsedMillis = 3), listOf(rule), otherProfile.frequencyState)
        val second = RuleEngine.evaluate(event.copy(elapsedMillis = 4), listOf(rule), missingKey.frequencyState)
        val newVersion = RuleEngine.evaluate(event.copy(elapsedMillis = 5), listOf(rule.copy(revision = 2)), second.frequencyState)

        assertEquals(Action.KEEP, otherProfile.decision.action)
        assertEquals(Action.KEEP, missingKey.decision.action)
        assertEquals(Action.HIDE, second.decision.action)
        assertEquals(Action.KEEP, newVersion.decision.action)
        assertEquals(listOf(rule.copy(revision = 2).version), newVersion.frequencyState.windows.keys.map { it.rule })
        assertTrue(second.frequencyState.windows.keys.all { it.group == "opaque" })
    }

    @Test
    fun globalFrequencyAndReplayCoverageAreIndependentForEachApp() {
        val rule = hide().copy(
            packageName = null,
            title = TextCondition("deal"),
            frequency = FrequencyCondition(1, 100),
        )
        val firstApp = card(1).copy(title = "Deal")
        val secondApp = firstApp.copy(packageName = "example.other", elapsedMillis = 2)
        val first = RuleEngine.evaluate(firstApp, listOf(rule))
        val second = RuleEngine.evaluate(secondApp, listOf(rule), first.frequencyState)
        val nextFirst = RuleEngine.evaluate(firstApp.copy(elapsedMillis = 3), listOf(rule), second.frequencyState)

        assertEquals(Action.KEEP, second.decision.action)
        assertEquals(Action.HIDE, nextFirst.decision.action)
        assertEquals(RuleScope.ALL_SELECTED_APPS, nextFirst.decision.ruleScope)
        assertEquals(setOf(SHOP, "example.other"), nextFirst.frequencyState.windows.keys.map { it.packageName }.toSet())

        val replay = RuleEngine.replay(
            listOf(ReplayInput.Card(firstApp.copy(contentAvailable = false)), ReplayInput.Card(secondApp)),
            listOf(rule),
            startsAtKnownReset = true,
        )
        assertEquals(listOf(false, true), replay.map { it.complete })
    }

    @Test
    fun replaySharesLiveDecisionsAndMarksCoverage() {
        val rule = hide().copy(title = TextCondition("deal"), frequency = FrequencyCondition(1, 100))
        fun deal(time: Long, contentAvailable: Boolean = true) =
            card(time).copy(title = "Deal", contentAvailable = contentAvailable)

        val result = RuleEngine.replay(
            listOf(
                ReplayInput.Card(deal(50)),
                ReplayInput.Card(deal(149)),
                ReplayInput.Card(deal(150)),
                ReplayInput.Reset,
                ReplayInput.Card(deal(1)),
                ReplayInput.Card(deal(2, contentAvailable = false)),
                ReplayInput.Card(deal(3)),
                ReplayInput.Card(deal(102)),
            ),
            listOf(rule),
        )

        assertEquals(listOf(false, false, true, true, false, false, true), result.map { it.complete })
        assertEquals(
            listOf(Action.KEEP, Action.HIDE, Action.HIDE, Action.KEEP, Action.KEEP, Action.HIDE, Action.HIDE),
            result.map { it.decision.action },
        )
        assertFalse(result[4].complete)
    }

    private fun card(time: Long = 1) = NotificationEvent(profileId = "owner", packageName = SHOP, elapsedMillis = time)

    private fun hide(id: String = "hide") = FilterRule(id, 1, SHOP, Action.HIDE)

    private companion object {
        const val SHOP = "example.shop"
    }
}
