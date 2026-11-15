package com.team.travelmap.core.trip

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Ignition
import com.team.travelmap.core.model.NaviConnection
import com.team.travelmap.core.model.NaviEvent
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.navi.NaviRepository
import com.team.travelmap.settings.Settings
import com.team.travelmap.story.StoryEngine
import com.team.travelmap.vehicle.VehicleRepository
import com.team.travelmap.visit.VisitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [공통 약속] 여행 한 번의 흐름. 모든 화면은 이 state 하나만 구독한다.
 *
 *  Idle → Planned(룰렛·직접 선택) → Guiding(출발) → Arrived(목적지 도착) → Completed(주차 확인 = 여행 완료)
 *
 * 안내 중 주차(P)한 시·군·구는 [ParkLog] 에 모았다가 여행 완료 때 함께 방문 기록한다(고속도로 위 P 는 제외).
 *
 * 경유지는 개수 제한 없이 받고, 내비에는 [TripLegs] 로 "다음 경유지 1곳 + 목적지"씩 나눠 넘긴다.
 * 경유지에 도착해 주차(P)했다가 다시 출발(D)하면 다음 구간을 요청한다. Guiding.legs 로 진행 상황을 볼 수 있다.
 *
 * 담당: 2번(차량·내비·통합). 상태를 바꾸는 로직은 여기에만 둔다.
 */
sealed interface TripState {
    data object Idle : TripState
    data class Planned(val region: Region, val destination: Place, val waypoints: List<Place>) : TripState
    data class Guiding(
        val region: Region,
        val destination: Place,
        /** 출발할 때 고른 경유지 전체(순서대로). 주행 중 화면의 "오늘의 여정"에 쓴다(1번). */
        val waypoints: List<Place> = emptyList(),
        /** 경유지 구간 진행 상황. 다음 경유지·남은 경유지·경유지 주차 여부(2번). */
        val legs: TripLegs = TripLegs(destination, waypoints),
    ) : TripState
    data class Arrived(val region: Region) : TripState
    data class Completed(
        val region: Region,
        val visitId: Long,
        val distanceKm: Double?,
        /** 가는 길에 주차해서 함께 기록한 다른 구의 방문 기록 id. 목적지 기록([visitId])은 빠져 있다. */
        val stopVisitIds: List<Long> = emptyList(),
    ) : TripState {
        /** 이번 여행의 방문 기록 전체(목적지 + 들른 구). 기록하기·기록 안 함에 쓴다. */
        val allVisitIds: List<Long> get() = listOf(visitId) + stopVisitIds
    }
}

