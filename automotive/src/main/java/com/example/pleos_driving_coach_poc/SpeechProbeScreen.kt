package com.example.pleos_driving_coach_poc

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.speech.SpeechProbeService

internal class SpeechProbeScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1_000)
        }
    }

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { handler.post(refresh) }
    override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(refresh) }

    override fun onGetTemplate(): ListTemplate {
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("주행 중 음성 시험 시작")
                .addText("준비 후 10초 뒤 첫 안내 · 완료 후 10초 간격으로 총 3회")
                .addText("Pleos ON_DEVICE · 다른 앱/주행 상태로 전환해 소리를 확인")
                .setOnClickListener {
                    try {
                        carContext.startForegroundService(Intent(carContext, SpeechProbeService::class.java))
                    } catch (error: RuntimeException) {
                        SpeechProbeService.report("서비스 시작 실패: ${error.message}")
                    }
                    invalidate()
                }.build())
            .addItem(Row.Builder().setTitle("시험 상태").addText(SpeechProbeService.status).build())
            .addItem(Row.Builder().setTitle("마지막 재생 결과")
                .addText(SpeechProbeService.result)
                .addText("시작/완료 콜백과 속도는 SpeechProbe 로그 · 실제 소리는 청취 확인")
                .build())
            .addItem(Row.Builder().setTitle("음성 시험 종료")
                .setOnClickListener {
                    carContext.stopService(Intent(carContext, SpeechProbeService::class.java))
                    invalidate()
                }.build())
            .build()
        return ListTemplate.Builder().setTitle("음성 안내 확인").setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("목소리 비교")
                .setOnClickListener { screenManager.push(VoiceProbeScreen(carContext)) }.build()).build())
            .setSingleList(items).build()
    }
}
