package com.example.pleos_driving_coach_poc

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.speech.SpeechProbeService

internal class VoiceProbeScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
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
        SpeechProbeService.KOREAN_SPEAKERS.forEach { speakerType ->
            items.addItem(Row.Builder().setTitle("$speakerType · 눌러 재생")
                .addText(SpeechProbeService.voiceResult(speakerType))
                .setOnClickListener {
                    try {
                        carContext.startForegroundService(Intent(carContext, SpeechProbeService::class.java)
                            .putExtra(SpeechProbeService.EXTRA_VOICE_TRIAL, true)
                            .putExtra(SpeechProbeService.EXTRA_SPEAKER_TYPE, speakerType))
                    } catch (error: RuntimeException) {
                        SpeechProbeService.report("목소리 시험 시작 실패: ${error.message}")
                    }
                    invalidate()
                }.build())
        }
        items.addItem(Row.Builder().setTitle("시험 상태").addText(SpeechProbeService.status)
            .addText("한국어 · 준비 후 2초 뒤 같은 문장 1회 · 완료 후 다음 목소리 선택")
            .build())
        items.addItem(Row.Builder().setTitle("음성 시험 종료")
            .setOnClickListener {
                carContext.stopService(Intent(carContext, SpeechProbeService::class.java))
                invalidate()
            }.build())
        return ListTemplate.Builder().setTitle("한국어 목소리 4종 비교").setHeaderAction(Action.BACK)
            .setSingleList(items.build()).build()
    }
}
