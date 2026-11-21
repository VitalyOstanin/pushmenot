package io.github.vitalyostanin.pushmenot

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

internal class TestAppLocale(private val context: Context) {
    private val previous = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales
    } else {
        null
    }

    fun set(language: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        return true
    }

    fun restore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && previous != null) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = previous
        }
    }
}
