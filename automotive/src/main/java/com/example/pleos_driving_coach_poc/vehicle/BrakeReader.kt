package com.example.pleos_driving_coach_poc.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener

internal class BrakeReader(
    private val vehicle: Vehicle,
    private val onValue: (Boolean?) -> Unit,
    private val onStatus: (String) -> Unit
) : BrakePedalStatusListener {
    private val brake = vehicle.getBrake()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var closed = false
    private var registered = false
    private var receivedEvent = false
    private var capabilityStatus = "지원 확인 대기"
    private var readStatus = "단건 조회 대기"
    private var subscriptionStatus = "이벤트 대기"

    fun start() {
        if (started || closed) return
        started = true
        try {
            brake.checkBrakePedalStatusCapabilities(
                onComplete = { config ->
                    Log.i("BrakeProbe", "CAPABILITY accessType=${config.accessType}")
                    deliver {
                        capabilityStatus = "접근 유형: ${config.accessType}"
                        reportStatus()
                    }
                },
                onFailed = { error ->
                    Log.w("BrakeProbe", "CAPABILITY failed", error)
                    deliver {
                        capabilityStatus = "지원 확인 실패: ${error.message}"
                        reportStatus()
                    }
                }
            )
            brake.getCurrentBrakePedalStatus(
                onComplete = { pressed ->
                    Log.i("BrakeProbe", "READ pressed=$pressed")
                    deliver {
                        readStatus = if (pressed == null) "단건 조회 수신 불가" else "단건 조회 ${if (pressed) "밟음" else "뗌"}"
                        if (!receivedEvent) onValue(pressed)
                        reportStatus()
                    }
                },
                onFailed = { error ->
                    Log.w("BrakeProbe", "READ failed", error)
                    deliver {
                        readStatus = "단건 조회 실패: ${error.message}"
                        reportStatus()
                    }
                }
            )
            // Registration returns Unit. Actual delivery is confirmed in the listener.
            brake.registerBrakePedalStatus(this)
            registered = true
            subscriptionStatus = "구독 요청 완료 · 첫 이벤트 대기"
            reportStatus()
        } catch (error: RuntimeException) {
            Log.w("BrakeProbe", "START failed", error)
            onStatus("브레이크 수집 시작 실패: ${error.message}")
        }
    }

    override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
        Log.i("BrakeProbe", "EVENT pressed=$pressed receivedAtNs=${SystemClock.elapsedRealtimeNanos()}")
        deliver {
            receivedEvent = true
            onValue(pressed)
            subscriptionStatus = if (pressed == null) "이벤트 수신 · 값 없음" else "이벤트 수신 확인"
            reportStatus()
        }
    }

    override fun onFailed(e: Exception) {
        Log.w("BrakeProbe", "SUBSCRIBE/EVENT failed", e)
        deliver {
            onValue(null)
            subscriptionStatus = "구독/이벤트 실패: ${e.message}"
            reportStatus()
        }
    }

    private fun reportStatus() {
        onStatus("$capabilityStatus\n$readStatus\n$subscriptionStatus")
    }

    private fun deliver(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try {
            if (registered) brake.unregisterBrakePedalStatus(this)
        } catch (error: RuntimeException) {
            Log.w("BrakeProbe", "UNSUBSCRIBE failed", error)
        }
    }
}

