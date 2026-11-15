package com.team.travelmap.core.trip

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.RegionId

/**
 * 담당: 2번(차량·내비·통합). 여행 중 주차(P)한 시·군·구를 모은다(2차 기획안: 코스 중 주차한 구 모두 기록).
 *
 * - 출발 전 P(출발지에 서 있던 상태)는 세지 않는다. 이번 여행에서 한 번이라도 움직인(D·R) 뒤의 P 만 센다.
 * - 고속도로(IC 진입 ~ IC 진출 사이)에서의 P 는 세지 않는다. 휴게소·졸음쉼터 정차가 여기에 해당한다.
 * - 같은 구는 한 번만 담는다. 목적지 구는 여행 완료 때 따로 기록하므로 [regionsExcept] 로 뺀다.
 */
data class ParkLog(
    /** 이번 여행에서 움직인 적이 있는지. */
    val departed: Boolean = false,
    /** 지금 고속도로 위인지(IC 진입 후 진출 전). */
    val onHighway: Boolean = false,
    /** 주차한 구(순서대로, 중복 없음). */
    val regions: List<RegionId> = emptyList(),
) {
    fun onIc(turn: IcTurn): ParkLog = copy(onHighway = turn == IcTurn.ENTER)

    /** 기어가 바뀌었을 때. 두 번째 값이 true 면 지금 위치의 구를 [add] 해야 한다. */
    fun onGear(gear: Gear): Pair<ParkLog, Boolean> = when (gear) {
        Gear.DRIVE, Gear.REVERSE -> (if (departed) this else copy(departed = true)) to false
        Gear.PARK -> this to (departed && !onHighway)
        else -> this to false
    }

    fun add(id: RegionId): ParkLog = if (id in regions) this else copy(regions = regions + id)

    fun regionsExcept(id: RegionId): List<RegionId> = regions.filter { it != id }
}

/** 고속도로 나들목(IC) 진입·진출. */
enum class IcTurn { ENTER, EXIT }

/**
 * 내비 TBT(회전 안내) 한 건. IC 진입·진출이 아니면 [ic] = null.
 * [distanceM] 은 **앞 회전에서부터** 이 회전까지 거리다(첫 항목은 현재 위치부터). 에뮬레이터 확인:
 * onTBTInfo 는 경로 전체 회전 목록(IC·톨게이트·휴게소 포함)을 주고, 거리 합이 경로 전체 거리와 같다.
 */
data class UpcomingTurn(val ic: IcTurn?, val distanceM: Double) {
    companion object {
        /** 현재 위치에서 목록 중 첫 [ic] 회전까지 누적 거리(m). 없으면 null. */
        fun metersTo(turns: List<UpcomingTurn>, ic: IcTurn): Int? {
            var sum = 0.0
            for (t in turns) {
                sum += t.distanceM
                if (t.ic == ic) return sum.toInt()
            }
            return null
        }
    }
}

/**
 * 내비 TBT 목록으로 IC 를 "지났는지" 판단한다.
 *
 * Pleos 내비는 앞으로의 회전 안내 목록(가까운 순)만 주고 "IC 를 지났다"는 이벤트는 없다.
 * 그래서 바로 앞 안내가 IC 진입·진출이고 [passWithinM] 이내로 가까웠는데,
 * 다음 목록에서 그 안내가 사라지면(다른 안내로 바뀌거나 목록이 비면) 지난 것으로 본다.
 * 멀리 있던 IC 안내가 사라진 것은 경로가 바뀐 것이므로 지난 것으로 보지 않는다.
 */
class IcPassDetector(private val passWithinM: Double = PASS_WITHIN_M) {
    private var pending: UpcomingTurn? = null

    /** 새 TBT 목록. IC 를 지났으면 그 종류를, 아니면 null. */
    fun onTurns(turns: List<UpcomingTurn>): IcTurn? {
        val next = turns.firstOrNull()
        val prev = pending
        pending = next?.takeIf { it.ic != null }
        if (prev == null) return null
        // 같은 IC 안내가 아직 앞에 있음(거리가 줄었거나 비슷함) → 아직 안 지남
        val stillAhead = next != null && next.ic == prev.ic && next.distanceM <= prev.distanceM + SAME_SLACK_M
        return if (!stillAhead && prev.distanceM <= passWithinM) prev.ic else null
    }

    /** 새 경로가 시작되면 이전 경로의 안내는 잊는다. */
    fun reset() { pending = null }

    companion object {
        const val PASS_WITHIN_M = 300.0
        /** 안내 거리 값이 조금 흔들려도 같은 IC 로 본다. */
        private const val SAME_SLACK_M = 50.0
    }
}
