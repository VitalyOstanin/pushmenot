package io.github.vitalyostanin.pushmenot.fixture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.ClipData
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.util.Base64

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("notification_target", false)) {
            setContentView(TextView(this).apply { text = "Opened the original notification target" })
            return
        }
        val delivery = intent.getStringExtra("rule_delivery")
        if (delivery != null) {
            val uri = Uri.parse("content://test.pushmenot.rules/rules.pushmenot")
            val type = if (delivery == "view") "application/octet-stream" else FixtureRuleFiles.MIME
            val request = if (delivery == "view") {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            } else {
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            }
            // Keep Android's implicit filter matching while avoiding a chooser in the fixture.
            request.setPackage("io.github.vitalyostanin.pushmenot")
            request.clipData = ClipData.newRawUri("Rules", uri)
            request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(request)
        }
        finish()
    }
}

class FixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            "io.github.vitalyostanin.pushmenot.fixture.RESET" -> manager.cancelAll()
            "io.github.vitalyostanin.pushmenot.fixture.ACTIVE" -> {
                resultData = manager.activeNotifications.map { it.id }.sorted().joinToString(",")
            }
            "io.github.vitalyostanin.pushmenot.fixture.POST" -> {
                val encoded = intent.getStringExtra("title64") ?: return
                val title = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
                val id = intent.getIntExtra("id", 0)
                manager.createNotificationChannel(
                    NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT),
                )
                val notification = Notification.Builder(context, "orders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText("Synthetic test notification")
                when (intent.getStringExtra("style")) {
                    "target" -> notification.setContentIntent(PendingIntent.getActivity(
                        context, id, Intent(context, FixtureActivity::class.java).putExtra("notification_target", true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ))
                    "ongoing" -> notification.setOngoing(true)
                    "messages" -> {
                        val self = Person.Builder().setName("Self").build()
                        val sender = Person.Builder().setName("Synthetic sender").build()
                        notification.setStyle(Notification.MessagingStyle(self)
                            .addMessage("First synthetic message", 1L, sender)
                            .addMessage("Second synthetic message", 2L, sender))
                    }
                    "image" -> {
                        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                        image.eraseColor(Color.YELLOW)
                        notification.setStyle(Notification.BigPictureStyle().bigPicture(image))
                    }
                }
                manager.notify(id, notification.build())
            }
        }
    }
}
