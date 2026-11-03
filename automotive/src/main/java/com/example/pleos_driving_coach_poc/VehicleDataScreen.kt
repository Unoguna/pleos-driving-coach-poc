package com.example.pleos_driving_coach_poc

import android.car.Car
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.vehicle.BrakeReader
import com.example.pleos_driving_coach_poc.vehicle.SteeringAngleReader
import com.example.pleos_driving_coach_poc.vehicle.SpeedReader
import com.example.pleos_driving_coach_poc.vehicle.VehicleStateReader
import com.example.pleos_driving_coach_poc.vehicle.SafetyReader
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import java.util.Locale
import kotlin.math.abs

internal class VehicleDataScreen(context: CarContext) : Screen(context),
    DefaultLifecycleObserver {
    private var status = "정차 상태에서 이 항목을 눌러 속도 권한을 허용해 주세요."

    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private var vehicleInitialized = false
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
    private var stoppedState = "정차 여부 미확인"
    private var displayedStoppedState = stoppedState
    private var safetyValue = "수신값 없음"
    private var displayedSafetyValue = safetyValue
    private var safetyStatus = "지원 확인 대기"
    private var displayedSafetyStatus = safetyStatus
    private val safetyReader = SafetyReader(
        context,
        vehicle,
        onValue = { telltales ->
            safetyValue = if (telltales == null) {
                "수신 불가"
            } else {
                val active = telltales.telltales.filter { it.state != TelltaleState.OFF }
                if (active.isEmpty()) {
                    "수신 ${telltales.telltales.size}개 · 점등/점멸 항목 없음"
                } else {
                    "점등/점멸 ${active.size}개 · " +
                        active.take(3).joinToString { "${it.type} ${it.state}" } +
                        if (active.size > 3) " · 전체 항목은 SafetyProbe 로그 확인" else ""
                }
            }
            scheduleRefresh()
        },
        onStatus = { message ->
            safetyStatus = message
            scheduleRefresh()
        }
    )
    private val stateValues = mutableMapOf<Int, String>()
    private val stateDiagnostics = mutableMapOf<Int, String>()
    private var stateChanged = false
    private val stateReader = VehicleStateReader(context) { propertyId, value, diagnostic ->
        stateValues[propertyId] = value
        stateDiagnostics[propertyId] = diagnostic
        stateChanged = true
        scheduleRefresh()
    }
    private val brakeReader = BrakeReader(
        vehicle,
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
            stoppedState = when {
                metersPerSecond == null -> "정차 여부 미확인"
                abs(metersPerSecond) <= 0.1f -> "정차 추정"
                else -> "이동 중"
            }
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
        vehicle,
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
            brakeStatus != displayedBrakeStatus || stoppedState != displayedStoppedState || stateChanged ||
            safetyValue != displayedSafetyValue || safetyStatus != displayedSafetyStatus) {
            displayedSpeed = latestSpeed
            displayedSteering = latestSteering
            displayedSteeringStatus = steeringStatus
            displayedBrake = latestBrake
            displayedBrakeStatus = brakeStatus
            displayedStoppedState = stoppedState
            displayedSafetyValue = safetyValue
            displayedSafetyStatus = safetyStatus
            stateChanged = false
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!vehicleInitialized) {
            try {
                vehicle.initialize()
                vehicleInitialized = true
            } catch (error: RuntimeException) {
                Log.w("VehicleDataScreen", "Pleos SDK initialization failed", error)
                val message = "Pleos SDK 연결 시작 실패: ${error.message}"
                steeringStatus = message
                brakeStatus = message
                safetyStatus = message
                scheduleRefresh()
            }
        }
        if (vehicleInitialized) {
            steeringReader.start()
            brakeReader.start()
            safetyReader.start()
        }
        stateReader.start()
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

    override fun onGetTemplate(): ListTemplate {
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("속도 · 정차 여부").addText("$displayedSpeed · $displayedStoppedState")
                .addText(status.replace("\n", " · ") + " · 정차 추정 |속도| ≤ 0.1 m/s")
                .apply {
                    if (!hasSpeedPermission()) {
                        setOnClickListener(androidx.car.app.model.ParkedOnlyOnClickListener.create {
                            requestSpeedPermission()
                        })
                    }
                }.build())
            .addItem(Row.Builder().setTitle("앞바퀴 조향각 · 좌회전 음수").addText(displayedSteering)
                .addText(displayedSteeringStatus.replace("\n", " · ")).build())
            .addItem(Row.Builder().setTitle("브레이크 페달").addText(displayedBrake)
                .addText(displayedBrakeStatus.replace("\n", " · ")).build())
        VehicleStateReader.labels.forEach { (propertyId, title) ->
            items.addItem(Row.Builder().setTitle(title)
                .addText(stateValues[propertyId] ?: "수신값 없음")
                .addText(stateDiagnostics[propertyId] ?: "조회·구독 대기").build())
        }
        items.addItem(Row.Builder().setTitle("계기판 경고등 · SDK 코드")
            .addText(displayedSafetyValue).addText(displayedSafetyStatus).build())
        val actions = ActionStrip.Builder().addAction(Action.Builder().setTitle("IMU 확인")
            .setOnClickListener { screenManager.push(ImuScreen(carContext)) }.build())
            .addAction(Action.Builder()
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
                    android.R.drawable.ic_dialog_info)).build())
                .setOnClickListener { screenManager.push(NotificationProbeScreen(carContext)) }.build())
            .build()
        return ListTemplate.Builder().setSingleList(items.build())
            .setActionStrip(actions).setHeaderAction(Action.APP_ICON).setTitle("차량 데이터 확인").build()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(refreshDisplay)
        try {
            speedReader.close()
        } finally {
            try {
                steeringReader.close()
            } finally {
                try {
                    brakeReader.close()
                } finally {
                    try {
                        stateReader.close()
                    } finally {
                        try {
                            safetyReader.close()
                        } finally {
                            vehicle.release()
                        }
                    }
                }
            }
        }
    }
}
