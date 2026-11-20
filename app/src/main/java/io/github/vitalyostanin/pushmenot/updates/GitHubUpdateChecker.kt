package io.github.vitalyostanin.pushmenot.updates

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

sealed interface UpdateResult {
    data object Idle : UpdateResult
    data object Checking : UpdateResult
    data object Current : UpdateResult
    data object NoPublishedRelease : UpdateResult
    data object Failed : UpdateResult
    data class Available(val version: String, val asset: UpdateAsset? = null) : UpdateResult {
        internal val fileName: String get() = "push-me-not-$version.apk"
        internal val downloadUrl: String get() = "$RELEASES_PAGE/download/v$version/$fileName"
        val releaseUrl: String get() = "$RELEASES_PAGE/tag/v$version"
    }
}

data class UpdateAsset internal constructor(val size: Long, val sha256: String)

internal const val MAX_UPDATE_APK_BYTES = 128L * 1024 * 1024
private val updateDigestPattern = Regex("sha256:[0-9a-f]{64}")

internal class GitHubUpdateChecker(
    private val connect: () -> HttpURLConnection = {
        URI(API_ENDPOINT).toURL().openConnection() as HttpURLConnection
    },
) {
    suspend fun check(installedVersion: String): UpdateResult {
        val installed = ReleaseVersion.parse(installedVersion) ?: return UpdateResult.Failed
        return withContext(Dispatchers.IO) {
            try {
                val request = connect()
                try {
                    request.instanceFollowRedirects = false
                    request.connectTimeout = 5_000
                    request.readTimeout = 7_000
                    request.setRequestProperty("Accept", "application/vnd.github+json")
                    request.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                    when (request.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val response = request.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
                            if (response.size > MAX_RESPONSE_BYTES) UpdateResult.Failed
                            else decide(installed, JSONObject(response.toString(Charsets.UTF_8)))
                        }
                        HttpURLConnection.HTTP_NOT_FOUND -> UpdateResult.NoPublishedRelease
                        else -> UpdateResult.Failed
                    }
                } finally {
                    request.disconnect()
                }
            } catch (_: IOException) {
                UpdateResult.Failed
            } catch (_: JSONException) {
                UpdateResult.Failed
            } catch (_: SecurityException) {
                UpdateResult.Failed
            }
        }
    }

    private fun decide(installed: ReleaseVersion, response: JSONObject): UpdateResult {
        if (response.getBoolean("draft") || response.getBoolean("prerelease")) {
            return UpdateResult.NoPublishedRelease
        }
        val tag = response.getString("tag_name")
        if (!tag.startsWith("v")) return UpdateResult.Failed
        val number = tag.substring(1)
        val version = ReleaseVersion.parse(number) ?: return UpdateResult.Failed
        if (version <= installed) return UpdateResult.Current
        val release = UpdateResult.Available(number)
        val assets = response.optJSONArray("assets") ?: return release
        val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name") == release.fileName && it.optString("state") == "uploaded" }
        val asset = candidates.singleOrNull() ?: return release
        val size = asset.optLong("size")
        val digest = asset.optString("digest")
        if (size !in 1..MAX_UPDATE_APK_BYTES || !updateDigestPattern.matches(digest)) return release
        return release.copy(asset = UpdateAsset(size, digest.substringAfter(':')))
    }
}

private data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val (major, minor, patch) = match.destructured
            return ReleaseVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
            )
        }
    }
}

private const val RELEASES_PAGE = "https://github.com/VitalyOstanin/pushmenot/releases"
private const val API_ENDPOINT = "https://api.github.com/repos/VitalyOstanin/pushmenot/releases/latest"
private const val MAX_RESPONSE_BYTES = 256 * 1024
