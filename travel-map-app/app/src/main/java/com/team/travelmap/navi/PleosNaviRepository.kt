package com.team.travelmap.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Guidance
import com.team.travelmap.core.model.TripCost
import com.team.travelmap.core.model.NaviConnection
import com.team.travelmap.core.model.NaviEvent
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.trip.IcPassDetector
import com.team.travelmap.core.trip.IcTurn
import com.team.travelmap.core.trip.UpcomingTurn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 담당: 2번(차량·내비·통합). Pleos NaviHelper 3.0.5 연동.
 *
 * 흐름: NaviHelper(context) → initialize(onConnected, onFailed) → onConnected 이후 addListener·요청
 *       종료 시 removeListener → release (release 후에는 새 객체로 다시 initialize)
 * - 요청 API는 반환값이 없고 결과는 리스너 콜백으로 온다.
 * - 좌표는 (위도, 경도) 순서. 3.0.5 에서 바뀐 부분이니 예전 예제를 그대로 옮기지 말 것.
 *
 * [2번 첫날 체크리스트] 에뮬레이터 logcat(TAG=PleosNavi)에서 아래를 직접 확인하고 주석을 갱신할 것
 *  1) 경로 탐색 완료 시 onRoutingCompleted / onRouteEnded 중 무엇이 오는지 (둘 다 처리해 둠)
 *  2) onCurrentLocationInfo 가 주기적으로 오는지, getCurrentLocationInfo() 호출 때만 오는지
 *  3) poiId 없이 좌표만으로 경로 요청이 되는지
 *  4) onDestinationArrived 가 Virtual Console 주행 시나리오에서 실제로 오는지
 */
class PleosNaviRepository(context: Context) : NaviRepository {

    private val appContext = context.applicationContext

    private val _connection = MutableStateFlow<NaviConnection>(NaviConnection.Disconnected)
    override val connection: StateFlow<NaviConnection> = _connection.asStateFlow()
    private val _guidance = MutableStateFlow(Guidance())
    override val guidance: StateFlow<Guidance> = _guidance.asStateFlow()
    private val _location = MutableStateFlow<GeoPoint?>(null)
    override val currentLocation: StateFlow<GeoPoint?> = _location.asStateFlow()
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    /** requestTrip 응답에는 요청 구분값이 없어서 한 번에 하나만 보낸다. */
    private val tripMutex = Mutex()
    @Volatile private var pendingTrip: CompletableDeferred<TripInfo>? = null
    @Volatile private var pendingTripTarget: GeoPoint? = null

    @Volatile private var helper: NaviHelper? = null
    @Volatile private var connected = false

