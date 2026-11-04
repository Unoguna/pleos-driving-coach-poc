package com.example.pleos_driving_coach_poc.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

internal class NavigationReader(private val context: Context, private val onChange: () -> Unit) {
    var status = "연결 대기"
        private set
    var destination = "수신값 없음"
        private set
    var route = "수신값 없음"
        private set
    var guidance = "수신값 없음"
        private set
    var location = "현재 위치 미수신"
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context.applicationContext)
    private var started = false
    private var connected = false
    private var closed = false
    private var listening = false
    private val pending = mutableSetOf<String>()
    private val queryTimeout = Runnable {
        if (pending.isNotEmpty()) report("조회 응답 대기 10초 경과: ${pending.joinToString()} · 내비 실행/안내 상태 확인")
    }
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = receive("DESTINATION", destinationInfo) {
            destination = "${destinationInfo.name} · ${destinationInfo.address}\n" +
                "남은 거리=${destinationInfo.remainDistance} · 남은 시간=${destinationInfo.remainTime}"
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = receive("ROUTE", routeStateInfo) {
            route = "모드=${routeStateInfo.naviMode} · 탐색 중=${routeStateInfo.isRouting}\n옵션=${routeStateInfo.routeOption}"
        }
        override fun onTBTInfo(tbtInfo: List<TBTInfo>) = receive("TBT", tbtInfo) {
            guidance = if (tbtInfo.isEmpty()) "TBT 0개 수신 · 안내 없음"
                else "${tbtInfo.size}개 수신 · " + tbtInfo.take(2).joinToString(" / ") {
                    "${it.type} · 거리=${it.distance} · ${it.description}"
                }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = receive("LOCATION", currentLocationInfo) {
            location = "${currentLocationInfo.latitude}, ${currentLocationInfo.longitude}"
        }
        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) = receive("WAYPOINT", waypointInfo) {}
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = receive("EVENT_ROUTE_STARTED", routeStartInfo) {
            status = "경로 탐색 시작 이벤트 수신"
        }
        override fun onRoutingCompleted() = event("경로 탐색 완료 이벤트 수신", refresh = true)
        override fun onReRouteEnded() = event("경로 재탐색 완료 이벤트 수신", refresh = true)
        override fun onDestinationChanged() = event("목적지 변경 이벤트 수신 · 조회로 실제 목적지 확인")
        override fun onRouteCancelled() = receive("EVENT_ROUTE_CANCELLED", Unit) {
            status = "안내 취소 이벤트 수신"
            destination = "안내 취소됨"
            guidance = "안내 취소됨"
        }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) = event("내비 상태 이벤트: $navigationStatus")
        override fun onError(errorCode: NaviErrorCode) = event("내비 오류 이벤트: $errorCode")
    }

    fun start() {
        if (started || closed) return
        started = true
        report("NaviHelper 연결 중")
        try {
            helper.initialize(onConnected = { post {
                connected = true
                try {
                    helper.addListener(listener)
                    listening = true
                    report("내비 서비스 연결·리스너 등록 완료")
                    query()
                } catch (error: RuntimeException) { report("내비 리스너 등록 실패: ${error.message}") }
            } }, onFailed = { error -> post { report("내비 서비스 연결 실패: ${error.message}") } })
        } catch (error: RuntimeException) { report("내비 초기화 실패: ${error.message}") }
    }

    fun query() {
        if (!canCall(READ)) return
        handler.removeCallbacks(queryTimeout)
        pending.clear()
        report("현재 내비 정보 조회 요청 · 콜백 대기")
        listOf<Pair<String, () -> Unit>>(
            "DESTINATION" to { helper.getDestinationInfo() },
            "ROUTE" to { helper.getRouteStateInfo() },
            "TBT" to { helper.getTBTInfo() },
            "LOCATION" to { helper.getCurrentLocationInfo() },
            "WAYPOINT" to { helper.getWaypointInfo() }
        ).forEach { (key, request) ->
            try {
                pending.add(key)
                request()
                Log.i(TAG, "REQUEST $key")
            } catch (error: RuntimeException) {
                pending.remove(key)
                report("$key 조회 실패: ${error.message}")
            }
        }
        handler.postDelayed(queryTimeout, 10_000)
    }

    fun requestTestRoute() {
        if (!canCall(SEARCH)) return
        try {
            helper.requestRoute(RouteInfo(
                latitude = 37.48544722, longitude = 127.03636666,
                displayLatitude = null, displayLongitude = null,
                poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", poiSubId = "0",
                address = "서울시 강남구 남부순환로 2621"
            ))
            report("시험 목적지(42dot) 경로 요청 전송 · 탐색 완료 이벤트 대기")
        } catch (error: RuntimeException) { report("경로 요청 실패: ${error.message}") }
    }

    fun showNavigation() {
        if (!canCall(MAP)) return
        try {
            helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
            report("기본 내비 화면 표시 요청 전송 · 실제 화면 확인 필요")
        } catch (error: RuntimeException) { report("내비 화면 요청 실패: ${error.message}") }
    }

    fun cancelRoute() {
        if (!canCall(SEARCH)) return
        try {
            helper.cancelRoute()
            report("안내 취소 요청 전송 · 취소 이벤트 대기")
        } catch (error: RuntimeException) { report("안내 취소 실패: ${error.message}") }
    }

    private fun canCall(permission: String): Boolean {
        if (closed) return false
        if (!connected || !listening) { report("내비 서비스 연결·리스너 등록 필요"); return false }
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            report("권한 없음: $permission")
            return false
        }
        return true
    }

    private fun event(message: String, refresh: Boolean = false) = post {
        report(message)
        if (refresh) query()
    }

    private fun receive(key: String, value: Any, update: () -> Unit) = post {
        Log.i(TAG, "RECEIVE $key $value")
        update()
        val wasPending = pending.remove(key)
        if (wasPending && pending.isEmpty()) {
            handler.removeCallbacks(queryTimeout)
            status = "요청한 내비 정보 콜백 모두 수신"
        }
        onChange()
    }

    private fun post(block: () -> Unit) { handler.post { if (!closed) block() } }
    private fun report(message: String) {
        status = message
        Log.i(TAG, message)
        if (!closed) onChange()
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try { if (listening) helper.removeListener(listener) }
        finally { helper.release() }
    }

    companion object {
        private const val TAG = "NavigationProbe"
        private const val READ = "pleos.car.permission.NAVI_ROUTE"
        private const val SEARCH = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        private const val MAP = "pleos.car.permission.NAVI_CUSTOM_MAP"
    }
}
