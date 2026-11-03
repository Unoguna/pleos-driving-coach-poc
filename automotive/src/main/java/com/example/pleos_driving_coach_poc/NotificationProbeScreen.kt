package com.example.pleos_driving_coach_poc

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.core.net.toUri
import com.example.pleos_driving_coach_poc.notification.NotificationProbe

internal class NotificationProbeScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var settingsResult = ""
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.post(refresh)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(refresh)
    }

    private fun requestNotificationPermission() {
        if (carContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, carContext.packageName))
        } else {
            carContext.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) { _, _ ->
                if (lifecycle.currentState != Lifecycle.State.DESTROYED) invalidate()
            }
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            carContext.applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settingsResult = "설정에서 허용한 뒤 앱으로 돌아와 주세요."
        } catch (error: RuntimeException) {
            settingsResult = "설정 화면 열기 실패: ${error.message}"
        }
        invalidate()
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${carContext.packageName}".toUri()),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        for (intent in intents) {
            try {
                carContext.applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                settingsResult = "설정에서 허용한 뒤 앱으로 돌아와 주세요."
                invalidate()
                return
            } catch (error: ActivityNotFoundException) {
                // Some automotive images omit this Android settings activity.
            } catch (error: RuntimeException) {
                settingsResult = "오버레이 권한 설정 접근 실패: ${error.message}"
                invalidate()
                return
            }
        }
        settingsResult = "현재 Pleos 이미지에 오버레이 권한 설정 화면이 없습니다."
        NotificationProbe.report(NotificationProbe.OVERLAY, "권한 미허용 · 권한 설정 화면 미제공")
        invalidate()
    }

    override fun onGetTemplate(): ListTemplate {
        val notificationsAllowed = carContext.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val overlaysAllowed = Settings.canDrawOverlays(carContext)
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("알림 권한 · $notificationsAllowed")
                .addText("눌러 권한 요청 또는 앱 알림 설정 열기")
                .setOnClickListener(ParkedOnlyOnClickListener.create { requestNotificationPermission() }).build())
            .addItem(Row.Builder().setTitle("다른 앱 위에 표시 · $overlaysAllowed")
                .addText(settingsResult.ifEmpty { "눌러 시스템 설정에서 허용" })
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    openOverlaySettings()
                }).build())
            .addItem(Row.Builder().setTitle("상태바 알림 시험")
                .addText(NotificationProbe.status(NotificationProbe.STATUS))
                .setOnClickListener {
                    NotificationProbe.start(carContext, NotificationProbe.STATUS)
                    invalidate()
                }.build())
            .addItem(Row.Builder().setTitle("내비게이션 팝업 시험")
                .addText(NotificationProbe.status(NotificationProbe.HEADS_UP))
                .addText("HIGH + NAVIGATION · 5초 후 표시 시험")
                .setOnClickListener {
                    NotificationProbe.start(carContext, NotificationProbe.HEADS_UP)
                    invalidate()
                }.build())
            .addItem(Row.Builder().setTitle("우측 상단 플로팅 시험")
                .addText(NotificationProbe.status(NotificationProbe.OVERLAY))
                .setOnClickListener {
                    NotificationProbe.start(carContext, NotificationProbe.OVERLAY)
                    invalidate()
                }.build())
            .addItem(Row.Builder().setTitle("시험 종료 및 알림 정리")
                .addText("표시 시험은 한 번에 하나씩 실행 · 오버레이 8초 후 자동 닫힘")
                .setOnClickListener {
                    NotificationProbe.clear(carContext)
                    invalidate()
                }.build())
            .build()
        return ListTemplate.Builder().setSingleList(items).setTitle("알림·플로팅 확인")
            .setHeaderAction(Action.BACK).build()
    }
}