    /** TBT 목록으로 IC 를 지났는지 판단(고속도로 위 주차 제외용). */
    private val icDetector = IcPassDetector()
    private var lastTbtSize = -1

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            log("onRouteStarted", routeStartInfo)
            _guidance.update { it.copy(active = true) }
            icDetector.reset()
            _events.tryEmit(NaviEvent.RouteStarted)
            requestInfo()
            // 이번 경로의 예상 통행료(원). 음수면 모름.
            _guidance.update { it.copy(tollFareWon = routeStartInfo.tollFare.takeIf { f -> f >= 0 }) }
        }

        override fun onRoutingCompleted() = requestInfo()

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onRouteEnded() = requestInfo() // 에뮬레이터 내비가 아직 이 이벤트를 보낼 수 있음

        override fun onReRouteEnded() = requestInfo()

        override fun onRouteCancelled() {
            log("onRouteCancelled", "")
            _guidance.value = Guidance()
            _events.tryEmit(NaviEvent.RouteCancelled)
        }

        override fun onDestinationChanged() = requestInfo()
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = requestInfo()
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            log("onWayPointArrived", waypointArrivedInfo)
            _events.tryEmit(NaviEvent.WaypointArrived(waypointArrivedInfo.name))
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _guidance.update { it.copy(active = false, remainDistanceMeters = 0, remainTimeSeconds = 0) }
            _events.tryEmit(NaviEvent.DestinationArrived(destinationArrivedInfo.name))
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            _guidance.update {
                it.copy(
                    destinationName = destinationInfo.name,
                    remainDistanceMeters = destinationInfo.remainDistance,
                    remainTimeSeconds = destinationInfo.remainTime,
                )
            }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            log("onNavigationStatus", status)
            if (status == NavigationStatus.ROUTE_DRIVE) {
                _guidance.update { it.copy(active = true) }
                requestInfo()
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _location.value = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            if (cautionPointList.isNotEmpty()) _events.tryEmit(NaviEvent.CautionAhead(cautionPointList.size))
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            log("onTripInfo", tripInfo)
            // 다른 앱이 요청한 응답도 모든 앱에 전달되므로, 도착 지점이 우리가 요청한 곳인지 확인한다.
            val target = pendingTripTarget ?: return
            val end = tripInfo.summary.routePoints.lastOrNull()
            if (end == null || Geo.distanceMeters(target, GeoPoint(end.latitude, end.longitude)) <= TRIP_MATCH_M) {
                pendingTrip?.complete(tripInfo)
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            _events.tryEmit(NaviEvent.Error(errorCode.name))
        }

        // 현재 앱에서 쓰지 않는 콜백. 값 확인용으로 로그만 남긴다.
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = log("onWayPointInfo", waypointInfoList)
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = log("onRouteStateInfo", routeStateInfo)
        override fun onRouteOptionChanged(routeOption: RouteOption) = log("onRouteOptionChanged", routeOption)
        // 다음 회전 안내 목록. IC 진입·진출 안내가 가까이 있다가 사라지면 그 IC 를 지난 것으로 본다.
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val turns = tbtInfoList.map { UpcomingTurn(it.type.toIcTurn(), it.distance) }
            if (turns.size != lastTbtSize) {
                lastTbtSize = turns.size
                log("onTBTInfo", "${turns.size}개, 첫 안내 ${tbtInfoList.firstOrNull()}")
            }
            val exitM = UpcomingTurn.metersTo(turns, IcTurn.EXIT)
            if (_guidance.value.nextIcExitMeters != exitM) _guidance.update { it.copy(nextIcExitMeters = exitM) }
            val passed = icDetector.onTurns(turns) ?: return
            log("icPassed", passed)
            _events.tryEmit(NaviEvent.HighwayChanged(onHighway = passed == IcTurn.ENTER))
        }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = Unit
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = Unit
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
    }

    override fun connect() {
        if (helper != null) return
        _connection.value = NaviConnection.Connecting
        val h = runCatching { NaviHelper(appContext) }.getOrElse {
            fail("NaviHelper 생성 실패", it); return
        }
        helper = h
        runCatching {
            h.initialize(
                onConnected = {
                    if (helper === h) {
                        h.addListener(listener)
                        connected = true
                        _connection.value = NaviConnection.Connected
                        requestInfo()
                    }
                },
                onFailed = { t -> if (helper === h) fail("NaviHelper 연결 실패", t) },
            )
        }.onFailure { helper = null; fail("NaviHelper initialize 예외", it) }
    }

    override fun disconnect() {
        val h = helper ?: return
        helper = null
        connected = false
        runCatching { h.removeListener(listener) }
        runCatching { h.release() }
        _connection.value = NaviConnection.Disconnected
    }

    override fun requestRoute(destination: Place, waypoints: List<Place>): Result<Unit> = command { h ->
        val dest = RouteInfo(
            latitude = destination.point.latitude,
            longitude = destination.point.longitude,
            displayLatitude = null,
            displayLongitude = null,
            poiName = destination.name,
            poiId = destination.poiId.orEmpty(),
            poiSubId = "0",
            address = destination.address.orEmpty(),
            routeOption = RouteOption.RECOMMENDED,
        )
        val wps = waypoints.take(NaviRepository.MAX_WAYPOINTS)
        if (wps.isEmpty()) {
            h.requestRoute(dest)
        } else {
            h.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination = dest,
                    waypoints = wps.mapIndexed { i, p ->
                        RequestWaypointInfo(
                            latitude = p.point.latitude,
                            longitude = p.point.longitude,
                            poiName = p.name,
                            waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                            poiId = p.poiId.orEmpty(),
                            address = p.address.orEmpty(),
                            poiSubId = "0",
                        )
                    },
                ),
            )
        }
        _guidance.update { it.copy(destinationName = destination.name) }
    }

    override fun showNavigation(): Result<Unit> = command { it.requestNavigationScreen(NavigationScreenRequest.Foreground) }

    override fun refreshLocation() {
        command { it.getCurrentLocationInfo() }
    }

    private fun requestInfo() {
        command {
            it.getDestinationInfo()
            it.getCurrentLocationInfo()
        }
    }

    override suspend fun tripCost(to: Place): TripCost? = tripMutex.withLock {
        val reply = CompletableDeferred<TripInfo>()
        pendingTripTarget = to.point
        pendingTrip = reply
        try {
            command { it.requestTrip(TripRequest(to.point.latitude, to.point.longitude, to.poiId.orEmpty(), to.name, null)) }
                .getOrElse { return@withLock null }
            val info = withTimeoutOrNull(TRIP_TIMEOUT_MS) { reply.await() } ?: return@withLock null
            TripCost(
                distanceMeters = info.distance,
                durationSeconds = info.eta,
                socAtDestPercent = info.estimatedSocAtDest.takeIf { it in 0..100 },
                estimated = false,
            )
        } finally {
            pendingTrip = null
            pendingTripTarget = null
        }
    }

    private inline fun command(block: (NaviHelper) -> Unit): Result<Unit> {
        val h = helper
        if (h == null || !connected) return Result.failure(IllegalStateException("내비에 연결되지 않음"))
        return runCatching { block(h) }.onFailure { Log.w(TAG, "command failed", it) }
    }

    private fun fail(msg: String, t: Throwable) {
        Log.w(TAG, msg, t)
        connected = false
        _connection.value = NaviConnection.Failed("$msg: ${t.message}")
        _events.tryEmit(NaviEvent.Error(msg))
    }

    private fun log(name: String, value: Any) {
        Log.d(TAG, "$name $value")
    }

    private fun TurnType.toIcTurn(): IcTurn? = when (this) {
        TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_C -> IcTurn.ENTER
        TurnType.TURN_EXIT_IC_R, TurnType.TURN_EXIT_IC_L, TurnType.TURN_EXIT_IC_C -> IcTurn.EXIT
        else -> null
    }

    private companion object { const val TAG = "PleosNavi" }
}

/** requestTrip 응답 대기 시간. 에뮬레이터에서 응답까지 약 3초 걸렸다. */
private const val TRIP_TIMEOUT_MS = 8_000L
/** 내비가 도착 지점을 가까운 도로로 옮기므로 이 정도 차이는 같은 곳으로 본다. */
private const val TRIP_MATCH_M = 1_000.0
