package com.pushmenot.core

data class FrequencyKey(
    val rule: RuleVersion,
    val profileId: String,
    val packageName: String,
    val group: String?,
)

data class FrequencyState(
    val windows: Map<FrequencyKey, List<Long>> = emptyMap(),
    val lastElapsedMillis: Long? = null,
)

enum class DecisionReason { RULE_MATCH, NO_MATCH, PROTECTED, CONTENT_UNAVAILABLE }

data class Decision(
    val action: Action,
    val reason: DecisionReason,
    val rule: RuleVersion? = null,
    val protection: Protection? = null,
    val ruleScope: RuleScope? = null,
)

data class Evaluation(val decision: Decision, val frequencyState: FrequencyState)

sealed interface ReplayInput {
    data class Card(val event: NotificationEvent) : ReplayInput
    /** A disconnect, reboot, or gap that invalidates the earlier frequency window. */
    data object Reset : ReplayInput
}

data class ReplayDecision(val event: NotificationEvent, val decision: Decision, val complete: Boolean)

object RuleEngine {
    /**
     * Evaluate an event without mutating the input state. The caller persists the returned
     * state with the decision and passes countAsNew=false for updates in the same generation.
     */
    fun evaluate(
        event: NotificationEvent,
        rules: List<FilterRule>,
        state: FrequencyState = FrequencyState(),
    ): Evaluation {
        val activeRules = rules.filter(FilterRule::enabled)
        val limits = activeRules.mapNotNull { rule -> rule.frequency?.let { rule.version to it } }.toMap()
        val windows = prune(state, limits, event.elapsedMillis)
        fun result(decision: Decision) = Evaluation(decision, FrequencyState(windows, event.elapsedMillis))

        if (event.protection != null) {
            return result(Decision(Action.KEEP, DecisionReason.PROTECTED, protection = event.protection))
        }

        if (!event.contentAvailable) {
            return result(Decision(Action.KEEP, DecisionReason.CONTENT_UNAVAILABLE))
        }

        var keep: FilterRule? = null
        var hide: FilterRule? = null

        for (rule in activeRules) {
            val frequency = rule.frequency
            val key = frequency?.let { frequencyKey(rule, event, it) }
            if (frequency != null && key == null) continue
            if (match(rule, event) != Match.YES) continue
            when (rule.action) {
                Action.KEEP -> if (rule.hasPriorityOver(keep)) keep = rule
                Action.HIDE -> {
                    val matchesFrequency = frequency == null ||
                        key != null && exceedsLimit(event, frequency, key, windows)
                    if (matchesFrequency && rule.hasPriorityOver(hide)) hide = rule
                }
            }
        }

        val matchedRule = keep ?: hide
        return result(matchedRule?.matchDecision() ?: Decision(Action.KEEP, DecisionReason.NO_MATCH))
    }

