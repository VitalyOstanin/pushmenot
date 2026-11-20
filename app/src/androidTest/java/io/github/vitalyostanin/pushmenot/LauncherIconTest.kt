package io.github.vitalyostanin.pushmenot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test
    fun launcherRendersYellowSunAndSuppliesMonochromeLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, 48, 48)
            icon.draw(Canvas(bitmap))
            assertTrue((9..38).any { y ->
                (9..38).any { x ->
                    val color = bitmap.getPixel(x, y)
                    android.graphics.Color.red(color) > 210 &&
                        android.graphics.Color.green(color) > 150 &&
                        android.graphics.Color.blue(color) < 160
                }
            })
        } finally {
            bitmap.recycle()
        }
    }
}
