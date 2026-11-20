package io.github.vitalyostanin.pushmenot.sources

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class JournalApp(val label: String?, val icon: Bitmap?)

internal class JournalApps(private val packages: PackageManager, private val iconPixels: Int) {
    private val cache = LruCache<String, JournalApp>(64)

    fun cached(packageName: String): JournalApp? = cache.get(packageName)

    suspend fun load(packageName: String): JournalApp = withContext(Dispatchers.IO) {
        cache.get(packageName)?.let { return@withContext it }
        val info = try {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext JournalApp(null, null)
        } catch (_: SecurityException) {
            return@withContext JournalApp(null, null)
        }
        val label = packages.getApplicationLabel(info).toString().ifBlank { packageName }
        val drawable = packages.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(iconPixels, iconPixels, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, iconPixels, iconPixels)
        drawable.draw(Canvas(bitmap))
        JournalApp(label, bitmap).also { cache.put(packageName, it) }
    }
}
