package io.github.vitalyostanin.pushmenot.updates

sealed interface UpdateInstallStatus {
    data object Idle : UpdateInstallStatus
    data object PermissionRequired : UpdateInstallStatus
    data class Downloading(val percent: Int) : UpdateInstallStatus
    data object Installing : UpdateInstallStatus
    data object ConfirmationRequired : UpdateInstallStatus
    data object Failed : UpdateInstallStatus

    val busy: Boolean
        get() = this is Downloading || this == Installing || this == ConfirmationRequired
}
