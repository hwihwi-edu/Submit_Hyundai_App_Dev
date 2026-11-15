package com.team.travelmap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.team.travelmap.core.trip.TripCoordinator
import com.team.travelmap.curation.CurationRepository
import com.team.travelmap.curation.companion.companionCandidates
import com.team.travelmap.drive.DrivingRestrictions
import com.team.travelmap.map.RegionRepository
import com.team.travelmap.navi.MockNaviRepository
import com.team.travelmap.navi.NaviRepository
import com.team.travelmap.navi.PleosNaviRepository
import com.team.travelmap.roulette.RoulettePicker
import com.team.travelmap.settings.AppSettings
import com.team.travelmap.story.StoryEngine
import com.team.travelmap.story.StoryPlayer
import com.team.travelmap.story.StoryRepository
import com.team.travelmap.vehicle.MockVehicleRepository
import com.team.travelmap.vehicle.PleosVehicleRepository
import com.team.travelmap.vehicle.VehicleRepository
import com.team.travelmap.visit.AppDatabase
import com.team.travelmap.visit.VisitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 수동 의존성 주입. BuildConfig.CAR_BACKEND 로 Pleos / Mock 구현을 고른다.
 *  - "auto"  : Pleos 차량 서비스가 있으면 Pleos, 없으면 Mock (기본값)
 *  - "pleos" : 항상 Pleos
 *  - "mock"  : 항상 Mock (SDK 연동 전 UI·로직 개발용)
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 1번 개발자 화면의 "가짜 내비·차량" 켜기(다음 실행부터). */
    val devForcedMock: Boolean = appContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("devForceMock", false)

    val usePleos: Boolean = if (devForcedMock) false else when (BuildConfig.CAR_BACKEND) {
        "pleos" -> true
        "mock" -> false
        else -> appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            runCatching {
                appContext.packageManager
                    .queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0)
                    .isNotEmpty()
            }.getOrDefault(false)
    }

    val vehicle: VehicleRepository = if (usePleos) PleosVehicleRepository(appContext) else MockVehicleRepository()
    val navi: NaviRepository = if (usePleos) PleosNaviRepository(appContext) else MockNaviRepository(scope, gear = vehicle.gear)
    val restrictions = DrivingRestrictions(appContext)

    val regions = RegionRepository(appContext)
    val curation = CurationRepository(appContext)
    /** 관광 데이터(1만여 곳)·테마 카드. 처음 읽을 때 무거우므로 한 번만 만들고 화면 시작 시 IO 에서 미리 읽는다(1번). */
    val tourism by lazy { com.team.travelmap.curation.TourismRepository(appContext) }
    val themeCuration by lazy { com.team.travelmap.curation.ThemeCurationRepository(appContext) }
    /** 코스 도착 시각 검사. 원본 관광 저장소와 검수 결과를 앱 수명 동안 공유한다. */
    val operatingHours by lazy { com.team.travelmap.curation.hours.OperatingHoursRepository(appContext, tourism) }
    /** 키가 없는 팀원 빌드에서는 MISSING_KEY로 반환한다. 실제 값은 local.properties에서 주입한다. */
    val weather by lazy { com.team.travelmap.curation.weather.KmaWeatherRepository(serviceKey = { BuildConfig.KMA_SERVICE_KEY }) }
    val courseWeather by lazy { com.team.travelmap.curation.weather.CourseWeatherRecommender(load = weather::load) }
    val roulette = RoulettePicker()
    /** 설정(담당: 1번). 2번·3번은 읽기만 한다. */
    val settings = AppSettings(appContext)
    private val db = AppDatabase.create(appContext)
    val visits = VisitRepository(db.visitDao(), settings)
    /** 경유지 리뷰(1번 DB). */
    val reviews = com.team.travelmap.visit.ReviewRepository(db.reviewDao())
    /** 3번 "핫" 표시: 재방문 의사 응답(실제 리뷰 + 시연용 예시)으로 장소별 통계. */
    val hot by lazy { com.team.travelmap.curation.hot.HotRepository(appContext, scope, tourism, reviews.reviews) }

    val storyPlayer = StoryPlayer(appContext, initialVolume = settings.state.value.guideVolume)
    val stories = StoryEngine(scope, navi, StoryRepository(appContext), storyPlayer,
        autoplayEnabled = { settings.state.value.storyAutoplay })

    init {
        scope.launch {
            settings.state.map { it.guideVolume }.distinctUntilChanged().collect { storyPlayer.setVolume(it) }
        }
    }

    /** 시동 상태: 차량 화면 켜짐/꺼짐으로 판단(2번). Pleos·Mock 모드 공통. */
    val ignition = com.team.travelmap.vehicle.ScreenIgnitionMonitor(appContext)

    val trip = TripCoordinator(
        scope, navi, vehicle, visits, stories, settings.state,
        ignition = ignition.state,
        store = com.team.travelmap.core.trip.PrefsTripStore(appContext),
        // 주차한 곳의 구: 지도 경계(regions.json) 안에 드는지로 판정. 처음엔 파일을 읽으므로 기본 스레드에서.
        regionAt = { p ->
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                regions.shapes.firstOrNull { s -> s.polygons.any { com.team.travelmap.core.geo.Geo.contains(it, p) } }?.region?.id
            }
        },
    )

    /** 3번 음성 해설: Pleos TTS. Pleos 음성 서비스가 없는 기기에서는 스스로 꺼지고 글만 보여 준다. */
    val narrator: com.team.travelmap.curation.companion.PlaceNarrator =
        com.team.travelmap.curation.companion.PleosPlaceNarrator(appContext) { speaking ->
            if (speaking) storyPlayer.holdForSpeech() else storyPlayer.releaseAfterSpeech()
        }
    private val voiceOn = settings.state.map { it.voiceGuide }.stateIn(scope, SharingStarted.Eagerly, settings.state.value.voiceGuide)

    /**
     * 3번 곧 도착 안내: 다음 경유지·목적지 2km 안에서 그곳 소개를 카드·음성으로 한 번.
     * 소개 글: 코스 장소 설명 → 같은 이름(또는 150m 안) 관광공사 소개문.
     */
    val approach = com.team.travelmap.curation.companion.StopApproachAnnouncer(
        scope = scope, trip = trip.state, location = navi.currentLocation, voiceEnabled = voiceOn,
        narrator = narrator,
        describe = { p ->
            p.description?.takeIf { it.isNotBlank() } ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
                val entry = tourism.all.firstOrNull { it.name == p.name }
                    ?: tourism.all.filter { com.team.travelmap.core.geo.Geo.distanceMeters(com.team.travelmap.core.model.GeoPoint(it.lat, it.lng), p.point) <= 150.0 }
                        .minByOrNull { com.team.travelmap.core.geo.Geo.distanceMeters(com.team.travelmap.core.model.GeoPoint(it.lat, it.lng), p.point) }
                entry?.let { tourism.detailsOf(it.contentId).description }?.takeIf { it.isNotBlank() }
            }
        },
    )

    /**
     * 3번 가는 길 동행: 안내 중 경로 앞쪽의 볼만한 곳을 하나씩 제안. 곧 도착 안내가 떠 있으면 묻지 않음(설정 "주변 장소 추천"이 켜져 있을 때).
     * 화면은 companion.suggestion 을 구독, "잠깐 들르기"는 trip.addStopNow 로 경유지 추가.
     */
    /** 3번 말로 대답하기: Pleos STT. 개발자 화면 "말로 대답 확인"도 같은 것을 쓴다. */
    val answers: com.team.travelmap.curation.companion.AnswerListener =
        com.team.travelmap.curation.companion.PleosAnswerListener(appContext)

    val companion = com.team.travelmap.curation.companion.RouteCompanionController(
        scope = scope, trip = trip.state, location = navi.currentLocation, onHighway = trip.onHighway,
        nextIcExitMeters = navi.guidance.map { it.nextIcExitMeters }.stateIn(scope, SharingStarted.Eagerly, null),
        enabled = settings.state.map { it.nearbyCards }.stateIn(scope, SharingStarted.Eagerly, settings.state.value.nearbyCards),
        loadCandidates = {
            tourism.companionCandidates(curatedIds = themeCuration.curatedIds, stories = StoryRepository(appContext).all)
        },
        narrator = narrator, voiceEnabled = voiceOn, isStoryPlaying = { storyPlayer.nowPlaying.value != null },
        blocked = approach.notice.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false),
        answers = answers,
        answerEnabled = settings.state.map { it.voiceGuide && it.voiceAnswer }
            .stateIn(scope, SharingStarted.Eagerly, settings.state.value.voiceGuide && settings.state.value.voiceAnswer),
        addStop = { p -> trip.addStopNow(p) },
        playRecorded = { id -> stories.storyById(id)?.let { stories.playNow(it) } ?: false },
    )

}
