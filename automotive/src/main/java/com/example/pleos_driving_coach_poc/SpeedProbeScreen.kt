package com.example.pleos_driving_coach_poc

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

internal class SpeedProbeScreen(context: CarContext) : Screen(context),
    DefaultLifecycleObserver, CarPropertyManager.CarPropertyEventCallback {
    private val speed = VehiclePropertyIds.PERF_VEHICLE_SPEED
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var subscribed = false
    private var started = false
    private var status = "정차 상태에서 차량 속도 권한을 허용해 주세요."

    private val handler = Handler(Looper.getMainLooper())
    private var latestSpeed = "대기 중"
    private var displayedSpeed = latestSpeed
    private var latestSteering = "대기 중"
    private var displayedSteering = latestSteering
    private var steeringStatus = "연결 대기"
    private var displayedSteeringStatus = steeringStatus
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
    private val refreshSpeed = Runnable {
        if (latestSpeed != displayedSpeed || latestSteering != displayedSteering ||
            steeringStatus != displayedSteeringStatus) {
            displayedSpeed = latestSpeed
            displayedSteering = latestSteering
            displayedSteeringStatus = steeringStatus
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        steeringReader.start()
        startIfGranted()
    }

    private fun startIfGranted(): Boolean {
        if (carContext.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (!started) {
            started = true
            startProbe()
        }
        return true
    }

    private fun requestSpeedPermission() {
        if (startIfGranted()) return
        carContext.requestPermissions(listOf(Car.PERMISSION_SPEED)) { _, _ ->
            if (lifecycle.currentState != Lifecycle.State.DESTROYED && !startIfGranted()) {
                updateStatus("차량 속도 권한이 필요합니다. 다시 요청하거나 앱 설정에서 허용해 주세요.")
            }
        }
    }
    private fun startProbe() {
        val properties = try {
            car = Car.createCar(carContext)
            car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (e: RuntimeException) {
            Log.w("SpeedProbe", "CONNECT failed", e)
            null
        }
        if (properties == null) {
            updateStatus("차량 속성 서비스에 연결할 수 없습니다.")
            return
        }
        manager = properties
        val reading = try {
            val initial = properties.getProperty(Float::class.javaObjectType, speed, 0)
            latestSpeed = formatSpeed(initial)
            displayedSpeed = latestSpeed
            describe(initial)
        } catch (e: RuntimeException) {
            "failed: $e"
        }
        Log.i("SpeedProbe", "READ $reading")
        val subscription = try {
            subscribed = properties.registerCallback(this, speed, CarPropertyManager.SENSOR_RATE_UI)
            subscribed.toString()
        } catch (e: RuntimeException) {
            "failed: $e"
        }
        Log.i("SpeedProbe", "SUBSCRIBE $subscription")
        updateStatus("단건 조회  $reading\n이벤트 구독  $subscription")
    }

    private fun describe(value: CarPropertyValue<*>?): String {
        val metersPerSecond = value?.value as? Float
        if (value?.status != CarPropertyValue.STATUS_AVAILABLE || metersPerSecond == null) {
            return "unavailable"
        }
        return String.format(Locale.ROOT, "%.2f m/s (%.1f km/h) timestampNs=%d",
            metersPerSecond, metersPerSecond * 3.6, value.timestamp)
    }

    private fun formatSpeed(value: CarPropertyValue<*>?): String {
        val speed = value?.value as? Float
        return if (value?.status == CarPropertyValue.STATUS_AVAILABLE && speed != null) {
            String.format(Locale.ROOT, "%.1f km/h", speed * 3.6)
        } else {
            "수신 불가"
        }
    }

    private fun queueSpeed(value: String) {
        latestSpeed = value
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (!handler.hasCallbacks(refreshSpeed)) {
            handler.postDelayed(refreshSpeed, 500)
        }
    }

    override fun onChangeEvent(value: CarPropertyValue<*>) {
        queueSpeed(formatSpeed(value))
        Log.i("SpeedProbe", "EVENT ${describe(value)}")
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        queueSpeed("수신 오류")
        Log.w("SpeedProbe", "EVENT error property=$propertyId area=$areaId")
    }

    private fun updateStatus(message: String) {
        status = message
        invalidate()
    }

    override fun onGetTemplate(): PaneTemplate {
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("현재 속도").addText(displayedSpeed).build())
            .addRow(Row.Builder().setTitle("진단 상태").addText(status).build())
            .addRow(Row.Builder().setTitle("앞바퀴 조향각 · 좌회전 음수").addText(displayedSteering).build())
            .addRow(Row.Builder().setTitle("조향각 진단").addText(displayedSteeringStatus).build())
        .apply {
            if (!started) {
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
        handler.removeCallbacks(refreshSpeed)
        try {
            if (subscribed) manager?.unregisterCallback(this)
        } finally {
            try {
                car?.disconnect()
            } finally {
                steeringReader.close()
            }
        }
    }
}
