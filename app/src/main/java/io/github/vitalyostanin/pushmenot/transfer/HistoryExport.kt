package io.github.vitalyostanin.pushmenot.transfer

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.JsonWriter
import android.util.Patterns
import com.pushmenot.core.Action
import io.github.vitalyostanin.pushmenot.storage.HistoryQuery
import io.github.vitalyostanin.pushmenot.storage.PushStorage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistoryExportOptions(
    val packageName: String?,
    val days: Int,
    val titles: Boolean,
    val text: Boolean,
    val images: Boolean,
    val untilMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(days == 1 || days == 7)
    }

    val includesContent: Boolean get() = titles || text || images

    internal fun query() = HistoryQuery(
        packageName = packageName,
        sinceMillis = untilMillis - days * 86_400_000L,
        untilMillis = untilMillis,
    )

    internal fun toSavedState() = Bundle().apply {
        putString("package", packageName)
        putInt("days", days)
        putBoolean("titles", titles)
        putBoolean("text", text)
        putBoolean("images", images)
        putLong("until", untilMillis)
    }

    companion object {
        internal fun fromSavedState(state: Bundle): HistoryExportOptions? {
            val days = state.getInt("days")
            if (days != 1 && days != 7 || !state.containsKey("until")) return null
            return HistoryExportOptions(
                state.getString("package"),
                days,
                state.getBoolean("titles"),
                state.getBoolean("text"),
                state.getBoolean("images"),
                state.getLong("until"),
            )
        }
    }
}

internal class HistoryExport(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val storage: PushStorage,
) {
    suspend fun count(options: HistoryExportOptions): Int =
        storage.countHistoryExport(options.query())

    suspend fun write(uri: Uri, options: HistoryExportOptions) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("history-export-", ".json", cacheDir)
        try {
            JsonWriter(temporary.bufferedWriter(Charsets.UTF_8)).use { writer ->
                writer.beginObject().name("version").value(1L).name("entries").beginArray()
                storage.readHistoryExport(options.query(), options.images) { entry, image ->
                    writer.beginObject()
                    writer.name("app").value(entry.packageName)
                    writer.name("receivedHourUtcMillis").value(Math.floorDiv(entry.observedAtMillis, 3_600_000L) * 3_600_000L)
                    val result = when (entry.verdict) {
                        Action.HIDE.name -> if (entry.dismissalRequestedAtMillis != null &&
                            entry.removedAtMillis != null &&
                            entry.removalReason == NotificationListenerService.REASON_LISTENER_CANCEL
                        ) "HIDDEN" else "HIDE_DECISION"
                        Action.KEEP.name -> "KEPT"
                        else -> "NOT_FILTERED"
                    }
                    writer.name("result").value(result)
                    if (options.titles) writer.name("title").value(HistoryExportRedaction.text(entry.title))
                    if (options.text) {
                        writer.name("text").value(HistoryExportRedaction.text(entry.body))
                        writer.name("additionalText").value(HistoryExportRedaction.text(entry.archivedText))
                    }
                    if (image != null) writer.name("imageBase64").value(Base64.encodeToString(image, Base64.NO_WRAP))
                    writer.endObject()
                }
                writer.endArray().endObject()
            }
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Unable to open export file")
            output.use { destination -> temporary.inputStream().use { it.copyTo(destination) } }
        } finally {
            temporary.delete()
        }
    }
}

private object HistoryExportRedaction {
    private const val CODE_LABEL =
        """(?:verification code|security code|confirmation code|login code|one[- ]time code|otp|pin|code|password|код(?:\s+(?:подтверждения|входа|операции))?|пароль|пин[- ]код)"""

    private val link = Regex("""(?i)\b(?:[a-z][a-z\d+.-]{1,20}://|www\.|(?:t\.me|wa\.me)/)[^\s<>"']+""")
    private val prefixedCode = Regex("""(?iu)\b($CODE_LABEL\s*(?:[:=—–-]\s*)?)\p{Nd}{4,8}(?![\p{L}\p{Nd}])""")
    private val suffixedCode = Regex("""(?iu)(?<![\p{L}\p{Nd}])\p{Nd}{4,8}(\s+(?:[-—–]\s*)?$CODE_LABEL\b)""")
    private val longNumber = Regex("""(?<![\p{L}\p{Nd}])\+?\p{Nd}(?:[ ().\u00a0\u202f-]{0,3}\p{Nd}){6,}(?![\p{L}\p{Nd}])""")

    fun text(value: String?): String? {
        val withoutLinks = value?.let { link.replace(it, "[link]") } ?: return null
        val withoutEmails = Patterns.EMAIL_ADDRESS.matcher(withoutLinks).replaceAll("[email]")
        val withoutPrefixedCodes = prefixedCode.replace(withoutEmails) { "${it.groupValues[1]}[code]" }
        val withoutCodes = suffixedCode.replace(withoutPrefixedCodes) { "[code]${it.groupValues[1]}" }
        return longNumber.replace(withoutCodes, "[number]")
    }
}
