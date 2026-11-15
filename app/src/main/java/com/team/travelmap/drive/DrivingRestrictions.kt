package com.team.travelmap.drive

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.team.travelmap.core.model.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 담당: 2번(차량·내비·통합). 주행 중 UI 제한 여부를 앱 전체에 알려 준다.
 * UI 쪽(1번)은 restricted == true 이면 리스트 상세·사진 등록·텍스트 입력을 숨기고 큰 버튼만 남긴다.
 *
 * AAOS CarUxRestrictions 를 우선 쓰고, 연결이 안 되면 기어(P가 아니면 주행)로 대신 판단한다.
 */
class DrivingRestrictions(context: Context) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var carAvailable = false

    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    fun start() {
        runCatching {
            val c = Car.createCar(appContext) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            carAvailable = true
            update(m.currentCarUxRestrictions)
            m.registerListener { update(it) }
        }.onFailure { Log.w(TAG, "CarUxRestrictions 사용 불가, 기어로 대체", it) }
    }

    /** CarUxRestrictions 를 못 쓸 때 기어 변화로 대신 판단한다. */
    fun onGearChanged(gear: Gear) {
        if (!carAvailable) _restricted.value = gear != Gear.PARK && gear != Gear.UNKNOWN
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null; car = null; carAvailable = false
    }

    private fun update(r: CarUxRestrictions?) {
        _restricted.value = r?.isRequiresDistractionOptimization ?: false
    }

    private companion object { const val TAG = "DrivingRestrictions" }
}
