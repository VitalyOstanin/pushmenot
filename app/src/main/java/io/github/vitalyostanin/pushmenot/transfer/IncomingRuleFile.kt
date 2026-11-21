package io.github.vitalyostanin.pushmenot.transfer

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal fun Intent.isRuleFileDelivery(): Boolean =
    action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND

internal fun Intent.externalRuleUri(ownPackage: String): Uri? {
    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> {
            val clip = clipData
            if (clip != null && clip.itemCount != 1) return null
            val stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            val clippedUri = clip?.getItemAt(0)?.uri
            if (stream != null && clippedUri != null && stream != clippedUri) return null
            stream ?: clippedUri
        }
        else -> null
    } ?: return null
    if (uri.scheme != "content") return null
    val provider = uri.authority?.substringAfterLast('@')?.takeIf(String::isNotBlank) ?: return null
    if (provider == ownPackage || provider.startsWith("$ownPackage.")) return null
    return uri
}
