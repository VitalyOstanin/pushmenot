package io.github.vitalyostanin.pushmenot.fixture

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.json.JSONObject

class FixtureShareReceiver : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val name = contentResolver.query(input, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            check(it.moveToFirst())
            it.getString(0)
        }
        check(name == "push-me-not-rules.pushmenot")
        val limit = 2 * 1024 * 1024
        val bytes = checkNotNull(contentResolver.openInputStream(input)).use { stream ->
            val buffer = ByteArray(limit)
            var length = 0
            while (length < limit) {
                val count = stream.read(buffer, length, limit - length)
                if (count == -1) break
                length += count
            }
            check(length < limit)
            buffer.copyOf(length)
        }
        val document = JSONObject(bytes.toString(Charsets.UTF_8))
        check(document.getJSONArray("rules").length() == 1)
        val packages = document.getJSONArray("enabledApps")
        check((0 until packages.length()).any { packages.getString(it) == "example.shared.app" })
        File(filesDir, "received.pushmenot").writeBytes(bytes)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Rules and filtering apps received" })
            addView(Button(context).apply {
                text = "Open received rules"
                setOnClickListener {
                    val uri = Uri.parse("content://test.pushmenot.rules/received.pushmenot")
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, FixtureRuleFiles.MIME)
                        setPackage("io.github.vitalyostanin.pushmenot")
                        clipData = ClipData.newRawUri("Rules", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    finish()
                }
            })
        })
    }
}
