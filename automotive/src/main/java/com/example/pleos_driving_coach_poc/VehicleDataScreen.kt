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
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.vehicle.BrakeReader
import com.example.pleos_driving_coach_poc.vehicle.SteeringAngleReader
import com.example.pleos_driving_coach_poc.vehicle.SpeedReader
import java.util.Locale

internal class VehicleDataScreen(context: CarContext) : Screen(context),
    DefaultLifecycleObserver {
    private var status = "정차 상태에서 차량 속도 권한을 허용해 주세요."

    private val handler = Handler(Looper.getMainLooper())
    private var latestSpeed = "대기 중"
    private var displayedSpeed = latestSpeed
    private var latestSteering = "대기 중"
    private var displayedSteering = latestSteering
    private var steeringStatus = "연결 대기"
    private var displayedSteeringStatus = steeringStatus
    private var latestBrake = "대기 중"
    private var displayedBrake = latestBrake
    private var brakeStatus = "연결 대기"
    private var displayedBrakeStatus = brakeStatus
    private val brakeReader = BrakeReader(
        context,
        onValue = { pressed ->
            latestBrake = when (pressed) {
                true -> "밟음"
                false -> "뗌"
                null -> "수신 불가"
            }
            scheduleRefresh()
        },
        onStatus = { message ->
            brakeStatus = message
            scheduleRefresh()
        }
    )
    private val speedReader = SpeedReader(
        context,
        onValue = { metersPerSecond ->
            latestSpeed = if (metersPerSecond != null) {
                String.format(Locale.ROOT, "%.1f km/h", metersPerSecond * 3.6)
            } else {
                "수신 불가"
            }
            scheduleRefresh()
        },
        onStatus = { updateStatus(it) }
    )
    private val steeringReader = SteeringAngleReader(
        context,
        onValue = { angle ->
            latestSteering = if (angle != null && angle.isFinite()) {
                String.format(Locale.ROOT, "%.1f°", angle)
            } else {
                "수신 불가"
            }
            scheduleRefresh()
        },
        onStatus = { message ->
            steeringStatus = message
            scheduleRefresh()
        }
    )
    private val refreshDisplay = Runnable {
        if (latestSpeed != displayedSpeed || latestSteering != displayedSteering ||
            steeringStatus != displayedSteeringStatus || latestBrake != displayedBrake ||
            brakeStatus != displayedBrakeStatus) {
            displayedSpeed = latestSpeed
            displayedSteering = latestSteering
            displayedSteeringStatus = steeringStatus
            displayedBrake = latestBrake
            displayedBrakeStatus = brakeStatus
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        steeringReader.start()
        brakeReader.start()
        if (hasSpeedPermission()) {
            speedReader.start()
        }
        invalidate()
    }

    private fun hasSpeedPermission(): Boolean =
        carContext.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    private fun requestSpeedPermission() {
        if (hasSpeedPermission()) {
            speedReader.start()
            invalidate()
            return
        }
        carContext.requestPermissions(listOf(Car.PERMISSION_SPEED)) { _, _ ->
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@requestPermissions
            if (hasSpeedPermission()) {
                speedReader.start()
                invalidate()
            } else {
                updateStatus("차량 속도 권한이 필요합니다. 다시 요청하거나 앱 설정에서 허용해 주세요.")
            }
        }
    }
    private fun scheduleRefresh() {
        if (!handler.hasCallbacks(refreshDisplay)) {
            handler.postDelayed(refreshDisplay, 500)
        }
    }

    private fun updateStatus(message: String) {
        status = message
        invalidate()
    }

    override fun onGetTemplate(): PaneTemplate {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("현재 속도").addText(displayedSpeed)
                .addText(status.replace("\n", " · ")).build())
            .addRow(Row.Builder().setTitle("앞바퀴 조향각 · 좌회전 음수").addText(displayedSteering)
                .addText(displayedSteeringStatus.replace("\n", " · ")).build())
            .addRow(Row.Builder().setTitle("브레이크 페달").addText(displayedBrake)
                .addText(displayedBrakeStatus.replace("\n", " · ")).build())
        .apply {
            if (!hasSpeedPermission()) {
                addAction(Action.Builder().setTitle("권한 허용")
                    .setOnClickListener(androidx.car.app.model.ParkedOnlyOnClickListener.create {
                        requestSpeedPermission()
                    }).build())
            }
        }
        .build()
        return PaneTemplate.Builder(pane).setHeaderAction(Action.APP_ICON).setTitle("차량 데이터 확인").build()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(refreshDisplay)
        try {
            speedReader.close()
        } finally {
            try {
                steeringReader.close()
            } finally {
                brakeReader.close()
            }
        }
    }
}
