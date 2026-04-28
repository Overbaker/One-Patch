package com.ccg.screenblocker.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ccg.screenblocker.MainActivity
import com.ccg.screenblocker.R
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.service.OverlayService

/**
 * 前台服务通知工具。
 *
 * - 通知渠道：overlay_block_channel
 * - 通知 ID：1001
 * - 包含「快捷停止」与「点击返回」两个 PendingIntent
 */
object NotificationHelper {

    const val CHANNEL_ID = "overlay_block_channel"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun buildRunning(
        context: Context,
        area: BlockArea,
        manualBypass: Boolean = false,
        autoBypass: Boolean = false
    ): Notification {
        val baseText = context.getString(R.string.notif_text, area.widthPx, area.heightPx)
        val subText = bypassToken(manualBypass, autoBypass)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(baseText)
            .apply { if (subText != null) setSubText(subText) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(buildOpenAppIntent(context))
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notif_action_stop),
                buildStopActionIntent(context)
            )
            .build()
    }

    private fun bypassToken(manualBypass: Boolean, autoBypass: Boolean): String? = when {
        manualBypass -> "已暂停（手动）"
        autoBypass -> "已暂停（自动）"
        else -> null
    }

    private fun buildOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
    }

    private fun buildStopActionIntent(context: Context): PendingIntent {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        return PendingIntent.getService(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private const val REQUEST_OPEN_APP = 1
    private const val REQUEST_STOP = 2
}
