package io.github.vitalyostanin.pushmenot.listener

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Parcelable
import android.util.Size
import androidx.core.os.BundleCompat
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import java.io.ByteArrayOutputStream

internal data class NotificationArchive(
    val extraText: String?,
    val fingerprint: Int,
    private val bitmap: Bitmap?,
    private val icon: Icon?,
) {
    fun encodeImage(context: Context, packageName: String): ByteArray? = try {
        val thumbnail = if (bitmap == null && icon?.type == Icon.TYPE_URI) {
            decodeOffloadedPicture(context, icon)
        } else {
            drawThumbnail(context, packageName)
        }
        thumbnail?.let {
            try {
                val output = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, output)
                if (output.size() > PushStorage.MAX_MEDIA_BYTES) {
                    output.reset()
                    it.compress(Bitmap.CompressFormat.WEBP_LOSSY, 30, output)
                }
                output.toByteArray().takeIf { bytes -> bytes.isNotEmpty() && bytes.size <= PushStorage.MAX_MEDIA_BYTES }
            } finally {
                it.recycle()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun drawThumbnail(context: Context, packageName: String): Bitmap? {
        val drawable = when (icon?.type) {
            Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> icon.loadDrawable(context)
            Icon.TYPE_RESOURCE -> when (icon.resPackage) {
                "android" -> icon.loadDrawable(context)
                packageName -> icon.loadDrawable(context.createPackageContext(packageName, 0))
                else -> null
            }
            else -> null
        }
        val width = bitmap?.width ?: drawable?.intrinsicWidth ?: return null
        val height = bitmap?.height ?: drawable?.intrinsicHeight ?: return null
        if (width <= 0 || height <= 0) return null

        val size = thumbnailSize(width, height)
        val thumbnail = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(thumbnail)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, size.width, size.height), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                drawable?.setBounds(0, 0, size.width, size.height)
                drawable?.draw(canvas)
            }
            return thumbnail
        } catch (error: Exception) {
            thumbnail.recycle()
            throw error
        }
    }

    private fun decodeOffloadedPicture(context: Context, icon: Icon): Bitmap? {
        val uri = icon.uri
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.host != BITMAP_OFFLOAD_AUTHORITY) return null
        // Android can pass a large notification picture through its own content provider.
        if (context.packageManager.resolveContentProvider(BITMAP_OFFLOAD_AUTHORITY, 0)?.packageName != "android") {
            return null
        }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = thumbnailSize(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(size.width, size.height)
        }
    }

    private fun thumbnailSize(width: Int, height: Int): Size {
        val scale = minOf(1.0, MAX_THUMBNAIL_SIDE.toDouble() / maxOf(width, height))
        return Size((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val MAX_THUMBNAIL_SIDE = 512
        const val BITMAP_OFFLOAD_AUTHORITY = "com.android.bitmapoffload"
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
                val entries = BundleCompat.getParcelableArray(extras, key, Parcelable::class.java) ?: return@flatMap emptyList()
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
        val bitmap = BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?.takeUnless { it.config == Bitmap.Config.HARDWARE }
        val icon = BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java)
        NotificationArchive(text, listOf(text, bitmap?.generationId, icon?.toString()).hashCode(), bitmap, icon)
    } catch (_: RuntimeException) {
        null
    }
}
