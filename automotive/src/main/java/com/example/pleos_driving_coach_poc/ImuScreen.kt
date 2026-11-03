package com.example.pleos_driving_coach_poc

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.vehicle.ImuReader
import com.example.pleos_driving_coach_poc.vehicle.ImuReading
import java.util.Locale

internal class ImuScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var acceleration = "이벤트 대기"
    private var gyroscope = "이벤트 대기"
    private var accelerationStatus = "연결 대기"
    private var gyroscopeStatus = "연결 대기"
    private val refreshDisplay = Runnable { invalidate() }
    private val reader = ImuReader(
        context,
        onReading = { channel, reading ->
            val unit = if (channel == ImuReader.ACCELERATION) "m/s²" else "rad/s"
            val values = formatValues(reading, unit)
            val interval = reading.intervalMs?.let {
                String.format(Locale.ROOT, "%.1f ms (약 %.1f Hz)", it, 1000.0 / it)
            } ?: "간격 측정 중"
            val detail = "${reading.name} · $interval · accuracy=${reading.accuracy}"
            if (channel == ImuReader.ACCELERATION) {
                acceleration = values
                accelerationStatus = detail
            } else {
                gyroscope = values
                gyroscopeStatus = detail
            }
            scheduleRefresh()
        },
        onStatus = { channel, message ->
            if (channel == ImuReader.ACCELERATION) accelerationStatus = message
            else gyroscopeStatus = message
            scheduleRefresh()
        }
    )

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        reader.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        reader.stop()
        handler.removeCallbacks(refreshDisplay)
    }

    private fun formatValues(reading: ImuReading, unit: String): String {
        if (reading.values.size < 3) return "XYZ 값 부족"
        return String.format(Locale.ROOT, "X %.3f · Y %.3f · Z %.3f %s",
            reading.values[0], reading.values[1], reading.values[2], unit)
    }

    private fun scheduleRefresh() {
        if (!handler.hasCallbacks(refreshDisplay)) {
            handler.postDelayed(refreshDisplay, 500)
        }
    }

    override fun onGetTemplate(): PaneTemplate {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("가속도 · 중력 포함")
                .addText(acceleration).addText(accelerationStatus).build())
            .addRow(Row.Builder().setTitle("자이로 · 각속도")
                .addText(gyroscope).addText(gyroscopeStatus).build())
            .addRow(Row.Builder().setTitle("센서 접근 검증")
                .addText("50 Hz 요청 · 로그에 센서 시각과 비보정 편향값 기록")
                .addText("수직축·중력 보정과 실제 차량 충격 감지는 별도 검증 필요").build())
            .build()
        return PaneTemplate.Builder(pane).setTitle("IMU 확인").setHeaderAction(Action.BACK).build()
    }
}
