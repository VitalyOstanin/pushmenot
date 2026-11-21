package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.updates.UpdateInstallStatus
import io.github.vitalyostanin.pushmenot.updates.UpdateResult

@Composable
internal fun UpdateSettings(state: ScreenState, callbacks: ScreenCallbacks) {
    val result = state.updateResult
    val status = state.updateInstallStatus
    val release = result as? UpdateResult.Available
    Panel {
        Title(stringResource(R.string.updates))
        Help(stringResource(R.string.installed_version, state.installedVersion))
        val text = when {
            status is UpdateInstallStatus.Downloading && release != null ->
                stringResource(R.string.update_downloading, release.version, status.percent)
            status == UpdateInstallStatus.Installing -> stringResource(R.string.update_installing)
            status == UpdateInstallStatus.ConfirmationRequired -> stringResource(R.string.update_confirm)
            status == UpdateInstallStatus.PermissionRequired && release != null ->
                stringResource(R.string.update_install_permission)
            status == UpdateInstallStatus.Failed && release != null -> stringResource(R.string.update_install_failed)
            else -> updateCheckText(result)
        }
        val available = release != null && status == UpdateInstallStatus.Idle
        Text(
            text,
            if (status is UpdateInstallStatus.Downloading) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = when {
                available -> MaterialTheme.colorScheme.primary
                status == UpdateInstallStatus.Failed || result == UpdateResult.Failed -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (available) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        if (status is UpdateInstallStatus.Downloading) {
            LinearProgressIndicator(progress = { status.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        when {
            status == UpdateInstallStatus.ConfirmationRequired ->
                ActionButton(R.string.confirm_update_android, callbacks.confirmUpdate, tag = "confirm_update")
            release?.asset != null && !status.busy -> when (status) {
                UpdateInstallStatus.PermissionRequired ->
                    ActionButton(R.string.open_update_android_settings, callbacks.openInstallPermission, tag = "install_update_permission")
                UpdateInstallStatus.Failed ->
                    ActionButton(R.string.retry_update_install, callbacks.installUpdate, tag = "install_update")
                else -> ActionButton(R.string.install_update, callbacks.installUpdate, tag = "install_update")
            }
        }
        if (release != null && !status.busy) {
            ActionButton(R.string.open_update_release, { callbacks.openUpdateRelease(release) }, primary = false, tag = "open_update_release")
        }
        ActionButton(
            R.string.check_for_updates,
            callbacks.checkForUpdates,
            primary = false,
            enabled = result != UpdateResult.Checking && !status.busy,
            tag = "check_for_updates",
        )
    }
}

@Composable
private fun updateCheckText(result: UpdateResult): String = when (result) {
    UpdateResult.Idle -> stringResource(R.string.update_check_idle)
    UpdateResult.Checking -> stringResource(R.string.update_check_running)
    UpdateResult.Current -> stringResource(R.string.update_current)
    UpdateResult.NoPublishedRelease -> stringResource(R.string.update_release_unavailable)
    UpdateResult.Failed -> stringResource(R.string.update_check_failed)
    is UpdateResult.Available -> stringResource(R.string.update_available, result.version)
}
