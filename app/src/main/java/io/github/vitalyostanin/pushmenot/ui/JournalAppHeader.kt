package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.sources.JournalApps

private val appIconSize = 32.dp

@Composable
internal fun rememberJournalApps(foreground: Boolean): JournalApps {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pixels = with(LocalDensity.current) { appIconSize.roundToPx().coerceIn(1, 128) }
    return remember(context, configuration, pixels, foreground) { JournalApps(context.packageManager, pixels) }
}

@Composable
internal fun JournalAppHeader(
    apps: JournalApps,
    packageName: String?,
    knownLabel: String?,
    time: String? = null,
    openSource: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    key(apps, packageName) {
        val app by produceState(packageName?.let(apps::cached)) {
            if (value == null && packageName != null) value = apps.load(packageName)
        }
        val identity = app
        val icon = identity?.icon
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sourceAction = if (openSource == null) Modifier else {
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = openSource)
            }
            Row(
                Modifier.weight(1f).then(sourceAction),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = remember(icon) { icon.asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier.size(appIconSize).testTag("journal_app_icon"),
                    )
                } else {
                    Canvas(
                        Modifier.size(appIconSize)
                            .background(colors.secondaryContainer, RoundedCornerShape(8.dp))
                            .testTag("journal_app_placeholder"),
                    ) {
                        val inset = size.width * .30f
                        drawRoundRect(
                            color = colors.onSecondaryContainer,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - 2 * inset, size.height - 2 * inset),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                }
                Text(
                    identity?.label ?: knownLabel ?: packageName ?: stringResource(R.string.notification),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                )
            }
            if (time != null) Text(time, color = colors.onSurfaceVariant, fontSize = 13.sp)
        }
        if (identity != null && identity.label == null && knownLabel == null) {
            Help(stringResource(R.string.journal_app_identity_unavailable))
        }
    }
}

@Composable
internal fun JournalNotificationTitle(title: String?) {
    title?.takeIf(String::isNotBlank)?.let {
        Text(it, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    }
}
