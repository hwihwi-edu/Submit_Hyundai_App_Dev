package com.team.travelmap.core.geo

import com.team.travelmap.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 지점 사이 직선 거리(m). */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** 점이 다각형(외곽 링) 안에 있는지. 지도 터치 → 시·군·구 판정에 쓴다. */
    fun contains(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** a→b 를 t(0..1) 비율로 잇는 점. Mock 내비 주행 시뮬레이션에 쓴다. */
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )
}
