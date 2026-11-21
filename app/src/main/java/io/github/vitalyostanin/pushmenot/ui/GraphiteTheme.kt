package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import io.github.vitalyostanin.pushmenot.R

private val lightGraphite = lightColorScheme(
    primary = Color(0xff414a85),
    onPrimary = Color.White,
    primaryContainer = Color(0xffdfe4fa),
    onPrimaryContainer = Color(0xff202333),
    secondary = Color(0xff535970),
    secondaryContainer = Color(0xffdfe4fa),
    onSecondaryContainer = Color(0xff202333),
    onBackground = Color(0xff202333),
    surface = Color.White,
    onSurface = Color(0xff202333),
    surfaceVariant = Color(0xffe9ecf6),
    onSurfaceVariant = Color(0xff535970),
    surfaceContainerHigh = Color(0xffe9ecf6),
    outline = Color(0xff6b7294),
    outlineVariant = Color(0xffc4cadd),
    tertiary = Color(0xff79480c),
    tertiaryContainer = Color(0xfffff0d7),
    onTertiaryContainer = Color(0xff5c3b16),
    error = Color(0xff8a3444),
    errorContainer = Color(0xfffff0f1),
    onErrorContainer = Color(0xff8a3444),
)

private val darkGraphite = darkColorScheme(
    primary = Color(0xffb5bff8),
    primaryContainer = Color(0xff363e60),
    onPrimaryContainer = Color(0xfff0f1fa),
    secondary = Color(0xffb9bfd1),
    secondaryContainer = Color(0xff363e60),
    onSecondaryContainer = Color(0xfff0f1fa),
    onBackground = Color(0xfff0f1fa),
    surface = Color(0xff252936),
    onSurface = Color(0xfff0f1fa),
    surfaceVariant = Color(0xff1d202c),
    onSurfaceVariant = Color(0xffb9bfd1),
    surfaceContainerLow = Color(0xff1d202c),
    surfaceContainerHigh = Color(0xff252936),
    outline = Color(0xff8691b6),
    outlineVariant = Color(0xff555e7c),
    tertiary = Color(0xffffca79),
    tertiaryContainer = Color(0xff50391f),
    onTertiaryContainer = Color(0xffffdfaa),
    error = Color(0xffffc1cb),
    errorContainer = Color(0xff39272f),
    onErrorContainer = Color(0xffffc1cb),
)

internal data class KeptJournalColors(val background: Color, val label: Color)

private val lightKeptJournal = KeptJournalColors(Color(0xffe8f5ec), Color(0xff21573b))
private val darkKeptJournal = KeptJournalColors(Color(0xff23352e), Color(0xffb6e4c6))

@Composable
internal fun keptJournalColors(): KeptJournalColors =
    if (isSystemInDarkTheme()) darkKeptJournal else lightKeptJournal

@Composable
internal fun GraphiteTheme(content: @Composable () -> Unit) {
    val window = colorResource(R.color.graphite_window)
    val dark = isSystemInDarkTheme()
    val colors = remember(dark, window) {
        if (dark) {
            darkGraphite.copy(background = window, onPrimary = window)
        } else {
            lightGraphite.copy(background = window, surfaceContainerLow = window)
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
