package com.example.pleos_driving_coach_poc.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.car.app.activity.CarAppActivity
import com.example.pleos_driving_coach_poc.R
import kotlin.math.min

internal class NotificationProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var activeMode: String? = null

    override fun onCreate() {
        super.onCreate()
        NotificationProbe.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode")
        if (mode == null || mode !in listOf(NotificationProbe.STATUS, NotificationProbe.HEADS_UP, NotificationProbe.OVERLAY)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeMode?.let { NotificationProbe.report(it, "이전 시험 종료 · 새 시험으로 전환") }
        activeMode = mode
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        val progress = Notification.Builder(this, NotificationProbe.SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("알림 표시 시험 실행 중")
            .setContentText("5초 후 시험 · 잠시 후 자동 종료")
            .setOngoing(true)
            .build()
        try {
            startForeground(NotificationProbe.SERVICE_ID, progress, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } catch (error: RuntimeException) {
            NotificationProbe.report(mode, "시험 서비스 실행 실패: ${error.message}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.postDelayed({
            try {
                if (mode == NotificationProbe.OVERLAY) showOverlay() else postNotification(mode)
            } catch (error: RuntimeException) {
                NotificationProbe.report(mode, "표시 실패: ${error.message}")
            }
        }, 5_000)
        handler.postDelayed({
            if (mode == NotificationProbe.OVERLAY && overlay != null) {
                removeOverlay()
                NotificationProbe.report(mode, "오버레이 시험 종료 · 실제 위치/표시는 육안 확인 필요")
            }
            stopSelf(startId)
        }, 13_000)
        return START_NOT_STICKY
    }

    private fun postNotification(mode: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()) {
            NotificationProbe.report(mode, "앱 알림 차단 상태")
            return
        }
        val high = mode == NotificationProbe.HEADS_UP
        val channelId = if (high) NotificationProbe.HEADS_UP_CHANNEL else NotificationProbe.STATUS_CHANNEL
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            NotificationProbe.report(mode, "알림 채널 차단 상태")
            return
        }
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, CarAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (high) "내비게이션 팝업 · 표시 시험" else "운전 코칭 · 상태바 표시 시험")
            .setContentText(if (high) "시험용 경로 안내입니다. 실제 주행 안내가 아닙니다."
                else "시험 메시지 · 부드러운 출발과 감속을 연습해 보세요.")
            // AAOS limits heads-up notifications from ordinary apps to specific categories.
            // NAVIGATION is used only to probe display support, not to classify coaching alerts.
            .setCategory(if (high) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        val id = if (high) NotificationProbe.HEADS_UP_ID else NotificationProbe.STATUS_ID
        manager.notify(id, notification)
        NotificationProbe.report(mode, "알림 등록 요청 완료")
        handler.postDelayed({
            try {
                val active = manager.activeNotifications.any { it.id == id }
                NotificationProbe.report(mode, "시스템 등록=$active · 채널 중요도=${manager.getNotificationChannel(channelId)?.importance} · 실제 표시 육안 확인 필요")
            } catch (error: RuntimeException) {
                NotificationProbe.report(mode, "알림 등록 확인 실패: ${error.message}")
            }
        }, 300)
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            NotificationProbe.report(NotificationProbe.OVERLAY, "다른 앱 위에 표시 권한 없음")
            return
        }
        val manager = getSystemService(WindowManager::class.java)
        val card = TextView(this).apply {
            setText(R.string.overlay_probe_message)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 48, 62))
                cornerRadius = dp(16).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val params = WindowManager.LayoutParams(
            min(dp(340), metrics.bounds.width() / 2),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(24)
            y = insets.top + dp(24)
            alpha = 0.8f
        }
        manager.addView(card, params)
        overlay = card
        NotificationProbe.report(NotificationProbe.OVERLAY, "오버레이 표시 요청 성공 · 우측 상단 위치 육안 확인 필요")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun removeOverlay() {
        overlay?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    getSystemService(WindowManager::class.java).removeViewImmediate(view)
                }
            } finally {
                overlay = null
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            removeOverlay()
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
