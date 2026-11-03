package com.example.pleos_driving_coach_poc.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener

internal class SteeringAngleReader(
    private val vehicle: Vehicle,
    private val onValue: (Float?) -> Unit,
    private val onStatus: (String) -> Unit
) : SteeringWheelAngleListener {
    private val steeringWheel = vehicle.getSteeringWheel()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var closed = false
    private var registered = false
    private var receivedEvent = false
    private var readStatus = "단건 조회 대기"
    private var subscriptionStatus = "이벤트 대기"

    fun start() {
        if (started || closed) return
        started = true
        try {
            steeringWheel.getCurrentSteeringWheelAngle(
                onComplete = { angle ->
                    Log.i("SteeringProbe", "READ angleDeg=$angle")
                    deliver {
                        readStatus = if (angle == null) "단건 조회 수신 불가" else "단건 조회 ${angle}°"
                        if (!receivedEvent) onValue(angle)
                        reportStatus()
                    }
                },
                onFailed = { error ->
                    Log.w("SteeringProbe", "READ failed", error)
                    deliver {
                        readStatus = "단건 조회 실패: ${error.message}"
                        reportStatus()
                    }
                }
            )
            // Registration returns Unit. Actual delivery is confirmed in the listener.
            steeringWheel.registerSteeringWheelAngle(this)
            registered = true
            subscriptionStatus = "구독 요청 완료 · 첫 이벤트 대기"
            reportStatus()
        } catch (error: RuntimeException) {
            Log.w("SteeringProbe", "START failed", error)
            onStatus("조향각 수집 시작 실패: ${error.message}")
        }
    }

    override fun onSteeringWheelAngleUpdated(angle: Float?) {
        Log.i("SteeringProbe", "EVENT angleDeg=$angle receivedAtNs=${SystemClock.elapsedRealtimeNanos()}")
        deliver {
            receivedEvent = true
            onValue(angle)
            subscriptionStatus = if (angle == null) "이벤트 수신 · 값 없음" else "이벤트 수신 확인"
            reportStatus()
        }
    }

    override fun onFailed(e: Exception) {
        Log.w("SteeringProbe", "SUBSCRIBE/EVENT failed", e)
        deliver {
            onValue(null)
            subscriptionStatus = "구독/이벤트 실패: ${e.message}"
            reportStatus()
        }
    }

    private fun reportStatus() {
        onStatus("$readStatus\n$subscriptionStatus")
    }

    private fun deliver(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try {
            if (registered) steeringWheel.unregisterSteeringWheelAngle(this)
        } catch (error: RuntimeException) {
            Log.w("SteeringProbe", "UNSUBSCRIBE failed", error)
        }
    }
}
