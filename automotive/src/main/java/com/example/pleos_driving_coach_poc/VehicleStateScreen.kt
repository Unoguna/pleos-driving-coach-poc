package com.example.pleos_driving_coach_poc

import android.car.Car
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.vehicle.SpeedReader
import com.example.pleos_driving_coach_poc.vehicle.VehicleStateReader
import java.util.Locale
import kotlin.math.abs

internal class VehicleStateScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val values = mutableMapOf<Int, String>()
    private val diagnostics = mutableMapOf<Int, String>()
    private var stoppedState = "정차 여부 미확인"
    private var speedStatus = "속도 권한 확인 필요 · 정차 추정 기준 |속도| ≤ 0.1 m/s"
    private val refreshDisplay = Runnable { invalidate() }
    private val stateReader = VehicleStateReader(context) { propertyId, value, status ->
        values[propertyId] = value
        diagnostics[propertyId] = status
        scheduleRefresh()
    }
    private val speedReader = SpeedReader(
        context,
        onValue = { speed ->
            stoppedState = when {
                speed == null -> "정차 여부 미확인"
                abs(speed) <= 0.1f -> "정차 추정"
                else -> "이동 중"
            }
            if (speed != null) {
                stoppedState += String.format(Locale.ROOT, " · %.2f m/s", speed)
            }
            scheduleRefresh()
        },
        onStatus = {
            speedStatus = "$it · 정차 추정 기준 |속도| ≤ 0.1 m/s"
            scheduleRefresh()
        }
    )

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        stateReader.start()
        if (hasSpeedPermission()) speedReader.start()
        invalidate()
    }

    private fun hasSpeedPermission() =
        carContext.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    private fun requestSpeedPermission() {
        carContext.requestPermissions(listOf(Car.PERMISSION_SPEED)) { _, _ ->
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@requestPermissions
            if (hasSpeedPermission()) speedReader.start()
            else speedStatus = "속도 권한 거부 · 정차 여부 미확인"
            invalidate()
        }
    }

    private fun scheduleRefresh() {
        if (!handler.hasCallbacks(refreshDisplay)) handler.postDelayed(refreshDisplay, 500)
    }

    override fun onGetTemplate(): PaneTemplate {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("정차 여부 · 속도 기반 추정")
                .addText(stoppedState).addText(speedStatus.replace("\n", " · ")).build())
        VehicleStateReader.labels.forEach { (propertyId, title) ->
            pane.addRow(Row.Builder().setTitle(title)
                .addText(values[propertyId] ?: "수신값 없음")
                .addText(diagnostics[propertyId] ?: "조회·구독 대기").build())
        }
        if (!hasSpeedPermission()) {
            pane.addAction(Action.Builder().setTitle("속도 권한 허용")
                .setOnClickListener(ParkedOnlyOnClickListener.create { requestSpeedPermission() }).build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("정차·주차 확인")
            .setHeaderAction(Action.BACK).build()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(refreshDisplay)
        try {
            stateReader.close()
        } finally {
            speedReader.close()
        }
    }
}