class TripCoordinator(
    private val scope: CoroutineScope,
    private val navi: NaviRepository,
    private val vehicle: VehicleRepository,
    private val visits: VisitRepository,
    private val stories: StoryEngine,
    /** 설정(담당: 1번). 여기서는 읽기만 한다. */
    private val settings: StateFlow<Settings> = MutableStateFlow(Settings()),
    /** 시동 상태(차량 화면 켜짐/꺼짐으로 판단, vehicle/ScreenIgnitionMonitor). */
    private val ignition: StateFlow<Ignition> = MutableStateFlow(Ignition.ON),
    /** 안내 중인 여행 저장소. 앱이 다시 시작되면 이어 간다. */
    private val store: TripStore = TripStore.None,
    /** 좌표 → 시·군·구. 주차한 구 기록에 쓴다(AppContainer 가 지도 경계로 판정). */
    private val regionAt: suspend (GeoPoint) -> RegionId? = { null },
) {
    private val _state = MutableStateFlow<TripState>(TripState.Idle)
    val state: StateFlow<TripState> = _state.asStateFlow()

    /** 이번 여행에서 리뷰를 닫은 경유지 번호. 같은 경유지에서 다시 띄우지 않는다. */
    private val dismissedReviewIndex = MutableStateFlow<Int?>(null)

    /**
     * 경유지 리뷰 신호. 경유지에 주차(P)해 있고 설정 "경유지 리뷰"가 켜져 있으면 값이 있다.
     * 화면(1번)은 값이 있을 때 리뷰 화면을 띄우고, 저장·건너뛰기 후 [dismissReview] 를 부른다.
     * 다시 출발(D)하면 자동으로 null 이 된다.
     */
    val reviewRequest: StateFlow<WaypointReviewRequest?> =
        combine(_state, settings, dismissedReviewIndex) { s, st, dismissed ->
            WaypointReview.request((s as? TripState.Guiding)?.legs, st.waypointReview, dismissed)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 리뷰를 저장했거나 건너뛰었을 때 화면이 부른다. */
    fun dismissReview() {
        reviewRequest.value?.let { dismissedReviewIndex.value = it.index }
    }

    /** 경유지에 주차한 채로 시동이 꺼졌는지. 다시 켜지면 "여행을 계속할까요?"를 띄운다. */
    private val ignitionOffAtStop = MutableStateFlow(false)
    private val _resumeRequest = MutableStateFlow<ResumeRequest?>(null)
    private var resumeTimer: Job? = null

    /**
     * "여행을 계속할까요?" 신호. 경유지에 주차한 채로 시동을 껐다가 다시 켜면 값이 있다.
     * 화면(1번)은 값이 있을 때 계속/아니요 버튼을 보여 주고 [answerResume] 를 부른다.
     * [ResumeRequest.autoContinueAtMillis] 까지 답이 없으면 자동으로 계속한다.
     * 설정 "여행 재개 알림"이 꺼져 있으면 뜨지 않고, 지금처럼 다시 출발(D)하면 다음 구간으로 간다.
     */
    val resumeRequest: StateFlow<ResumeRequest?> = _resumeRequest.asStateFlow()

    /** 계속 = true. "아니요"(false)를 골랐을 때의 동작은 이슈 #21 결정 전까지 알림만 닫는다. */
    fun answerResume(continueTrip: Boolean) {
        if (continueTrip) continueAfterStop()
        else cancel() // 이슈 #21: "아니요" = 여행 종료(방문 기록 없이 Idle 로)
    }

    /** 이번 여행에서 주차한 구(고속도로 위 P 제외). */
    private val parkLog = MutableStateFlow(ParkLog())

    /** 지금 고속도로 위인지(IC 진입~진출). 화면은 주행 중 주변 추천을 고속도로에서 숨기는 데 쓴다. */
    /** 이번 여행의 장소들(목적지 먼저, 그다음 경유지). 완료 때 방문 기록에 장소를 남기는 데 쓴다. */
    private var lastPlaces: List<Place> = emptyList()

    val onHighway: StateFlow<Boolean> = parkLog.map { it.onHighway }.stateIn(scope, SharingStarted.Eagerly, false)

    private var startOdometerKm: Double? = null
    /** 이번 여행의 경로 시작(RouteStarted)을 받았는지. */
    private var routeConfirmed = false
    private var startedAt = 0L

    init {
        restore()
        // 상태가 바뀔 때마다 저장한다(안내 중·도착만, 나머지는 지움).
        scope.launch {
            combine(_state, ignitionOffAtStop, dismissedReviewIndex, parkLog) { _, _, _, _ -> }.collect { persist() }
        }
        // 시동: 경유지에 주차한 채로 꺼졌다가 켜지면 "여행을 계속할까요?"
        scope.launch {
            ignition.collect { ig ->
                val s = _state.value as? TripState.Guiding ?: return@collect
                when (ig) {
                    Ignition.OFF -> if (s.legs.stoppedAtWaypoint) ignitionOffAtStop.value = true
                    Ignition.ON -> if (ignitionOffAtStop.value) {
                        ignitionOffAtStop.value = false
                        promptResume(s.legs)
                    }
                }
            }
        }
        scope.launch {
            navi.events.collect { e ->
                val s = _state.value
                when {
                    e is NaviEvent.DestinationArrived && s is TripState.Guiding -> {
                        stories.stopTrip()
                        lastPlaces = listOf(s.destination) + s.waypoints
                        _state.value = TripState.Arrived(s.region)
                    }
                    e is NaviEvent.WaypointArrived && s is TripState.Guiding ->
                        _state.value = s.copy(legs = s.legs.onWaypointArrived())
                    e is NaviEvent.HighwayChanged && s is TripState.Guiding ->
                        parkLog.update { it.onIc(if (e.onHighway) IcTurn.ENTER else IcTurn.EXIT) }
                    e is NaviEvent.RouteStarted -> routeConfirmed = true
                    // 이전 경로가 안내 중일 때 새 경로를 요청하면 Pleos 내비가 이전 경로의 취소 이벤트를
                    // 새 경로 시작 전에 보낸다(에뮬레이터에서 확인). 새 경로가 시작된 뒤의 취소만 여행 취소로 본다.
                    e is NaviEvent.RouteCancelled && s is TripState.Guiding && routeConfirmed -> cancel()
                }
            }
        }
        // 도착 후 기어가 P 가 되면 여행 완료로 인증
        scope.launch {
            combine(_state, vehicle.gear) { s, g -> s to g }.collect { (s, g) ->
                if (s is TripState.Arrived && g == Gear.PARK) complete(s.region)
            }
        }
        // 안내 중 주차(P)하면 그 자리의 구를 모은다(출발 전·고속도로 위 P 는 제외).
        scope.launch {
            vehicle.gear.collect { g ->
                val s = _state.value as? TripState.Guiding ?: return@collect
                val (log, record) = parkLog.value.onGear(g)
                parkLog.value = log
                if (record) scope.launch { parkedRegion(s)?.let { id -> parkLog.update { it.add(id) } } }
            }
        }
        // 경유지에서 주차(P) 후 다시 출발(D)하면 다음 구간을 내비에 요청
        scope.launch {
            vehicle.gear.collect { g ->
                val s = _state.value as? TripState.Guiding ?: return@collect
                val (legs, requestNext) = s.legs.onGear(g)
                if (legs == s.legs) return@collect
                _state.value = s.copy(legs = legs)
                // 알림에 답하기 전에 다시 출발(D)했으면 계속한 것으로 보고 알림을 닫는다.
                if (!legs.stoppedAtWaypoint) { clearResume(); ignitionOffAtStop.value = false }
                if (requestNext) requestLeg(legs)
            }
        }
    }

    /** 지금 주차한 곳의 구. 위치를 모르면 막 도착한 경유지의 구로 대신한다. */
    private suspend fun parkedRegion(s: TripState.Guiding): RegionId? {
        navi.currentLocation.value?.let { p -> regionAt(p)?.let { return it } }
        return s.legs.nextWaypoint?.takeIf { s.legs.arrivedAtWaypoint }?.regionId?.takeIf { it.code.length == 5 }
    }

    private fun promptResume(legs: TripLegs) {
        val req = TripResume.request(legs, ignitionWasOffAtStop = true, settings.value.resumeAlert, System.currentTimeMillis()) ?: return
        resumeTimer?.cancel()
        _resumeRequest.value = req
        resumeTimer = scope.launch {
            delay(TripResume.AUTO_CONTINUE_MS)
            if (_resumeRequest.value == req) continueAfterStop()
        }
    }

    /** 경유지를 마치고 다음 구간(다음 경유지 또는 목적지)을 내비에 요청한다. */
    private fun continueAfterStop() {
        clearResume()
        val s = _state.value as? TripState.Guiding ?: return
        if (!s.legs.stoppedAtWaypoint) return
        val legs = s.legs.departFromWaypoint()
        _state.value = s.copy(legs = legs)
        requestLeg(legs)
    }

    private fun clearResume() {
        resumeTimer?.cancel()
        resumeTimer = null
        _resumeRequest.value = null
    }

    private fun persist() {
        val snap = TripSnapshot.of(_state.value, ignitionOffAtStop.value, startedAt, startOdometerKm, dismissedReviewIndex.value, parkLog.value)
        store.save(snap?.toJson())
    }

    /** 앱이 다시 시작되면(시동을 오래 꺼서 시스템이 종료된 경우 등) 저장한 여행을 이어 간다. */
    private fun restore() {
        val snap = TripSnapshot.fromJson(store.load()) ?: return
        val restored = snap.toState()
        startedAt = snap.startedAt
        startOdometerKm = snap.startOdometerKm
        dismissedReviewIndex.value = snap.dismissedReviewIndex
        parkLog.value = snap.toParkLog()
        routeConfirmed = true
        lastPlaces = (restored as? TripState.Guiding)?.let { listOf(it.destination) + it.waypoints }.orEmpty()
        _state.value = restored
        val g = restored as? TripState.Guiding ?: return
        stories.startTrip(navi.currentLocation.value, g.destination.point)
        if (g.legs.stoppedAtWaypoint) {
            // 경유지에 주차한 상태에서 앱이 다시 시작됐다 = 시동을 다시 켰다고 본다.
            promptResume(g.legs)
        } else {
            // 이동 중이던 구간은 내비 연결 후 다시 요청한다.
            scope.launch {
                navi.connection.first { it is NaviConnection.Connected }
                requestLeg(g.legs)
            }
        }
    }

    /** 경유지는 개수 제한 없이 받는다. 내비에는 [TripLegs] 가 구간별로 나눠 넘긴다. */
    fun plan(region: Region, destination: Place, waypoints: List<Place> = emptyList()) {
        lastPlaces = listOf(destination) + waypoints
        _state.value = TripState.Planned(region, destination, waypoints)
    }

    fun start(): Result<Unit> {
        val s = _state.value as? TripState.Planned ?: return Result.failure(IllegalStateException("여행지가 없음"))
        val legs = TripLegs(s.destination, s.waypoints)
        return requestLeg(legs).onSuccess {
            dismissedReviewIndex.value = null
            // 출발 버튼을 누를 때 이미 D 면 움직이는 중으로 본다(P 면 첫 D 이후부터 센다).
            parkLog.value = ParkLog().onGear(vehicle.gear.value).first
            lastPlaces = listOf(s.destination) + s.waypoints
            _state.value = TripState.Guiding(s.region, s.destination, s.waypoints, legs)
            startedAt = System.currentTimeMillis()
            scope.launch { startOdometerKm = vehicle.odometerKm(); persist() }
            stories.startTrip(navi.currentLocation.value, s.destination.point)
            navi.showNavigation()
        }
    }

    /** 현재 위치 → (다음 경유지) → 목적지 경로를 요청한다. */
    private fun requestLeg(legs: TripLegs): Result<Unit> {
        // 새 경로를 요청하면 이전 경로의 취소 이벤트가 먼저 올 수 있으므로, 새 경로 시작 전 취소는 무시한다.
        routeConfirmed = false
        return navi.requestRoute(legs.destination, legs.naviWaypoints())
    }

    /**
     * 달리는 중 경유지 하나를 바로 앞에 추가하고 그 구간을 다시 요청한다(3번 가는 길 동행 "잠깐 들르기").
     * 안내 중이 아니거나 경유지에 서 있는 중이면 실패.
     */
    fun addStopNow(place: Place): Result<Unit> {
        val s = _state.value as? TripState.Guiding ?: return Result.failure(IllegalStateException("안내 중이 아님"))
        val legs = s.legs.insertNext(place) ?: return Result.failure(IllegalStateException("경유지에 서 있는 중"))
        return requestLeg(legs).onSuccess {
            lastPlaces = lastPlaces + place
            _state.value = s.copy(waypoints = legs.waypoints, legs = legs)
            persist()
            navi.showNavigation()
        }
    }

    fun cancel() {
        stories.stopTrip()
        clearResume()
        ignitionOffAtStop.value = false
        parkLog.value = ParkLog()
        _state.value = TripState.Idle
    }

    /** 시연·디버그용: 도착 이벤트 없이 바로 완료 처리. */
    fun forceComplete() {
        when (val s = _state.value) {
            is TripState.Guiding -> scope.launch { complete(s.region) }
            is TripState.Arrived -> scope.launch { complete(s.region) }
            is TripState.Planned -> scope.launch { complete(s.region) }
            else -> Unit
        }
    }

    private suspend fun complete(region: Region) {
        if (_state.value is TripState.Completed) return
        val end = vehicle.odometerKm()
        val distance = if (end != null && startOdometerKm != null) end - startOdometerKm!! else null
        val minutes = if (startedAt > 0) ((System.currentTimeMillis() - startedAt) / 60_000).toInt() else null
        // 실제로 간 장소도 함께 남긴다(기록 카드 사진용): 목적지, 그리고 주차한 구에 있는 경유지.
        val trip = lastPlaces
        val id = visits.markVisited(region.id, distance, minutes, trip.firstOrNull())
        // 가는 길에 주차한 다른 구도 기록한다(거리·시간은 여행 전체 값이라 넣지 않는다).
        val stopIds = parkLog.value.regionsExcept(region.id).map { stop ->
            visits.markVisited(stop, null, null, trip.drop(1).firstOrNull { it.regionId == stop })
        }
        clearResume()
        ignitionOffAtStop.value = false
        parkLog.value = ParkLog()
        _state.value = TripState.Completed(region, id, distance, stopIds)
    }
}
