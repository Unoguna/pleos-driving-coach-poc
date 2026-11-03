package com.example.pleos_driving_coach_poc.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.PleosPermission
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener

internal class SafetyReader(
    private val context: Context,
    private val vehicle: Vehicle,
    private val onValue: (Telltales?) -> Unit,
    private val onStatus: (String) -> Unit
) : TelltaleInfoListener {
    private val safety = vehicle.getSafety()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var closed = false
    private var registered = false
    private var receivedEvent = false
    private var capabilityPending = true
    private var readPending = true
    private var subscriptionPending = false
    private var capabilityStatus = "지원 확인 대기"
    private var readStatus = "단건 조회 대기"
    private var subscriptionStatus = "이벤트 대기"

    fun start() {
        if (started || closed) return
        started = true
        val allowed = context.checkSelfPermission(PleosPermission.PRIVATE_CAR_SAFETY) ==
            PackageManager.PERMISSION_GRANTED
        Log.i("SafetyProbe", "PERMISSION PRIVATE_CAR_SAFETY granted=$allowed")
        if (!allowed) {
            onStatus("통합 경고등 접근 권한 없음 · 제조사 권한 설정 확인 필요")
            return
        }
        try {
            safety.checkTelltalesCapabilities(
                onComplete = { config ->
                    Log.i("SafetyProbe", "CAPABILITY accessType=${config.accessType}")
                    deliver {
                        capabilityPending = false
                        capabilityStatus = "접근 유형 ${config.accessType}"
                        reportStatus()
                    }
                },
                onFailed = { error ->
                    Log.w("SafetyProbe", "CAPABILITY failed", error)
                    deliver {
                        capabilityPending = false
                        capabilityStatus = "지원 확인 실패: ${error.message}"
                        reportStatus()
                    }
                }
            )
        } catch (error: RuntimeException) {
            capabilityPending = false
            capabilityStatus = "지원 확인 실패: ${error.message}"
        }
        try {
            safety.getTelltalesInfo(
                onComplete = { telltales ->
                    logValue("READ", telltales)
                    deliver {
                        readPending = false
                        readStatus = "단건 조회 ${telltales.telltales.size}개"
                        if (!receivedEvent) onValue(telltales)
                        reportStatus()
                    }
                },
                onFailed = { error ->
                    Log.w("SafetyProbe", "READ failed", error)
                    deliver {
                        readPending = false
                        readStatus = "단건 조회 실패: ${error.message}"
                        reportStatus()
                    }
                }
            )
        } catch (error: RuntimeException) {
            readPending = false
            readStatus = "단건 조회 실패: ${error.message}"
        }
        try {
            safety.registerTelltales(this)
            registered = true
            subscriptionPending = true
            subscriptionStatus = "구독 요청 완료 · 첫 이벤트 대기"
        } catch (error: RuntimeException) {
            subscriptionStatus = "구독 요청 실패: ${error.message}"
        }
        reportStatus()
        handler.postDelayed({
            if (!closed) {
                if (capabilityPending) capabilityStatus = "지원 확인 10초 내 응답 없음"
                if (readPending) readStatus = "단건 조회 10초 내 응답 없음"
                if (subscriptionPending) subscriptionStatus = "구독 요청 후 10초 내 이벤트 없음"
                reportStatus()
            }
        }, 10_000)
    }

    override fun onTelltaleInfoUpdated(telltales: Telltales?) {
        logValue("EVENT", telltales)
        deliver {
            receivedEvent = true
            subscriptionPending = false
            onValue(telltales)
            subscriptionStatus = if (telltales == null) "이벤트 수신 · 값 없음" else "이벤트 수신 확인"
            reportStatus()
        }
    }

    override fun onFailed(e: Exception) {
        Log.w("SafetyProbe", "SUBSCRIBE/EVENT failed", e)
        deliver {
            subscriptionPending = false
            onValue(null)
            subscriptionStatus = "구독/이벤트 실패: ${e.message}"
            reportStatus()
        }
    }

    private fun logValue(source: String, value: Telltales?) {
        Log.i("SafetyProbe", "$source count=${value?.telltales?.size} receivedAtNs=${SystemClock.elapsedRealtimeNanos()}")
        value?.telltales?.forEach { item ->
            Log.i("SafetyProbe", "$source type=${item.type} state=${item.state} color=${item.color} priority=${item.priority} warningCount=${item.warningCount}")
        }
    }

    private fun reportStatus() {
        val status = "$capabilityStatus · $readStatus · $subscriptionStatus"
        Log.i("SafetyProbe", status)
        onStatus(status)
    }

    private fun deliver(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try {
            if (registered) safety.unregisterTelltales(this)
        } catch (error: RuntimeException) {
            Log.w("SafetyProbe", "UNSUBSCRIBE failed", error)
        }
    }
}
