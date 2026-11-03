package com.example.pleos_driving_coach_poc.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

internal class VehicleStateReader(
    private val context: Context,
    private val onUpdate: (Int, String, String) -> Unit
) : CarPropertyManager.CarPropertyEventCallback {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var started = false
    private var closed = false
    private var subscribed = false
    private val values = mutableMapOf<Int, String>()
    private val readResults = mutableMapOf<Int, String>()
    private val subscriptionResults = mutableMapOf<Int, String>()

    fun start() {
        if (started || closed) return
        started = true
        val properties = try {
            car = Car.createCar(context)
            car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (error: RuntimeException) {
            Log.w("VehicleStateProbe", "CONNECT failed", error)
            null
        }
        if (properties == null) {
            labels.keys.forEach { onUpdate(it, "수신값 없음", "차량 속성 서비스 연결 실패") }
            return
        }
        manager = properties
        labels.keys.forEach { propertyId -> probe(properties, propertyId) }
    }

    private fun probe(properties: CarPropertyManager, propertyId: Int) {
        try {
            val config = properties.getCarPropertyConfig(propertyId)
            if (config == null) {
                report(propertyId, "수신값 없음", "속성 미노출 · 미지원 또는 접근 제한")
                return
            }
            Log.i("VehicleStateProbe", "CONFIG property=$propertyId access=${config.access} changeMode=${config.changeMode}")
        } catch (error: RuntimeException) {
            report(propertyId, "수신값 없음", "지원 확인 실패: ${error.message}")
            return
        }
        try {
            val value = properties.getProperty<Any>(propertyId, 0)
            values[propertyId] = describe(value)
            readResults[propertyId] = "단건 조회 ${values[propertyId]}"
            logValue("READ", value)
        } catch (error: RuntimeException) {
            readResults[propertyId] = "단건 조회 실패: ${error.message}"
            Log.w("VehicleStateProbe", "READ failed property=$propertyId", error)
        }
        try {
            val registered = properties.registerCallback(this, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            subscribed = subscribed || registered
            subscriptionResults[propertyId] = if (registered) "구독 등록 성공 · 첫 이벤트 대기" else "구독 등록 실패"
        } catch (error: RuntimeException) {
            subscriptionResults[propertyId] = "구독 실패: ${error.message}"
        }
        publish(propertyId)
    }

    private fun describe(value: CarPropertyValue<*>?): String {
        if (value?.status != CarPropertyValue.STATUS_AVAILABLE) return "수신 불가"
        return when (value.propertyId) {
            VehiclePropertyIds.GEAR_SELECTION -> when (val gear = value.value as? Int) {
                VehicleGear.GEAR_PARK -> "P · 주차 기어"
                VehicleGear.GEAR_DRIVE -> "D"
                VehicleGear.GEAR_NEUTRAL -> "N"
                VehicleGear.GEAR_REVERSE -> "R"
                null -> "수신 불가"
                else -> VehicleGear.toString(gear)
            }
            VehiclePropertyIds.PARKING_BRAKE_ON -> when (value.value as? Boolean) {
                true -> "체결"
                false -> "해제"
                null -> "수신 불가"
            }
            else -> "알 수 없는 속성"
        }
    }

    override fun onChangeEvent(value: CarPropertyValue<*>) {
        if (closed || value.propertyId !in labels) return
        values[value.propertyId] = describe(value)
        subscriptionResults[value.propertyId] = if (value.status == CarPropertyValue.STATUS_AVAILABLE) {
            "이벤트 수신 확인"
        } else {
            "이벤트 수신 · 값 없음"
        }
        logValue("EVENT", value)
        publish(value.propertyId)
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) {
        if (closed || propertyId !in labels) return
        values[propertyId] = "수신 오류"
        subscriptionResults[propertyId] = "이벤트 오류"
        publish(propertyId)
    }

    private fun logValue(source: String, value: CarPropertyValue<*>?) {
        Log.i("VehicleStateProbe", "$source property=${value?.propertyId} raw=${value?.value} status=${value?.status} timestampNs=${value?.timestamp}")
    }

    private fun publish(propertyId: Int) {
        report(propertyId, values[propertyId] ?: "수신값 없음",
            "${readResults[propertyId]} · ${subscriptionResults[propertyId]}")
    }

    private fun report(propertyId: Int, value: String, status: String) {
        Log.i("VehicleStateProbe", "STATUS property=$propertyId value=$value $status")
        onUpdate(propertyId, value, status)
    }

    fun close() {
        closed = true
        try {
            if (subscribed) manager?.unregisterCallback(this)
        } finally {
            car?.disconnect()
        }
    }

    companion object {
        val labels = linkedMapOf(
            VehiclePropertyIds.GEAR_SELECTION to "선택 기어 · P는 주차 기어",
            VehiclePropertyIds.PARKING_BRAKE_ON to "주차 브레이크"
        )
    }
}
