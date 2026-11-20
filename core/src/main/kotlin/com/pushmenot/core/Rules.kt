package com.pushmenot.core

enum class Action { KEEP, HIDE }

enum class RuleScope { APP, ALL_SELECTED_APPS }

enum class TextOperation { EQUALS, CONTAINS }

data class TextCondition(
    val value: String,
    val operation: TextOperation = TextOperation.CONTAINS,
    val ignoreCase: Boolean = true,
) {
    init {
        require(value.isNotBlank())
    }

    internal fun matches(text: String): Boolean = when (operation) {
        TextOperation.EQUALS -> text.equals(value, ignoreCase = ignoreCase)
        TextOperation.CONTAINS -> text.contains(value, ignoreCase = ignoreCase)
    }
}

enum class FrequencyGroup { APP, CHANNEL, SENDER }

data class FrequencyCondition(
    val allowedCount: Int,
    val windowMillis: Long,
    val groupBy: FrequencyGroup = FrequencyGroup.APP,
) {
    init {
        require(allowedCount in 1 until Int.MAX_VALUE)
        require(windowMillis > 0)
    }
}

data class RuleVersion(val id: String, val revision: Long) {
    init {
        require(id.isNotBlank())
        require(revision > 0)
    }
}

data class FilterRule(
    val id: String,
    val revision: Long,
    val packageName: String?,
    val action: Action,
    val channelId: String? = null,
    val title: TextCondition? = null,
    val body: TextCondition? = null,
    val sender: TextCondition? = null,
    val frequency: FrequencyCondition? = null,
    val enabled: Boolean = true,
) {
    val version = RuleVersion(id, revision)
    val scope: RuleScope = if (packageName == null) RuleScope.ALL_SELECTED_APPS else RuleScope.APP

    /** The caller must pass only notifications from sources selected for filtering. */
    fun appliesTo(packageName: String): Boolean = this.packageName == null || this.packageName == packageName

    init {
        require(packageName == null || packageName.isNotBlank())
        require(channelId == null || channelId.isNotBlank())
        require(frequency == null || action == Action.HIDE)
    }
}

enum class Protection {
    GROUP_SUMMARY,
    ONGOING,
    NOT_CLEARABLE,
    EMERGENCY,
    AMBIGUOUS_MESSAGES,
}

data class NotificationEvent(
    val profileId: String,
    val packageName: String,
    val elapsedMillis: Long,
    val channelId: String? = null,
    val title: String? = null,
    val body: String? = null,
    val sender: String? = null,
    /** A stable, opaque key for the sender, scoped to this installation. */
    val senderKey: String? = null,
    /** True for the first observation of a notification generation. */
    val countAsNew: Boolean = true,
    val protection: Protection? = null,
    /** False for redacted or unreadable content and metadata-only history. */
    val contentAvailable: Boolean = true,
) {
    init {
        require(elapsedMillis >= 0)
    }
}
