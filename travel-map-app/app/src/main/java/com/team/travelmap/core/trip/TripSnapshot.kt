package com.team.travelmap.core.trip

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 2번(차량·내비·통합). 안내 중인 여행을 저장했다가 앱이 다시 시작되면 이어 간다(연속성).
 * 시동을 끄면 차량 화면이 꺼지고, 오래 세워 두면 시스템이 종료되어 메모리의 여행 상태가 사라지기 때문.
 *
 * 저장 대상: Guiding(안내 중), Arrived(목적지 도착, 주차 대기). Idle·Planned·Completed 는 저장하지 않는다.
 */
@Serializable
data class TripSnapshot(
    val stage: Stage,
    val region: SnapRegion,
    val destination: SnapPlace,
    val waypoints: List<SnapPlace> = emptyList(),
    val nextIndex: Int = 0,
    val arrivedAtWaypoint: Boolean = false,
    val parkedAtWaypoint: Boolean = false,
    /** 경유지에 주차한 채로 시동이 꺼졌는지. 켜지면 "여행을 계속할까요?"를 띄운다. */
    val ignitionOffAtStop: Boolean = false,
    val startedAt: Long = 0L,
    val startOdometerKm: Double? = null,
    val dismissedReviewIndex: Int? = null,
    /** 주차한 구 기록([ParkLog]). */
    val departed: Boolean = false,
    val onHighway: Boolean = false,
    val parkedRegions: List<String> = emptyList(),
) {
    fun toParkLog() = ParkLog(departed, onHighway, parkedRegions.map(::RegionId))

    enum class Stage { GUIDING, ARRIVED }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun toState(): TripState {
        val r = region.toRegion()
        return when (stage) {
            Stage.ARRIVED -> TripState.Arrived(r)
            Stage.GUIDING -> {
                val dest = destination.toPlace()
                val wps = waypoints.map { it.toPlace() }
                TripState.Guiding(
                    r, dest, wps,
                    TripLegs(dest, wps, nextIndex.coerceIn(0, wps.size), arrivedAtWaypoint, parkedAtWaypoint),
                )
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 저장 형식이 바뀌었거나 깨졌으면 null(이어 가지 않고 새로 시작). */
        fun fromJson(text: String?): TripSnapshot? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        /** 저장할 상태가 아니면 null. */
        fun of(
            state: TripState,
            ignitionOffAtStop: Boolean,
            startedAt: Long,
            startOdometerKm: Double?,
            dismissedReviewIndex: Int?,
            parkLog: ParkLog = ParkLog(),
        ): TripSnapshot? = when (state) {
            is TripState.Guiding -> TripSnapshot(
                stage = Stage.GUIDING,
                region = SnapRegion.of(state.region),
                destination = SnapPlace.of(state.destination),
                waypoints = state.waypoints.map(SnapPlace::of),
                nextIndex = state.legs.nextIndex,
                arrivedAtWaypoint = state.legs.arrivedAtWaypoint,
                parkedAtWaypoint = state.legs.parkedAtWaypoint,
                ignitionOffAtStop = ignitionOffAtStop,
                startedAt = startedAt,
                startOdometerKm = startOdometerKm,
                dismissedReviewIndex = dismissedReviewIndex,
                departed = parkLog.departed,
                onHighway = parkLog.onHighway,
                parkedRegions = parkLog.regions.map { it.code },
            )
            is TripState.Arrived -> TripSnapshot(
                stage = Stage.ARRIVED,
                region = SnapRegion.of(state.region),
                destination = SnapPlace.of(Place(state.region.fullName, state.region.center, PlaceCategory.LANDMARK, state.region.id)),
                startedAt = startedAt,
                startOdometerKm = startOdometerKm,
                departed = parkLog.departed,
                onHighway = parkLog.onHighway,
                parkedRegions = parkLog.regions.map { it.code },
            )
            else -> null
        }
    }
}

@Serializable
data class SnapRegion(val code: String, val sidoName: String, val name: String, val lat: Double, val lng: Double) {
    fun toRegion() = Region(RegionId(code), sidoName, name, GeoPoint(lat, lng))
    companion object {
        fun of(r: Region) = SnapRegion(r.id.code, r.sidoName, r.name, r.center.latitude, r.center.longitude)
    }
}

@Serializable
data class SnapPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String,
    val regionId: String? = null,
    val address: String? = null,
    val poiId: String? = null,
    val description: String? = null,
) {
    fun toPlace() = Place(
        name, GeoPoint(lat, lng),
        runCatching { PlaceCategory.valueOf(category) }.getOrDefault(PlaceCategory.LANDMARK),
        regionId?.let(::RegionId), address, poiId, description,
    )
    companion object {
        fun of(p: Place) = SnapPlace(
            p.name, p.point.latitude, p.point.longitude, p.category.name,
            p.regionId?.code, p.address, p.poiId, p.description,
        )
    }
}

/** 여행 저장소. 테스트에서는 메모리 구현을 쓴다. */
interface TripStore {
    fun load(): String?
    fun save(json: String?)

    object None : TripStore {
        override fun load(): String? = null
        override fun save(json: String?) = Unit
    }
}

class PrefsTripStore(context: Context) : TripStore {
    private val prefs = context.applicationContext.getSharedPreferences("trip", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(json: String?) {
        prefs.edit().apply { if (json == null) remove(KEY) else putString(KEY, json) }.apply()
    }
    private companion object { const val KEY = "active_trip_v1" }
}
