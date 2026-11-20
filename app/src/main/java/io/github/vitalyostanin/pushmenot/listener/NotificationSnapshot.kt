package io.github.vitalyostanin.pushmenot.listener

import android.annotation.SuppressLint
import android.app.Notification
import android.content.res.Resources
import android.os.Build
import android.os.Parcelable
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Patterns
import com.pushmenot.core.Protection
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class NotificationSnapshot(
    val profileId: String,
    val packageName: String,
    val channelId: String?,
    val title: String? = null,
    val body: String? = null,
    val sender: String? = null,
    val senderIdentity: String? = null,
    val protection: Protection? = null,
    val contentAvailable: Boolean = false,
    val channelName: String? = null,
)

internal class NotificationReader(private val resources: Resources) {
    private val macKey = SecretKeySpec(SecureRandom().generateSeed(32), "HmacSHA256")

    fun read(
        card: StatusBarNotification,
        readContent: Boolean = true,
        rankingMap: RankingMap? = null,
    ): NotificationSnapshot {
        val notification = card.notification
        val protection = when {
            card.packageName in emergencyPackages || notification.category == "emergency" ->
                Protection.EMERGENCY
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> Protection.GROUP_SUMMARY
            card.isOngoing -> Protection.ONGOING
            !card.isClearable -> Protection.NOT_CLEARABLE
            else -> null
        }
        val metadata = NotificationSnapshot(
            profileId = card.user.hashCode().toString(),
            packageName = card.packageName,
            channelId = notification.channelId?.takeIf { it.length <= MAX_ID_LENGTH },
            protection = protection,
            channelName = readableChannelName(card, rankingMap),
        )
        if (!readContent || protection != null || notification.visibility == Notification.VISIBILITY_SECRET) {
            return metadata
        }

        return try {
            content(notification, metadata)
        } catch (_: RuntimeException) {
            metadata
        }
    }

    private fun readableChannelName(card: StatusBarNotification, rankingMap: RankingMap?): String? {
        val id = card.notification.channelId ?: return null
        val ranking = Ranking()
        if (rankingMap?.getRanking(card.key, ranking) != true) return null
        val channel = ranking.channel?.takeIf { it.id == id } ?: return null
        return channel.name?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it != id.trim() &&
                !Patterns.EMAIL_ADDRESS.matcher(it).find()
        }
    }

    private fun content(notification: Notification, metadata: NotificationSnapshot): NotificationSnapshot {
        val extras = notification.extras ?: return metadata
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (rawMessages != null && rawMessages.size > 1 || textLines != null && textLines.size > 1) {
            return metadata.copy(protection = Protection.AMBIGUOUS_MESSAGES)
        }

        val structured = if (rawMessages != null && rawMessages.isNotEmpty()) {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(rawMessages).singleOrNull()
                ?: return metadata
        } else {
            null
        }
        val person = structured?.senderPerson
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val body = structured?.text
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: textLines?.singleOrNull()
        val sender = person?.name
        val identity = person?.key ?: person?.uri ?: sender
        if (listOf(title, body, sender, identity).any { it != null && it.length > MAX_TEXT_LENGTH }) {
            return metadata
        }
        val titleText = title?.toString()
        val bodyText = body?.toString()
        if (titleText.isNullOrBlank() && bodyText.isNullOrBlank() || isRedacted(bodyText)) return metadata

        return metadata.copy(
            title = titleText,
            body = bodyText,
            sender = sender?.toString(),
            senderIdentity = identity?.toString()?.let { digest("sender", metadata.profileId, metadata.packageName, it) },
            contentAvailable = true,
        )
    }

    // Framework-redacted cards use a localized placeholder with no public marker as of API 36.
    @SuppressLint("DiscouragedApi")
    internal fun isRedacted(text: String?): Boolean {
        if (Build.VERSION.SDK_INT < 35 || text == null) return false
        val placeholder = resources.getIdentifier("redacted_notification_message", "string", "android")
        return if (placeholder != 0) text == resources.getString(placeholder) else text == "Sensitive content hidden"
    }

    fun fingerprint(snapshot: NotificationSnapshot): String = digest(
        "card",
        snapshot.channelId,
        snapshot.title,
        snapshot.body,
        snapshot.sender,
        snapshot.senderIdentity,
        snapshot.protection?.name,
        snapshot.contentAvailable.toString(),
    )

    private fun digest(vararg fields: String?): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
        for (field in fields) {
            val bytes = field?.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) mac.update(bytes)
        }
        return android.util.Base64.encodeToString(mac.doFinal(), android.util.Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_ID_LENGTH = 512
        const val MAX_TEXT_LENGTH = 4096
        val emergencyPackages = setOf(
            "com.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver.module",
            "com.google.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver.module",
        )
    }
}