    /**
     * Re-evaluate retained history with the same engine. An unknown prefix is incomplete
     * until its events can no longer affect any applicable frequency window.
     */
    fun replay(
        inputs: Iterable<ReplayInput>,
        rules: List<FilterRule>,
        startsAtKnownReset: Boolean = false,
    ): List<ReplayDecision> {
        val decisions = mutableListOf<ReplayDecision>()
        var state = FrequencyState()
        var unknownPrefix = !startsAtKnownReset
        var firstElapsedMillis: Long? = null
        val unknownObservations = mutableMapOf<FrequencyKey, Long>()

        for (input in inputs) {
            if (input is ReplayInput.Reset) {
                state = FrequencyState()
                unknownPrefix = false
                firstElapsedMillis = null
                unknownObservations.clear()
                continue
            }
            val event = (input as ReplayInput.Card).event
            if (state.lastElapsedMillis?.let { event.elapsedMillis < it } == true) {
                unknownPrefix = true
                firstElapsedMillis = null
                unknownObservations.clear()
            }
            val first = firstElapsedMillis ?: event.elapsedMillis.also { firstElapsedMillis = it }
            val evaluation = evaluate(event, rules, state)
            state = evaluation.frequencyState
            var complete = true
            for (rule in rules) {
                if (!rule.enabled || !rule.appliesTo(event.packageName)) continue
                val match = match(rule, event)
                val frequency = rule.frequency
                val contentUnknown = match != Match.NO && !event.contentAvailable
                // Missing input in one app or Android profile cannot affect another's window.
                val observationKey = FrequencyKey(rule.version, event.profileId, event.packageName, group = null)
                if (event.protection == null && contentUnknown) {
                    complete = false
                    if (event.countAsNew && frequency != null) {
                        unknownObservations[observationKey] = event.elapsedMillis
                    }
                }
                if (event.protection != null || match == Match.NO || frequency == null) continue
                val cutoff = event.elapsedMillis - frequency.windowMillis
                val missingStart = unknownPrefix && cutoff < first
                val missingObservation = unknownObservations[observationKey]?.let { it > cutoff } == true
                if (missingStart || missingObservation) complete = false
            }
            decisions.add(ReplayDecision(event, evaluation.decision, complete))
        }
        return decisions
    }

    private fun prune(
        state: FrequencyState,
        limits: Map<RuleVersion, FrequencyCondition>,
        now: Long,
    ): MutableMap<FrequencyKey, List<Long>> {
        if (state.lastElapsedMillis?.let { now < it } == true) return mutableMapOf()

        return state.windows.mapNotNull { (key, timestamps) ->
            val limit = limits[key.rule] ?: return@mapNotNull null
            // Keep only enough observations to distinguish N from N+1.
            val retained = timestamps.filter { it > now - limit.windowMillis && it <= now }
                .takeLast(limit.allowedCount + 1)
            if (retained.isEmpty()) null else key to retained
        }.toMap(mutableMapOf())
    }

    private fun frequencyKey(rule: FilterRule, event: NotificationEvent, frequency: FrequencyCondition): FrequencyKey? {
        val group = when (frequency.groupBy) {
            FrequencyGroup.APP -> null
            FrequencyGroup.CHANNEL -> event.channelId ?: return null
            FrequencyGroup.SENDER -> event.senderKey ?: return null
        }
        return FrequencyKey(rule.version, event.profileId, event.packageName, group)
    }

    private fun exceedsLimit(
        event: NotificationEvent,
        frequency: FrequencyCondition,
        key: FrequencyKey,
        windows: MutableMap<FrequencyKey, List<Long>>,
    ): Boolean {
        val previous = windows[key].orEmpty()
        if (!event.countAsNew) return previous.size > frequency.allowedCount

        val updated = (previous + event.elapsedMillis).takeLast(frequency.allowedCount + 1)
        windows[key] = updated
        return updated.size > frequency.allowedCount
    }

    private enum class Match { YES, NO, UNKNOWN }

    private fun FilterRule.hasPriorityOver(current: FilterRule?): Boolean =
        current == null || scope == RuleScope.APP && current.scope == RuleScope.ALL_SELECTED_APPS

    private fun FilterRule.matchDecision(): Decision =
        Decision(action, DecisionReason.RULE_MATCH, version, ruleScope = scope)

    private fun match(rule: FilterRule, event: NotificationEvent): Match {
        if (!rule.appliesTo(event.packageName) || rule.channelId != null && rule.channelId != event.channelId) {
            return Match.NO
        }
        val conditions = listOf(rule.title to event.title, rule.body to event.body, rule.sender to event.sender)
            .filter { it.first != null }
        if (conditions.isEmpty()) return Match.YES
        if (!event.contentAvailable) return Match.UNKNOWN
        return if (conditions.all { (condition, value) -> value != null && condition?.matches(value) == true }) {
            Match.YES
        } else {
            Match.NO
        }
    }
}
