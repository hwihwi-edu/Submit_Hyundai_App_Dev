package com.team.travelmap.map

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 담당: 1번(지도·화면). 도시 확대 보기에서 구·군 이름을 구역 안에 겹치지 않게 놓는 순수 로직.
 * design/chaum-map-preview.html 의 layoutDistricts 를 옮긴 것이다(좌표 단위는 화면 px).
 *
 * 규칙
 *  - 이름 후보: 한 줄 12sp → 한 줄 11sp → (3글자 이상이면) 두 줄 11sp. 이름은 줄이지 않는다.
 *  - 기준점: 대표 지점(center)이 구역 안이면 우선, 그다음 구역 안의 격자점.
 *  - 점수: (상자 모서리·변 중앙 9점 중 구역 밖에 나간 점 수) × 1000 + 후보 순서 × 16 + 대표 지점과의 거리.
 *  - 공간이 좁은(구역 안에 다 들어가는 후보가 적은) 구부터 자리를 잡고, 다른 이름·제목 배지와 겹치지 않게 한다.
 *  - 끝내 구역 밖으로 삐져나간 이름은 needsHalo = true(밝은 테두리로 읽히게).
 */
object DistrictLabelLayout {

    /** 화면 px 사각형. x, y 는 중심. */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun intersects(o: Box) = kotlin.math.abs(x - o.x) < (w + o.w) / 2 + 1 && kotlin.math.abs(y - o.y) < (h + o.h) / 2 + 1
    }

    /** 이름 표기 하나. w, h 는 측정한 글자 크기 + 여백(px). */
    data class Format(val text: String, val fontSizeSp: Float, val w: Float, val h: Float)

    /** rings: 화면 px 로 투영한 다각형 고리들. [x0, y0, x1, y1, ...] */
    data class District<K>(
        val key: K,
        val rings: List<FloatArray>,
        val centerX: Float,
        val centerY: Float,
        val formats: List<Format>,
    )

    /** placedFreely = 다른 이름·배지와 겹치지 않는 자리를 찾았는지(false 면 겹친 자리에 놓임). */
    data class Placed<K>(val key: K, val box: Box, val format: Format, val needsHalo: Boolean, val placedFreely: Boolean = true)

    /** 측정 전 이름 표기 후보 (글자, sp). 우선순위 순. */
    fun candidateTexts(name: String): List<Pair<String, Float>> = buildList {
        add(name to 12f)
        add(name to 11f)
        if (name.length >= 3) {
            val split = ceil(name.length / 2.0).toInt()
            add(name.substring(0, split) + "\n" + name.substring(split) to 11f)
        }
    }

    /** 짝홀 규칙의 다각형 포함 판정. 지도 채우기(EvenOdd)와 같은 규칙이라 구멍 있는 구역도 일치한다. */
    fun inside(x: Float, y: Float, rings: List<FloatArray>): Boolean {
        var hit = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]
                val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit
                j = i
            }
        }
        return hit
    }

    private class Candidate(val box: Box, val format: Format, val missing: Int, val score: Float)

    fun <K> layout(
        districts: List<District<K>>,
        width: Float,
        height: Float,
        reserved: List<Box> = emptyList(),
        margin: Float = 3f,
        minStep: Float = 3f,
        maxAnchors: Int = 500,
    ): List<Placed<K>> {
        val occupied = reserved.toMutableList()
        fun valid(b: Box) = b.x - b.w / 2 >= margin && b.x + b.w / 2 <= width - margin &&
            b.y - b.h / 2 >= margin && b.y + b.h / 2 <= height - margin &&
            occupied.none { it.intersects(b) }

        val scored = districts.map { d ->
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            d.rings.forEach { r ->
                for (i in 0 until r.size / 2) {
                    minX = minOf(minX, r[2 * i]); maxX = maxOf(maxX, r[2 * i])
                    minY = minOf(minY, r[2 * i + 1]); maxY = maxOf(maxY, r[2 * i + 1])
                }
            }
            // 큰 화면에서도 계산량이 일정하도록 격자 간격을 넓힌다(시안은 3px 고정).
            val step = max(minStep, sqrt((maxX - minX) * (maxY - minY) / maxAnchors))
            val anchors = ArrayList<Pair<Float, Float>>()
            if (inside(d.centerX, d.centerY, d.rings)) anchors += d.centerX to d.centerY
            var y = minY + 2
            while (y < maxY) {
                var x = minX + 2
                while (x < maxX) {
                    if (inside(x, y, d.rings)) anchors += x to y
                    x += step
                }
                y += step
            }
            // 너무 작아 격자점이 없어도 이름은 반드시 표시한다.
            if (anchors.isEmpty()) anchors += d.centerX to d.centerY
            val offsets = floatArrayOf(-1f, 0f, 1f)
            val candidates = d.formats.flatMapIndexed { priority, f ->
                anchors.map { (ax, ay) ->
                    var missing = 0
                    for (sx in offsets) for (sy in offsets) {
                        if (!inside(ax + sx * (f.w / 2 - 2), ay + sy * (f.h / 2 - 2), d.rings)) missing++
                    }
                    Candidate(
                        Box(ax, ay, f.w, f.h), f, missing,
                        missing * 1000f + priority * 16f + hypot(ax - d.centerX, ay - d.centerY),
                    )
                }
            }.sortedBy { it.score }
            Triple(d, candidates, candidates.count { it.missing == 0 })
        }

        val placed = HashMap<K, Placed<K>>()
        scored.sortedBy { it.third }.forEach { (d, candidates, _) ->
            val free = candidates.firstOrNull { valid(it.box) }
            val c = free ?: candidates.firstOrNull() ?: return@forEach
            occupied += c.box
            placed[d.key] = Placed(d.key, c.box, c.format, c.missing > 0, placedFreely = free != null)
        }
        return districts.mapNotNull { placed[it.key] }
    }
}
