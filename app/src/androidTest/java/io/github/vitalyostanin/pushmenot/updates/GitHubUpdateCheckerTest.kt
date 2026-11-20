package io.github.vitalyostanin.pushmenot.updates

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun comparesVersionNumbersAndBuildsOnlyTheKnownReleaseUrl() = runBlocking {
        val response = ReleaseResponse(200, release("v0.2.10"))
        val checker = GitHubUpdateChecker { response }
        val update = checker.check("0.2.9") as UpdateResult.Available
        assertEquals("0.2.10", update.version)
        assertEquals("https://github.com/VitalyOstanin/pushmenot/releases/tag/v0.2.10", update.releaseUrl)
        assertFalse(response.instanceFollowRedirects)
        assertTrue(response.disconnected)
        assertEquals(UpdateResult.Current, checker.check("0.2.10"))
        assertEquals(UpdateResult.Current, checker.check("1.0.0"))
    }

    @Test
    fun distinguishesNoPublishedReleaseFromAnUnsuccessfulCheck() = runBlocking {
        assertEquals(UpdateResult.NoPublishedRelease, check(404))
        assertEquals(UpdateResult.NoPublishedRelease, check(200, release("v0.2.8-beta", prerelease = true)))
        assertEquals(UpdateResult.NoPublishedRelease, check(200, release("v0.2.8", draft = true)))
        assertEquals(UpdateResult.Failed, check(302))
        assertEquals(UpdateResult.Failed, check(403))
        assertEquals(UpdateResult.Failed, check(200, "not JSON"))
        assertEquals(UpdateResult.Failed, check(200, release("v0.2.08")))
        assertEquals(UpdateResult.Failed, check(200, " ".repeat(256 * 1024 + 1)))
        assertEquals(UpdateResult.Failed, GitHubUpdateChecker { throw SocketTimeoutException() }.check("0.2.7"))
    }

    @Test
    fun inAppInstallRequiresOneUploadedAssetWithAValidDigest() = runBlocking {
        val digest = "a".repeat(64)
        val apk = """{"name":"push-me-not-0.2.8.apk","state":"uploaded","size":1234,"digest":"sha256:$digest"}"""
        val release = check(200, release("v0.2.8").dropLast(1) + ""","assets":[$apk]}""") as UpdateResult.Available
        assertEquals(UpdateAsset(1234, digest), release.asset)
        assertEquals("https://github.com/VitalyOstanin/pushmenot/releases/download/v0.2.8/push-me-not-0.2.8.apk", release.downloadUrl)
        val invalid = listOf(apk.replace("sha256:", "bad:"), "$apk,$apk", apk.replace("uploaded", "new"))
        for (assets in invalid) {
            val result = check(200, release("v0.2.8").dropLast(1) + ""","assets":[$assets]}""") as UpdateResult.Available
            assertEquals(null, result.asset)
        }
    }

    private suspend fun check(code: Int, body: String = "") =
        GitHubUpdateChecker { ReleaseResponse(code, body) }.check("0.2.7")

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://example.invalid/"}"""

    private class ReleaseResponse(private val status: Int, private val body: String) :
        HttpURLConnection(URI("https://example.invalid").toURL()) {
        var disconnected = false
            private set

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
    }
}
