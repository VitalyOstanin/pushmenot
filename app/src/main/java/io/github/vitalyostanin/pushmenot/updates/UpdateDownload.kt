package io.github.vitalyostanin.pushmenot.updates

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class UpdateDownload(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    /** Runs on IO, writes to an app-private temporary file and checks the signed release asset's checksum. */
    suspend fun copy(release: UpdateResult.Available, destination: File, progress: (Int) -> Unit) {
        val asset = requireNotNull(release.asset)
        var url = URI(release.downloadUrl)
        val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MILLIS
        repeat(4) {
            checkDeadline(deadline)
            if (url.scheme != "https" || url.host !in downloadHosts || url.userInfo != null || url.port !in setOf(-1, 443)) {
                throw IOException("Unexpected update download URL")
            }
            val request = connect(url.toURL())
            try {
                request.instanceFollowRedirects = false
                request.connectTimeout = 5_000
                request.readTimeout = 10_000
                request.setRequestProperty("Accept-Encoding", "identity")
                when (request.responseCode) {
                    200 -> {
                        copyChecked(request, asset, destination, deadline, progress)
                        return
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = request.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        url = url.resolve(location)
                    }
                    else -> throw IOException("Update download request failed")
                }
            } finally {
                request.disconnect()
            }
        }
        throw IOException("Too many download redirects")
    }

    private suspend fun copyChecked(
        request: HttpURLConnection,
        asset: UpdateAsset,
        destination: File,
        deadline: Long,
        progress: (Int) -> Unit,
    ) {
        val responseLength = request.contentLengthLong
        if (responseLength >= 0 && responseLength != asset.size) throw IOException("Unexpected APK length")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        var percent = 0
        request.inputStream.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    checkDeadline(deadline)
                    val size = input.read(buffer)
                    if (size < 0) break
                    downloaded += size
                    if (downloaded > asset.size) throw IOException("APK exceeds expected size")
                    sha256.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                    val current = (downloaded * 100 / asset.size).toInt()
                    if (current != percent) {
                        percent = current
                        progress(percent)
                    }
                }
            }
        }
        val actualDigest = sha256.digest().joinToString("") { "%02x".format(it) }
        if (downloaded != asset.size || actualDigest != asset.sha256) throw IOException("APK size or digest mismatch")
    }

    private suspend fun checkDeadline(deadline: Long) {
        currentCoroutineContext().ensureActive()
        if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Update download timed out")
    }

    private companion object {
        val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        const val DOWNLOAD_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
