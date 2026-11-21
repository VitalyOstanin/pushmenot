package io.github.vitalyostanin.pushmenot.listener

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateMapOf

/** Original notification actions are valid only as long as the system keeps their tokens alive. */
@MainThread
internal object JournalDestinations {
    private const val CAPACITY = 64
    private val actions = mutableStateMapOf<Long, PendingIntent>()
    private val order = ArrayDeque<Long>()

    fun remember(id: Long, action: PendingIntent) {
        if (order.size == CAPACITY) actions.remove(order.removeFirst())
        order.addLast(id)
        actions[id] = action
    }

    fun available(id: Long): Boolean = id in actions

    fun open(context: Context, id: Long) {
        val action = actions[id] ?: return
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            ).toBundle()
        } else {
            null
        }
        try {
            action.send(context, 0, null, null, null, null, options)
        } catch (_: PendingIntent.CanceledException) {
            order.remove(id)
            actions.remove(id)
        } catch (_: SecurityException) {
            order.remove(id)
            actions.remove(id)
        }
    }

    fun clear() {
        order.clear()
        actions.clear()
    }
}
