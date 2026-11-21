package io.github.vitalyostanin.pushmenot.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class FixtureRuleFiles : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        if (uri.lastPathSegment == "received.pushmenot") {
            return ParcelFileDescriptor.open(File(checkNotNull(context).filesDir, "received.pushmenot"),
                ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (uri.lastPathSegment != "rules.pushmenot") throw FileNotFoundException()
        return openPipeHelper(uri, MIME, null, RULES) { output, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(checkNotNull(data).toByteArray(Charsets.UTF_8)) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val MIME = "application/vnd.pushmenot.rules+json"
        private const val RULES = """{"version":1,"rules":[{"id":"opened-external-rule","revision":1,"packageName":"example.shop","action":"HIDE","title":{"value":"Synthetic offer"}}]}"""
    }
}
