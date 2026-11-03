package com.example.pleos_driving_coach_poc.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

internal object NotificationProbe {
    const val STATUS = "status"
    const val HEADS_UP = "heads_up"
    const val OVERLAY = "overlay"
    const val SERVICE_CHANNEL = "notification_probe_service"
    const val STATUS_CHANNEL = "coach_status_probe"
    const val HEADS_UP_CHANNEL = "coach_high_probe"
    const val SERVICE_ID = 200
    const val STATUS_ID = 201
    const val HEADS_UP_ID = 202
    private val results = mutableMapOf<String, String>()

    fun status(mode: String) = results[mode] ?: "미실행 · 5초 후 표시"

    fun report(mode: String, message: String) {
        results[mode] = message
        Log.i("NotificationProbe", "mode=$mode $message")
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(SERVICE_CHANNEL, "표시 시험 실행 상태", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(STATUS_CHANNEL, "운전 코칭 상태바 시험", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(HEADS_UP_CHANNEL, "운전 코칭 고중요도 시험", NotificationManager.IMPORTANCE_HIGH)
        ))
    }

    fun start(context: Context, mode: String) {
        if (mode == OVERLAY && !Settings.canDrawOverlays(context)) {
            report(mode, "다른 앱 위에 표시 권한 필요")
            return
        }
        if (mode != OVERLAY &&
            !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            report(mode, "알림 권한 또는 앱 알림 설정 확인 필요")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, NotificationProbeService::class.java).putExtra("mode", mode)
            )
            report(mode, "5초 후 표시 예정 · 다른 앱으로 전환해 확인")
        } catch (error: RuntimeException) {
            report(mode, "시험 시작 실패: ${error.message}")
            Log.w("NotificationProbe", "START failed", error)
        }
    }

    fun clear(context: Context) {
        context.stopService(Intent(context, NotificationProbeService::class.java))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(STATUS_ID)
        manager.cancel(HEADS_UP_ID)
        listOf(STATUS, HEADS_UP, OVERLAY).forEach { report(it, "시험 종료 · 다시 실행 가능") }
    }
}
