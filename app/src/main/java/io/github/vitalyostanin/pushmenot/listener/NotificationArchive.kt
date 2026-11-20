package io.github.vitalyostanin.pushmenot.listener

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Parcelable
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import java.io.ByteArrayOutputStream

internal data class NotificationArchive(
    val extraText: String?,
    val fingerprint: Int,
    private val bitmap: Bitmap?,
    private val icon: Icon?,
) {
    fun encodeImage(context: Context, packageName: String): ByteArray? = try {
        val drawable = when (icon?.type) {
            Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> icon.loadDrawable(context)
            Icon.TYPE_RESOURCE -> when (icon.resPackage) {
                "android" -> icon.loadDrawable(context)
                packageName -> icon.loadDrawable(context.createPackageContext(packageName, 0))
                else -> null
            }
            else -> null
        }
        val width = bitmap?.width ?: drawable?.intrinsicWidth ?: 0
        val height = bitmap?.height ?: drawable?.intrinsicHeight ?: 0
        if (width <= 0 || height <= 0) {
            null
        } else {
            val scale = minOf(1.0, 512.0 / maxOf(width, height))
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val thumbnail = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(thumbnail)
                if (bitmap != null) {
                    canvas.drawBitmap(
                        bitmap, null, android.graphics.Rect(0, 0, targetWidth, targetHeight),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                    )
                } else {
                    drawable?.setBounds(0, 0, targetWidth, targetHeight)
                    drawable?.draw(canvas)
                }
                val output = ByteArrayOutputStream()
                thumbnail.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, output)
                if (output.size() > PushStorage.MAX_MEDIA_BYTES) {
                    output.reset()
                    thumbnail.compress(Bitmap.CompressFormat.WEBP_LOSSY, 30, output)
                }
                output.toByteArray().takeIf { it.isNotEmpty() && it.size <= PushStorage.MAX_MEDIA_BYTES }
            } finally {
                thumbnail.recycle()
            }
        }
    } catch (_: Exception) {
        null
    }
}

internal fun readArchive(
    notification: Notification,
    snapshot: NotificationSnapshot,
    reader: NotificationReader,
): NotificationArchive? {
    if (notification.visibility == Notification.VISIBILITY_SECRET) return null
    return try {
        val extras = notification.extras ?: return null
        val fields = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_CONVERSATION_TITLE,
            Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION,
        ).mapNotNull { extras.getCharSequence(it)?.toString() }
        if (fields.any(reader::isRedacted)) return null

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        val messages = listOf(Notification.EXTRA_HISTORIC_MESSAGES, Notification.EXTRA_MESSAGES)
            .flatMap { key ->
                val entries = extras.getParcelableArray(key, Parcelable::class.java) ?: return@flatMap emptyList()
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(entries)
            }
        if (lines.any(reader::isRedacted) || messages.any { reader.isRedacted(it.text?.toString()) }) return null
        val actions = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
        val mainFields = setOf(snapshot.title, snapshot.body, snapshot.sender)
        val supplementary = (fields + lines).filter { it.isNotBlank() && it !in mainFields }.distinct()
        val messageText = if (messages.size == 1 && messages.single().text?.toString() == snapshot.body) {
            emptyList()
        } else {
            messages.mapNotNull { message ->
                message.text?.toString()?.let { value ->
                    listOfNotNull(message.senderPerson?.name?.toString(), value).joinToString(": ")
                }
            }
        }
        val text = (supplementary + messageText + actions).takeIf { it.isNotEmpty() }?.joinToString("\n")
        val bitmap = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?.takeUnless { it.config == Bitmap.Config.HARDWARE }
        val icon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
        NotificationArchive(text, listOf(text, bitmap?.generationId, icon?.toString()).hashCode(), bitmap, icon)
    } catch (_: RuntimeException) {
        null
    }
}
