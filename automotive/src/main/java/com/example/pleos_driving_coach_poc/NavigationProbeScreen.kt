package com.example.pleos_driving_coach_poc

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.pleos_driving_coach_poc.navigation.NavigationReader

internal class NavigationProbeScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val reader = NavigationReader(context) { invalidate() }
    init { lifecycle.addObserver(this) }
    override fun onCreate(owner: LifecycleOwner) { reader.start() }
    override fun onDestroy(owner: LifecycleOwner) { reader.close() }

    override fun onGetTemplate(): ListTemplate {
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("연결 상태 · 눌러 정보 다시 조회")
                .addText(reader.status).addText(reader.location)
                .setOnClickListener { reader.query() }.build())
            .addItem(Row.Builder().setTitle("현재 목적지").addText(reader.destination).build())
            .addItem(Row.Builder().setTitle("경로 상태").addText(reader.route).build())
            .addItem(Row.Builder().setTitle("단계별 안내 · TBT").addText(reader.guidance).build())
            .addItem(Row.Builder().setTitle("시험 목적지 길 안내 요청")
                .addText("42dot · 서울시 강남구 남부순환로 2621 · 기존 목적지 변경")
                .setOnClickListener(ParkedOnlyOnClickListener.create { reader.requestTestRoute() }).build())
            .addItem(Row.Builder().setTitle("현재 길 안내 취소")
                .setOnClickListener(ParkedOnlyOnClickListener.create { reader.cancelRoute() }).build())
            .build()
        return ListTemplate.Builder().setTitle("내비게이션 연동 확인").setHeaderAction(Action.BACK)
            .setSingleList(items)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("내비 화면 열기")
                .setOnClickListener { reader.showNavigation() }.build())
                .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
                    android.R.drawable.ic_menu_sort_by_size)).build())
                    .setOnClickListener { screenManager.push(TrafficProbeScreen(carContext, reader)) }.build())
                .build()).build()
    }
}
