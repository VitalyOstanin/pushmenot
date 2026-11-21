package io.github.vitalyostanin.pushmenot.listener

import android.app.PendingIntent
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pushmenot.core.Action
import com.pushmenot.core.NotificationEvent
import com.pushmenot.core.RuleEngine
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.rules.unmatchedAction
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcut
import io.github.vitalyostanin.pushmenot.storage.FrequencyJson
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.JournalSettings
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import io.github.vitalyostanin.pushmenot.storage.writeTransaction
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PushMeNotListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val queue = ArrayDeque<Signal>()
    private val active = mutableMapOf<String, CardSlot>()
    private val database by lazy { PushDatabase.open(this) }
    private val storage get() = database.storage
    private val reader by lazy { NotificationReader(resources) }
    private var sources: Map<String, SourceSettings>? = null
    private var retention: JournalSettings? = null
    private var epoch = 0L
    private var generation = 0L
    private var baselineComplete = true
    private var connected = false
    private var openGapId: Long? = null
    private var reset: Reset? = null
    private var pendingFailure: Reset? = null
    private var observedRulesVersion: Long? = null
    private var pendingReview: Long? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            storage.observeSources().collect { configured ->
                val before = sources
                sources = configured.associateBy(SourceSettings::packageName)
                activateWhenReady()
                if (before != null && configured.any { it.enabled && before[it.packageName]?.enabled != true }) {
                    requestActiveReview()
                }
            }
        }
        scope.launch {
            storage.observeJournalSettings().collect { settings ->
                retention = settings
                if (!settings.retainContent) JournalDestinations.clear()
                activateWhenReady()
            }
        }
        scope.launch {
            storage.observeRules().collect { rules ->
                if (observedRulesVersion != rules.version) {
                    observedRulesVersion = rules.version
                    requestActiveReview()
                }
            }
        }
        scope.launch {
            for (unused in wake) {
                drain()
            }
        }
    }

    private fun activateWhenReady() {
        if (connected && sources != null && retention != null && !connection.value) {
            resetConnection("initial_settings")
            connection.value = true
            requestActiveReview()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        shadeProvider = WeakReference(this)
        shadeChanges.update { it + 1 }
        connection.value = sources != null && retention != null
        resetConnection("listener_connected")
        requestActiveReview()
    }

    override fun onListenerDisconnected() {
        JournalDestinations.clear()
        connected = false
        clearShadeProvider()
        connection.value = false
        resetConnection("listener_disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (!connected || sbn == null || sbn.user != Process.myUserHandle()) return
        if (sbn.packageName == packageName && sbn.id == HistoryShortcut.ID &&
            sbn.notification.channelId == HistoryShortcut.CHANNEL
        ) return
        shadeChanges.update { it + 1 }
        val configured = sources ?: return
        val settings = retention ?: return
        val source = configured[sbn.packageName]
        if (queue.size >= MAX_QUEUE || sbn.key !in active && active.size >= MAX_ACTIVE) {
            resetConnection("ingestion_overflow")
            return
        }

        val snapshot = reader.read(
            sbn, readContent = source?.enabled == true || settings.retainContent, rankingMap = rankingMap,
        )
        val archive = if (settings.retainContent) readArchive(sbn.notification, snapshot, reader) else null
        val fingerprint = reader.fingerprint(snapshot)
        val previous = active[sbn.key]
        if (previous != null && previous.fingerprint == fingerprint && previous.postTime == sbn.postTime &&
            previous.archiveFingerprint == archive?.fingerprint) return

        val slot = CardSlot(
            lifecycleId = previous?.lifecycleId ?: UUID.randomUUID().toString(),
            generation = previous?.generation ?: ++generation,
            revision = (previous?.revision ?: 0) + 1,
            postTime = sbn.postTime,
            fingerprint = fingerprint,
            archiveFingerprint = archive?.fingerprint,
        )
        active[sbn.key] = slot
        enqueue(
            Posted(
                epoch = epoch,
                key = sbn.key,
                slot = slot,
                snapshot = snapshot,
                contentAction = sbn.activityAction(),
                sourceRevision = source?.consentRevision,
                retentionRevision = settings.retentionRevision.takeIf { settings.retainContent },
                archive = archive,
                countAsNew = previous == null && baselineComplete,
                elapsedMillis = SystemClock.elapsedRealtime(),
                observedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        if (!connected || sbn == null || sbn.user != Process.myUserHandle()) return
        shadeChanges.update { it + 1 }
        val slot = active[sbn.key]?.takeIf { it.postTime == sbn.postTime } ?: return
        active.remove(sbn.key)
        if (queue.size >= MAX_QUEUE) {
            resetConnection("ingestion_overflow")
            return
        }
        enqueue(Removed(epoch, slot, System.currentTimeMillis(), reason))
    }

    override fun onDestroy() {
        JournalDestinations.clear()
        if (connected) connection.value = false
        clearShadeProvider()
        connected = false
        wake.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun clearShadeProvider() {
        if (shadeProvider?.get() === this) {
            shadeProvider = null
            shadeChanges.update { it + 1 }
        }
    }

    private fun readShade(): List<ShadeCard>? {
        val cards = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val rankingMap = currentRanking
        return cards.asSequence()
            .filter { it.user == Process.myUserHandle() && it.packageName != packageName }
            .take(MAX_ACTIVE)
            .map { card ->
                val snapshot = reader.read(card, rankingMap = rankingMap)
                val channel = snapshot.channelId?.takeIf { it.isNotBlank() && it == it.trim() }
                ShadeCard(snapshot.packageName, channel, snapshot.title, snapshot.body, snapshot.protection, snapshot.channelName)
            }
            .toList()
    }

    private fun enqueue(signal: Signal) {
        queue.addLast(signal)
        wake.trySend(Unit)
    }

    private fun resetConnection(reason: String) {
        val now = System.currentTimeMillis()
        reset = Reset(
            beganAtMillis = minOf(reset?.beganAtMillis ?: now, pendingFailure?.beganAtMillis ?: now, now),
            endedAtMillis = now.takeIf { connected },
            reason = reason,
        )
        pendingFailure = null
        epoch++
        queue.clear()
        pendingReview = null
        active.clear()
        baselineComplete = true
        if (connected) {
            val visible = try {
                activeNotifications
            } catch (_: RuntimeException) {
                null
            }
            baselineComplete = visible != null
            for (card in visible.orEmpty()) {
                if (card.user != Process.myUserHandle()) continue
                if (card.packageName == packageName && card.id == HistoryShortcut.ID &&
                    card.notification.channelId == HistoryShortcut.CHANNEL
                ) continue
                if (active.size >= MAX_ACTIVE) {
                    baselineComplete = false
                    break
                }
                active[card.key] = CardSlot(
                    lifecycleId = UUID.randomUUID().toString(),
                    generation = ++generation,
                    revision = 0,
                    postTime = card.postTime,
                    fingerprint = null,
                )
            }
        }
        wake.trySend(Unit)
    }

    private suspend fun drain() {
        while (true) {
            val gap = reset ?: pendingFailure
            if (gap != null) {
                reset = null
                pendingFailure = null
                try {
                    persistGap(gap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    // Retry only after another callback wakes the worker.
                    pendingFailure = gap
                    queue.clear()
                    return
                }
                continue
            }

            val event = queue.removeFirstOrNull()
            if (event == null) {
                val version = pendingReview ?: return
                pendingReview = null
                try {
                    reviewActive(version)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    resetConnection("active_review_failure")
                }
                continue
            }
            if (event.epoch != epoch) continue
            try {
                when (event) {
                    is Posted -> handlePosted(event)
                    is Removed -> storage.recordRemoval(
                        event.slot.journalId,
                        event.atMillis,
                        event.reason,
                        event.slot.lifecycleId.takeIf { event.reason == REASON_LISTENER_CANCEL },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                resetConnection("processing_failure")
            }
        }
    }

    private fun requestActiveReview() {
        if (!connection.value) return
        pendingReview = observedRulesVersion?.takeIf { it > 0 }
        if (pendingReview != null) wake.trySend(Unit)
    }

    private suspend fun reviewActive(version: Long) {
        val reviewingEpoch = epoch
        val ruleSet = storage.rules()
        if (ruleSet.version != version) return
        val document = RuleJson.decodeStored(ruleSet.rulesJson)
        val rules = document.rules.filter { it.frequency == null }
        if (document.modes.values.none { it == Action.HIDE } && rules.none { it.enabled && it.action == Action.HIDE }) return
        val visible = activeNotifications ?: return
        val rankingMap = currentRanking
        for (card in visible) {
            if (epoch != reviewingEpoch || observedRulesVersion != version || !connected) return
            val slot = active[card.key]?.takeIf { card.postTime == it.postTime } ?: continue
            val source = sources?.get(card.packageName)?.takeIf { it.enabled } ?: continue
            val settings = retention ?: return
            val snapshot = reader.read(card, rankingMap = rankingMap)
            if (!snapshot.contentAvailable || snapshot.protection != null) continue
            val event = Posted(
                epoch = reviewingEpoch,
                key = card.key,
                slot = slot,
                snapshot = snapshot,
                sourceRevision = source.consentRevision,
                retentionRevision = settings.retentionRevision.takeIf { settings.retainContent },
                archive = null,
                countAsNew = false,
                elapsedMillis = SystemClock.elapsedRealtime(),
                observedAtMillis = System.currentTimeMillis(),
                reviewVersion = version,
                contentAction = card.activityAction(),
            )
            val fallback = document.modes.unmatchedAction(snapshot.packageName, snapshot.channelId)
            if (RuleEngine.evaluate(notificationEvent(event), rules, unmatched = fallback).decision.action != Action.HIDE) continue
            val archive = if (settings.retainContent) readArchive(card.notification, snapshot, reader) else null
            handlePosted(event.copy(archive = archive))
        }
    }

    private suspend fun persistGap(gap: Reset) {
        openGapId = database.writeTransaction {
            storage.clearFrequency()
            val previous = openGapId
            if (previous != null) {
                if (gap.endedAtMillis != null) storage.closeGap(previous, gap.endedAtMillis)
                previous.takeIf { gap.endedAtMillis == null }
            } else {
                val recorded = storage.appendGap(gap.beganAtMillis, gap.reason, gap.endedAtMillis)
                recorded.takeIf { gap.endedAtMillis == null }
            }
        }
    }

    private fun StatusBarNotification.activityAction(): PendingIntent? =
        notification.contentIntent?.takeIf { it.isActivity && it.creatorPackage == packageName }

    private suspend fun handlePosted(event: Posted) {
        val outcome = withContext(Dispatchers.Default) {
            val image = event.archive?.encodeImage(this@PushMeNotListenerService, event.snapshot.packageName)
            persistDecision(event, image)
        } ?: return
        if (event.reviewVersion != null) event.slot.revision++
        event.slot.journalId = outcome.journalId
        if (connected && event.epoch == epoch && event.retentionRevision != null &&
            event.retentionRevision == retention?.retentionRevision
        ) {
            event.contentAction?.let { JournalDestinations.remember(outcome.journalId, it) }
        }
        if (outcome.action != Action.HIDE || !isCurrent(event)) return

        val current = withContext(Dispatchers.IO) {
            val source = storage.source(event.snapshot.packageName)
            if (source?.enabled != true || source.consentRevision != event.sourceRevision ||
                storage.rules().version != outcome.rulesVersion
            ) {
                return@withContext false
            }
            val visible = getActiveNotifications(arrayOf(event.key))?.singleOrNull() ?: return@withContext false
            visible.key == event.key && visible.postTime == event.slot.postTime &&
                reader.fingerprint(reader.read(visible)) == reader.fingerprint(event.snapshot)
        }
        if (!current || !isCurrent(event)) return
        cancelNotification(event.key)
        storage.recordDismissalRequested(outcome.journalId, System.currentTimeMillis(), event.slot.lifecycleId)
    }

    private fun isCurrent(event: Posted): Boolean =
        connected && event.epoch == epoch && reset == null && pendingFailure == null &&
            active[event.key] === event.slot &&
            sources?.get(event.snapshot.packageName)?.consentRevision == event.sourceRevision

    private fun notificationEvent(event: Posted): NotificationEvent {
        val card = event.snapshot
        return NotificationEvent(
            profileId = card.profileId,
            packageName = card.packageName,
            elapsedMillis = event.elapsedMillis,
            channelId = card.channelId,
            title = card.title,
            body = card.body,
            sender = card.sender,
            senderKey = card.senderIdentity,
            countAsNew = event.countAsNew,
            protection = card.protection,
            contentAvailable = card.contentAvailable,
        )
    }

    private suspend fun persistDecision(event: Posted, image: ByteArray?): Outcome? = database.writeTransaction {
        val card = event.snapshot
        val source = storage.source(card.packageName)
        val filter = source?.enabled == true && source.consentRevision == event.sourceRevision
        val ruleSet = if (filter) storage.rules() else null
        val notification = notificationEvent(event)
        val review = event.reviewVersion != null
        if (review && ruleSet?.version != event.reviewVersion) return@writeTransaction null
        val previous = if (ruleSet != null && !review) storage.frequency() else null
        val evaluation = if (ruleSet != null) {
            val document = RuleJson.decodeStored(ruleSet.rulesJson)
            val rules = if (review) document.rules.filter { it.frequency == null } else document.rules
            RuleEngine.evaluate(
                notification, rules, FrequencyJson.decode(previous),
                document.modes.unmatchedAction(card.packageName, card.channelId),
            )
        } else {
            null
        }
        val decision = evaluation?.decision
        if (review && decision?.action != Action.HIDE) return@writeTransaction null
        val journalId = storage.appendNotification(
            JournalItem(
                observedAtMillis = event.observedAtMillis,
                profileId = card.profileId,
                packageName = card.packageName,
                channelId = card.channelId,
                channelName = card.channelName,
                lifecycleId = event.slot.lifecycleId,
                generation = event.slot.generation,
                revision = event.slot.revision + if (review) 1 else 0,
                elapsedMillis = event.elapsedMillis,
                countAsNew = event.countAsNew,
                contentAvailable = card.contentAvailable,
                archivedText = event.archive?.extraText,
                protection = card.protection?.name,
                title = card.title,
                body = card.body,
                sender = card.sender,
                senderKey = card.senderIdentity,
                verdict = decision?.action?.name,
                decisionReason = decision?.reason?.name,
                ruleId = decision?.rule?.id,
                ruleRevision = decision?.rule?.revision,
                ruleScope = decision?.ruleScope?.name,
                rulesVersion = ruleSet?.version,
            ),
            event.retentionRevision,
            image,
        )
        if (evaluation != null && !review) {
            val next = FrequencyJson.encode(evaluation.frequencyState)
            if (next != previous) {
                if (next == null) storage.clearFrequency() else storage.saveFrequency(next)
            }
        }
        Outcome(journalId, decision?.action, ruleSet?.version)
    }

    private sealed interface Signal { val epoch: Long }

    private data class Reset(val beganAtMillis: Long, val endedAtMillis: Long?, val reason: String)

    private data class Posted(
        override val epoch: Long,
        val key: String,
        val slot: CardSlot,
        val snapshot: NotificationSnapshot,
        val sourceRevision: Long?,
        val retentionRevision: Long?,
        val archive: NotificationArchive?,
        val countAsNew: Boolean,
        val elapsedMillis: Long,
        val observedAtMillis: Long,
        val reviewVersion: Long? = null,
        val contentAction: PendingIntent? = null,
    ) : Signal

    private data class Removed(
        override val epoch: Long,
        val slot: CardSlot,
        val atMillis: Long,
        val reason: Int,
    ) : Signal

    private class CardSlot(
        val lifecycleId: String,
        val generation: Long,
        var revision: Long,
        val postTime: Long,
        val fingerprint: String?,
        val archiveFingerprint: Int? = null,
        var journalId: Long? = null,
    )

    private data class Outcome(val journalId: Long, val action: Action?, val rulesVersion: Long?)

    companion object {
        private const val MAX_QUEUE = 128
        private const val MAX_ACTIVE = 1024
        private val connection = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = connection.asStateFlow()
        @Volatile private var shadeProvider: WeakReference<PushMeNotListenerService>? = null
        private val shadeChanges = MutableStateFlow(0L)
        internal val visibleShadeChanges = shadeChanges.asStateFlow()
        internal fun visibleShade(): List<ShadeCard>? = shadeProvider?.get()?.readShade()
    }
}
