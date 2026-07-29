package com.example.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R

const val AGENTIC_LOOP_CHANNEL_ID = "ollamadev_agentic_loop"
const val AGENTIC_LOOP_NOTIFICATION_ID = 1

/**
 * Creates the notification channel for background agentic-loop tasks.
 * Safe to call repeatedly; the system ignores subsequent calls for the same channel.
 */
fun createAgenticNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        AGENTIC_LOOP_CHANNEL_ID,
        "Agentic Loop",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Persistent notifications for OllamaDev agentic-loop tasks running in the background."
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}

/**
 * Builds the initial foreground notification shown while a background agentic-loop task runs.
 */
fun buildAgenticLoopNotification(
    context: Context,
    title: String = "Agentic loop running",
    content: String = "A swarm task is executing in the background..."
): android.app.Notification =
    NotificationCompat.Builder(context, AGENTIC_LOOP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(title)
        .setContentText(content)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

/**
 * Updates the running notification with the latest status text.
 */
fun updateAgenticLoopNotification(context: Context, content: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, AGENTIC_LOOP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Agentic loop running")
        .setContentText(content)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()
    manager.notify(AGENTIC_LOOP_NOTIFICATION_ID, notification)
}

/**
 * Finalizes the notification when the background task completes or fails.
 */
fun finalizeAgenticLoopNotification(context: Context, success: Boolean, content: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val title = if (success) "Agentic loop complete" else "Agentic loop failed"
    val notification = NotificationCompat.Builder(context, AGENTIC_LOOP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(title)
        .setContentText(content)
        .setOngoing(false)
        .setAutoCancel(true)
        .build()
    manager.notify(AGENTIC_LOOP_NOTIFICATION_ID, notification)
}
