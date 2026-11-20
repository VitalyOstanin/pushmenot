package io.github.vitalyostanin.pushmenot.listener

import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.Protection
import com.pushmenot.core.TextCondition
import com.pushmenot.core.TextOperation
import java.util.UUID

@ConsistentCopyVisibility
data class ShadeCard internal constructor(
    val packageName: String,
    val channelId: String?,
    val title: String?,
    val body: String?,
    val protection: Protection?,
) {
    private val exactTitle = title?.takeIf(::canUseAsCondition)
    private val exactBody = body?.takeIf(::canUseAsCondition)
    val canSuggestRule: Boolean get() = protection == null && (exactTitle != null || exactBody != null)
    val suggestsTitle: Boolean get() = exactTitle != null

    internal fun draftRule(): FilterRule {
        check(canSuggestRule)
        return FilterRule(
            UUID.randomUUID().toString(), 1L, packageName, Action.HIDE, channelId,
            title = exactTitle?.let { TextCondition(it, TextOperation.EQUALS) },
            body = if (exactTitle == null) exactBody?.let { TextCondition(it, TextOperation.EQUALS) } else null,
        )
    }

    private fun canUseAsCondition(text: String): Boolean = text.isNotBlank() && text.length <= 1024
}
