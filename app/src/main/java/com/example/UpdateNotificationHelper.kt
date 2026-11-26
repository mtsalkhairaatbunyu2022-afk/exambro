package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Helper to display persistent, ongoing system notifications when an update is available.
 * The notification cannot be dismissed by swiping ("waktu yang cukup lama")
 * and will only be removed once the user updates the application to the latest version.
 */
object UpdateNotificationHelper {
    const val CHANNEL_ID = "exambro_app_updates"
    const val NOTIFICATION_ID = 2024

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Pembaruan Aplikasi"
            val descriptionText = "Pemberitahuan pembaruan versi Exambro CBT MTs Alkhairaat Bunyu"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun showUpdateNotification(context: Context, info: RemoteVersionInfo) {
        createNotificationChannel(context)

        // Intent when clicking the notification body: bring MainActivity to front and show update prompt
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("SHOW_UPDATE_DIALOG", true)
        }
        val pendingOpenApp = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent for the direct "Perbarui Sekarang" action button
        val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingDownload = PendingIntent.getActivity(
            context,
            1,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Pembaruan Tersedia: v${info.versionName}"
        val shortContent = "Versi baru telah dirilis. Ketuk untuk memperbarui aplikasi."
        val bigText = "Aplikasi Exambro MTs Alkhairaat Bunyu versi baru v${info.versionName} (Build ${info.versionCode}) siap dipasang.\n\nCatatan:\n${info.releaseNotes}\n\nNotifikasi ini akan tetap aktif di HP Anda sampai aplikasi berhasil diperbarui."

        val largeIconBitmap = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .apply {
                if (largeIconBitmap != null) {
                    setLargeIcon(largeIconBitmap)
                }
            }
            .setContentTitle(title)
            .setContentText(shortContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true) // Waktu yang cukup lama: tidak dapat dihapus dengan swipe biasa
            .setAutoCancel(false) // Tetap aktif sampai aplikasi diperbarui
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.stat_sys_download,
                "Perbarui Sekarang",
                pendingDownload
            )
            .addAction(
                android.R.drawable.ic_menu_info_details,
                "Buka Aplikasi",
                pendingOpenApp
            )

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelUpdateNotification(context: Context) {
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
