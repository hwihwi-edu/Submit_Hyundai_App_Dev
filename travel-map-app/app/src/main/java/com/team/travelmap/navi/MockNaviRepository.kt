package com.team.travelmap.navi

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Guidance
import com.team.travelmap.core.model.NaviConnection
import com.team.travelmap.core.model.NaviEvent
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.TripCost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 가짜 내비. 경로를 요청하면 출발지→(첫 경유지)→목적지를 [steps]번에 나눠 이동하며
 * 남은 거리·위치를 갱신하고, 끝나면 DestinationArrived 를 보낸다.
 * 경유지가 있으면 도착 시 WaypointArrived 를 보내고 [waypointStopSteps]번 멈춘 뒤 목적지로 계속 간다
 * (실제 내비처럼 경유지에서 안내를 끝내지 않는다). 멈춘 동안 P → D 로 다음 구간을 시험할 수 있다.
 * 구간 직선 거리가 [highwayMinMeters] 이상이면 구간의 25% 지점에서 IC 진입, 75% 지점에서 IC 진출
 * (HighwayChanged)을 보낸다. 그 사이에 P 를 넣으면 고속도로 위 주차(휴게소)로 보고 방문 기록에서 빠진다.
 * 기어가 P 인 동안은 멈춘다(실제 차처럼). 출발 후 D 를 넣어야 움직이고, 경유지에서 P 를 넣으면 D 전까지 서 있다.
 * 오디오 스토리·도착 인증·경유지 구간 안내를 실제 SDK 없이 시험할 때 쓴다.
 */
class MockNaviRepository(
    private val scope: CoroutineScope,
    private val start: GeoPoint = GeoPoint(37.5663, 126.9779), // 서울시청
    private val steps: Int = 60,
    private val stepMillis: Long = 1_000,
    private val waypointStopSteps: Int = 15,
    private val highwayMinMeters: Double = 20_000.0,
    /** 차량 기어. P 인 동안은 차가 서 있으므로 가짜 주행도 멈춘다(경유지 리뷰 중 혼자 목적지에 도착하지 않게). */
    private val gear: StateFlow<Gear>? = null,
) : NaviRepository {

    private val _connection = MutableStateFlow<NaviConnection>(NaviConnection.Disconnected)
    override val connection: StateFlow<NaviConnection> = _connection.asStateFlow()
    private val _guidance = MutableStateFlow(Guidance())
    override val guidance: StateFlow<Guidance> = _guidance.asStateFlow()
    private val _location = MutableStateFlow<GeoPoint?>(start)
    override val currentLocation: StateFlow<GeoPoint?> = _location.asStateFlow()
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 32)
    override val events = _events.asSharedFlow()

    private var job: Job? = null

    override fun connect() { _connection.value = NaviConnection.Connected }
    override fun disconnect() { job?.cancel(); _connection.value = NaviConnection.Disconnected }

    override fun requestRoute(destination: Place, waypoints: List<Place>): Result<Unit> {
        job?.cancel()
        val from = _location.value ?: start
        // 실제 내비처럼 경유지는 최대 2곳까지만 받는다. Mock 은 첫 경유지만 들른다.
        val waypoint = waypoints.take(NaviRepository.MAX_WAYPOINTS).firstOrNull()
        val path = listOfNotNull(from, waypoint?.point, destination.point)
        val totalM = path.zipWithNext { a, b -> Geo.distanceMeters(a, b) }.sum() * 1.3
        job = scope.launch {
            _events.emit(NaviEvent.RouteStarted)
            // 경유지가 있으면 앞 절반은 경유지까지, 뒤 절반은 목적지까지
            val legSteps = if (waypoint == null) listOf(steps) else listOf(steps / 2, steps - steps / 2)
            var done = 0
            path.zipWithNext().forEachIndexed { leg, (a, b) ->
                val n = legSteps[leg]
                val legRoadM = Geo.distanceMeters(a, b) * 1.3
                val highway = legRoadM / 1.3 >= highwayMinMeters
                val enterAt = n / 4
                val exitAt = n * 3 / 4
                for (i in 0..n) {
                    if (highway && i == enterAt) _events.emit(NaviEvent.HighwayChanged(onHighway = true))
                    if (highway && i == exitAt) _events.emit(NaviEvent.HighwayChanged(onHighway = false))
                    val t = i.toDouble() / n
                    _location.value = Geo.lerp(a, b, t)
                    val overall = (done + i).toDouble() / steps
                    _guidance.value = Guidance(
                        active = true,
                        tollFareWon = mockToll(totalM),
                        // 고속도로 위에서는 진출 IC(구간 75% 지점)까지 남은 거리
                        nextIcExitMeters = if (highway && i in enterAt until exitAt) (legRoadM * (exitAt - i) / n).toInt() else null,
                        destinationName = destination.name,
                        remainDistanceMeters = (totalM * (1 - overall)).toInt(),
                        remainTimeSeconds = (totalM * (1 - overall) / MOCK_SPEED_MPS).toInt(), // 평균 60km/h 로 가정한 표시용 시간
                    )
                    step()
                }
                done += n
                if (waypoint != null && leg == 0) {
                    _events.emit(NaviEvent.WaypointArrived(waypoint.name))
                    repeat(waypointStopSteps) { step() }
                }
            }
            _guidance.value = Guidance(active = false, destinationName = destination.name, remainDistanceMeters = 0, remainTimeSeconds = 0)
            _events.emit(NaviEvent.DestinationArrived(destination.name))
        }
        return Result.success(Unit)
    }

    /** 한 단계 진행. 기어가 P 이면 P 가 풀릴 때까지 기다린다. */
    private suspend fun step() {
        delay(stepMillis)
        gear?.first { it != Gear.PARK }
    }

    override fun showNavigation(): Result<Unit> = Result.success(Unit)
    override fun refreshLocation() = Unit

    /** 직선거리 × 1.3, 평균 60km/h 로 어림한 값(estimated = true). */
    override suspend fun tripCost(to: Place): TripCost {
        val m = Geo.distanceMeters(_location.value ?: start, to.point) * 1.3
        return TripCost(m.toInt(), (m / MOCK_SPEED_MPS).toInt(), socAtDestPercent = null, estimated = true)
    }

    /** 20km 이상이면 고속도로를 탄다고 보고 km 당 50원(표시 확인용 가짜 값). */
    private fun mockToll(totalM: Double): Int = if (totalM >= 20_000) (totalM / 1000 * 50).toInt() / 100 * 100 else 0
}

/** Mock 남은 시간 표시용 평균 속도(60km/h). */
private const val MOCK_SPEED_MPS = 60_000.0 / 3600.0
