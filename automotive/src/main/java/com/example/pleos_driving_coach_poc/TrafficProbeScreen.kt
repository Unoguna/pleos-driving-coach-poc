package com.example.pleos_driving_coach_poc

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.navigation.NavigationReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class TrafficProbeScreen(context: CarContext, private val reader: NavigationReader) :
    Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1_000)
        }
    }

    init { lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { handler.post(refresh) }
    override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(refresh) }

    override fun onGetTemplate(): ListTemplate {
        val traffic = reader.traffic
        val received = if (reader.trafficReceivedAt == 0L) "미수신"
            else SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(reader.trafficReceivedAt))
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("교통 정보 · 눌러 다시 조회")
                .addText(reader.trafficStatus)
                .addText("마지막 수신 $received · 누적 ${reader.trafficReceiveCount}회")
                .setOnClickListener { reader.queryTraffic() }.build())
            .addItem(Row.Builder().setTitle("경로 혼잡 요약")
                .addText(traffic?.summary?.let {
                    "거리=${it.totalCongestionDist} · 시간=${it.totalCongestionTime} · 평균속도=${it.totalCongestionAvrSpeed}"
                } ?: "수신값 없음")
                .addText("SDK 원본값 · 빈 구간 목록만으로 원활하다고 판단하지 않음").build())
            .addItem(Row.Builder().setTitle("혼잡 구간 상세")
                .addText(traffic?.let { "${it.congestionItemList.size}개 수신 · 눌러 전체 목록 확인" } ?: "미수신")
                .setOnClickListener {
                    val rows = reader.traffic?.congestionItemList?.map { info ->
                        Row.Builder().setTitle("${info.roadName} · ${info.trafficCongestionType}")
                            .addText("${info.startCrossName} → ${info.endCrossName}")
                            .addText("경로상 위치=${info.offset} · 길이=${info.distance} · 시간=${info.trafficCongestionTime} · 평균속도=${info.avrSpeed}")
                            .build()
                    }
                    screenManager.push(TrafficDetailScreen(carContext, "혼잡 구간", rows))
                }.build())
            .addItem(Row.Builder().setTitle("교통 이벤트 상세")
                .addText(traffic?.let { "${it.trafficEventList.size}개 수신 · 눌러 전체 목록 확인" } ?: "미수신")
                .setOnClickListener {
                    val rows = reader.traffic?.trafficEventList?.map { info ->
                        Row.Builder().setTitle("${info.eventType} · ${info.description}")
                            .addText("위도=${info.latitude} · 경도=${info.longitude}")
                            .addText("시작=${info.startTime} · 종료=${info.endTime}").build()
                    }
                    screenManager.push(TrafficDetailScreen(carContext, "교통 이벤트", rows))
                }.build())
            .addItem(Row.Builder().setTitle("수신한 경로 상태").addText(reader.route)
                .addText("실제 정체 반영·업데이트 주기는 별도 검증 필요").build())
            .build()
        return ListTemplate.Builder().setTitle("교통 정체 정보 확인").setHeaderAction(Action.BACK)
            .setSingleList(items).build()
    }
}

// Keep the received list as a snapshot so new events do not reorder a page being read.
private class TrafficDetailScreen(context: CarContext, private val title: String, private val rows: List<Row>?) : Screen(context) {
    private var page = 0
    override fun onGetTemplate(): ListTemplate {
        val entries = rows.orEmpty()
        val pageCount = ((entries.size + 5) / 6).coerceAtLeast(1)
        val list = ItemList.Builder().setNoItemsMessage(if (rows == null) "교통 정보 미수신"
            else "0개 수신 · 실제 정체가 없다는 의미로 확정할 수 없음")
        entries.drop(page * 6).take(6).forEach { list.addItem(it) }
        val template = ListTemplate.Builder().setTitle("$title · ${page + 1}/$pageCount")
            .setHeaderAction(Action.BACK).setSingleList(list.build())
        if (pageCount > 1) {
            val actions = ActionStrip.Builder()
            if (page > 0) actions.addAction(pageAction(android.R.drawable.ic_media_previous) { page--; invalidate() })
            if (page + 1 < pageCount) actions.addAction(pageAction(android.R.drawable.ic_media_next) { page++; invalidate() })
            template.setActionStrip(actions.build())
        }
        return template.build()
    }

    private fun pageAction(icon: Int, onClick: () -> Unit) = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .setOnClickListener { onClick() }.build()
}
