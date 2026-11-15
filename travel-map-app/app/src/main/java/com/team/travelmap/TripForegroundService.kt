package com.team.travelmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 담당: 2번(차량·내비·통합). 여행 안내 중(Guiding·Arrived)에만 앱 프로세스를 유지한다.
 *
 * 출발하면 내비 화면이 앞에 뜨고 우리 앱은 뒤로 간다. 그 사이 시스템이 메모리를 정리하면서 프로세스를 끝내면
 * 오디오 스토리·도착 인증·경유지 구간 안내가 끊긴다. 안내 중에는 Foreground Service 로 이를 막는다.
 * 이 서비스는 일을 하지 않고 프로세스를 지키기만 한다. 실제 처리는 Application 의 AppContainer 가 한다.
 *
 * 종류는 mediaPlayback(가는 길 오디오 스토리 재생). 시작·종료는 TravelMapApp 이 여행 상태를 보고 정한다.
 */
class TripForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "startForeground 실패", it)
            stopSelf()
        }
        // 시스템이 서비스를 끝내도 다시 띄우지 않는다. 앱이 다시 시작되면 TripCoordinator 가 여행을 복원한다.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "여행 안내", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("여행 안내 중이에요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TripService"
        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 1

        /** 안내 중이면 시작, 아니면 종료. 앱이 화면에 없을 때 시작이 막히면(Android 12+) 로그만 남긴다. */
        fun update(context: Context, guiding: Boolean) {
            val intent = Intent(context, TripForegroundService::class.java)
            if (!guiding) {
                context.stopService(intent)
                return
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Log.w(TAG, "서비스 시작 실패(앱이 뒤에 있음)", it) }
        }
    }
}
