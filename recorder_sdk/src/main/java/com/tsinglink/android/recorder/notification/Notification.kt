package com.tsinglink.android.recorder.notification

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.tsinglink.android.recorder.CHANNEL_ID_RECORDING
import timber.log.Timber


fun createNotificationChannel(context:Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        createNotificationChannel(
            context,
            CHANNEL_ID_RECORDING,
            CHANNEL_ID_RECORDING,
            NotificationManager.IMPORTANCE_HIGH
        )
    }
}

@TargetApi(Build.VERSION_CODES.O)
private fun createNotificationChannel(context:Context, channelId: String,
                                      channelName: String, importance: Int) {
    val channel = NotificationChannel(channelId, channelName, importance)
    channel.setShowBadge(false)
    channel.setSound(null, null)
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (notificationManager == null) {
        Timber.e( "Create Notification Channel Error!")
        return
    }
    notificationManager.createNotificationChannel(channel)
}