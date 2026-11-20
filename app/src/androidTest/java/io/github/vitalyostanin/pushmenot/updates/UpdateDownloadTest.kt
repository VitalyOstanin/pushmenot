package io.github.vitalyostanin.pushmenot.updates

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadTest {
    private val bytes = "abc".toByteArray()
    private val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun streamsAndVerifiesBytesAfterGitHubRedirect() = runBlocking {
        val hosts = mutableListOf<String>()
        val withRedirect = UpdateDownload { url ->
            hosts += url.host
            if (hosts.size == 1) Response(302, "https://release-assets.githubusercontent.com/signed")
            else Response(200, body = bytes)
        }
        val progress = mutableListOf<Int>()
        withTarget { file ->
            withRedirect.copy(release(), file) { progress += it }
            assertArrayEquals(bytes, file.readBytes())
        }
        assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), hosts)
        assertEquals(listOf(100), progress)
    }

    @Test
    fun refusesUnexpectedHostAndMismatchedBytes() = runBlocking {
        var connects = 0
        val untrustedRedirect = UpdateDownload {
            connects++
            Response(302, "https://example.invalid/some-apk")
        }
        withTarget { file ->
            val redirect = runCatching { untrustedRedirect.copy(release(), file) {} }
            assertTrue(redirect.exceptionOrNull() is IOException)
            assertEquals(1, connects)
            val download = UpdateDownload { Response(200, body = "xyz".toByteArray()) }
            val mismatch = runCatching { download.copy(release(), file) {} }
            assertTrue(mismatch.exceptionOrNull() is IOException)
        }
    }

    private fun release() = UpdateResult.Available("0.2.8", UpdateAsset(bytes.size.toLong(), digest))

    private suspend fun withTarget(block: suspend (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("test-update-", ".apk", cache)
        try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private class Response(
        private val status: Int,
        private val location: String? = null,
        private val body: ByteArray = byteArrayOf(),
    ) : HttpURLConnection(URI("https://example.invalid").toURL()) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = location.takeIf { name == "Location" }
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream() = body.inputStream()
    }
}
