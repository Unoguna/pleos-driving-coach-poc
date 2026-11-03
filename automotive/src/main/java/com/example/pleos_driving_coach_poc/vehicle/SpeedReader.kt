package com.example.pleos_driving_coach_poc.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import java.util.Locale

internal class SpeedReader(
    private val context: Context,
    private val onValue: (Float?) -> Unit,
    private val onStatus: (String) -> Unit
) : CarPropertyManager.CarPropertyEventCallback {
    private val speed = VehiclePropertyIds.PERF_VEHICLE_SPEED
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var subscribed = false
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        val properties = try {
            car = Car.createCar(context)
            car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (e: RuntimeException) {
            Log.w("SpeedProbe", "CONNECT failed", e)
            null
        }
        if (properties == null) {
            onStatus("차량 속성 서비스에 연결할 수 없습니다.")
            return
        }
        manager = properties
        val reading = try {
            val initial = properties.getProperty(Float::class.javaObjectType, speed, 0)
            onValue(speedValue(initial))
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
        onStatus("단건 조회  $reading\n이벤트 구독  $subscription")
    }

    private fun describe(value: CarPropertyValue<*>?): String {
        val metersPerSecond = value?.value as? Float
        if (value?.status != CarPropertyValue.STATUS_AVAILABLE || metersPerSecond == null) {
            return "unavailable"
        }
        return String.format(Locale.ROOT, "%.2f m/s (%.1f km/h) timestampNs=%d",
            metersPerSecond, metersPerSecond * 3.6, value.timestamp)
    }

    private fun speedValue(value: CarPropertyValue<*>?): Float? {
        if (value?.status != CarPropertyValue.STATUS_AVAILABLE) return null
        return (value.value as? Float)?.takeIf { it.isFinite() }
    }

    override fun onChangeEvent(value: CarPropertyValue<*>) {
        if (closed || value.propertyId != speed) return
        onValue(speedValue(value))
        Log.i("SpeedProbe", "EVENT ${describe(value)}")
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        if (closed || propertyId != speed) return
        onValue(null)
        onStatus("속도 이벤트 수신 오류")
        Log.w("SpeedProbe", "EVENT error property=$propertyId area=$areaId")
    }

    fun close() {
        closed = true
        try {
            if (subscribed) manager?.unregisterCallback(this)
        } finally {
            car?.disconnect()
        }
    }
}
