package com.fengkao.timetable.data.electricity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fengkao.timetable.R
import com.fengkao.timetable.notify.NoteReminders
import com.fengkao.timetable.ui.electricity.ElectricityActivity
import java.util.Locale

object ElectricityNotifier {
    const val CHANNEL_ID = "electricity_alert"
    const val CHANNEL_NAME = "宿舍电费提醒"

    private const val NOTIFICATION_ID = 7401
    private const val REQUEST_CODE = 7402

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "宿舍剩余电量低于设定阈值时提醒"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyLow(context: Context, rooms: List<ElectricityRoom>): Boolean {
        if (rooms.isEmpty() || !NoteReminders.canNotify(context)) return false
        ensureChannel(context)
        val lines = rooms.take(5).map { room ->
            "· ${room.roomName}：剩余 ${formatBalance(room.remainingKwh)} 度电"
        }.toMutableList()
        if (rooms.size > 5) lines += "· 另有 ${rooms.size - 5} 个房间"
        val title = if (rooms.size == 1) "宿舍电费不足" else "${rooms.size} 个宿舍电费不足"
        val intent = Intent(context, ElectricityActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notice)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.isSuccess
    }

    private fun formatBalance(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.CHINA, "%.2f", value)
}
