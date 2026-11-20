package io.github.vitalyostanin.pushmenot.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class UpdateInstaller(private val context: Context) {
    private val packages = context.packageManager
    private val installer = packages.packageInstaller
    private val download = UpdateDownload()

    fun refresh() {
        if (status.value == UpdateInstallStatus.PermissionRequired && packages.canRequestPackageInstalls()) {
            mutableStatus.value = UpdateInstallStatus.Idle
        }
        if (status.value != UpdateInstallStatus.Idle) return

        val pending = session(context) ?: return
        when {
            installed().longVersionCode >= pending.versionCode -> clear(context)
            pending.confirmationRequired || installer.getSessionInfo(pending.id) == null -> {
                // Android's confirmation intent cannot survive the app process.
                abandon(installer, pending.id)
                clear(context)
                mutableStatus.value = UpdateInstallStatus.Failed
            }
            else -> mutableStatus.value = UpdateInstallStatus.Installing
        }
    }

    suspend fun install(release: UpdateResult.Available) {
        if (status.value.busy || release.asset == null) return
        if (!packages.canRequestPackageInstalls()) {
            mutableStatus.value = UpdateInstallStatus.PermissionRequired
            return
        }
        mutableStatus.value = UpdateInstallStatus.Downloading(0)
        try {
            withContext(Dispatchers.IO) {
                val apk = File.createTempFile("update-", ".apk", context.cacheDir)
                try {
                    download.copy(release, apk) { mutableStatus.value = UpdateInstallStatus.Downloading(it) }
                    val candidate = verifyPackage(apk, release.version)
                    submit(apk, candidate.longVersionCode)
                } finally {
                    apk.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            if (status.value is UpdateInstallStatus.Downloading) mutableStatus.value = UpdateInstallStatus.Idle
            throw cancelled
        } catch (_: IOException) {
            mutableStatus.value = UpdateInstallStatus.Failed
        } catch (_: IllegalArgumentException) {
            mutableStatus.value = UpdateInstallStatus.Failed
        } catch (_: IllegalStateException) {
            mutableStatus.value = UpdateInstallStatus.Failed
        } catch (_: SecurityException) {
            mutableStatus.value = UpdateInstallStatus.Failed
        }
    }

    private fun installed(): PackageInfo = packages.getPackageInfo(context.packageName, signingFlags)

    private fun verifyPackage(apk: File, version: String): PackageInfo {
        val installed = installed()
        val candidate = packages.getPackageArchiveInfo(apk.path, signingFlags)
            ?: throw IOException("Update is not an Android package")
        val expectedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val actualSigners = candidate.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (
            candidate.packageName != context.packageName || candidate.versionName != version ||
            candidate.longVersionCode <= installed.longVersionCode ||
            expectedSigners.isEmpty() || actualSigners != expectedSigners
        ) {
            throw IOException("Update package, version or signing certificate does not match")
        }
        return candidate
    }

    private suspend fun submit(apk: File, versionCode: Long) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                withContext(Dispatchers.Main.immediate) {
                    val recorded = preferences(context).edit().putInt(SESSION_ID, id)
                        .putLong(VERSION_CODE, versionCode).putBoolean(CONFIRMATION_REQUIRED, false).commit()
                    if (!recorded) throw IOException("Cannot record update installation")
                    val callback = PendingIntent.getBroadcast(
                        context,
                        id,
                        Intent(context, UpdateInstallReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                    )
                    mutableStatus.value = UpdateInstallStatus.Installing
                    session.commit(callback.intentSender)
                    committed = true
                }
            }
        } finally {
            if (!committed) {
                abandon(installer, id)
                if (session(context)?.id == id) clear(context)
            }
        }
    }

    companion object {
        private const val SESSION_ID = "session_id"
        private const val VERSION_CODE = "version_code"
        private const val CONFIRMATION_REQUIRED = "confirmation_required"
        private val signingFlags = PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        private val mutableStatus = MutableStateFlow<UpdateInstallStatus>(UpdateInstallStatus.Idle)
        val status = mutableStatus.asStateFlow()
        private var confirmation: Intent? = null

        fun confirm(launch: (Intent) -> Boolean) {
            val prompt = confirmation ?: return
            if (launch(prompt)) mutableStatus.value = UpdateInstallStatus.Installing
        }

        fun receiveResult(context: Context, result: Intent) {
            val pending = session(context) ?: return
            if (result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != pending.id) return
            when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val prompt = result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    if (prompt == null) {
                        abandon(context.packageManager.packageInstaller, pending.id)
                        clear(context)
                        mutableStatus.value = UpdateInstallStatus.Failed
                    } else {
                        preferences(context).edit().putBoolean(CONFIRMATION_REQUIRED, true).commit()
                        confirmation = prompt
                        mutableStatus.value = UpdateInstallStatus.ConfirmationRequired
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    clear(context)
                    mutableStatus.value = UpdateInstallStatus.Idle
                }
                else -> {
                    clear(context)
                    mutableStatus.value = UpdateInstallStatus.Failed
                }
            }
        }

        private fun abandon(installer: PackageInstaller, id: Int) {
            try {
                installer.abandonSession(id)
            } catch (_: IllegalStateException) {
                // The session has already finished.
            } catch (_: SecurityException) {
                // Android has already removed the session.
            }
        }

        private fun clear(context: Context) {
            confirmation = null
            preferences(context).edit().clear().apply()
        }

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("app_update_install", Context.MODE_PRIVATE)

        private fun session(context: Context): PendingSession? {
            val stored = preferences(context)
            val id = stored.getInt(SESSION_ID, -1)
            if (id < 0) return null
            return PendingSession(id, stored.getLong(VERSION_CODE, -1), stored.getBoolean(CONFIRMATION_REQUIRED, false))
        }

        private data class PendingSession(val id: Int, val versionCode: Long, val confirmationRequired: Boolean)
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstaller.receiveResult(context, intent)
    }
}
