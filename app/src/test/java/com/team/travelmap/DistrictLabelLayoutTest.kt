package com.team.travelmap

import com.team.travelmap.map.DistrictLabelLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.min

/**
 * 도시 확대 보기의 구·군 이름 배치를 실제 정밀 경계(assets/pilot-city-boundaries.json)로 검사한다.
 * 화면 크기는 차량 화면 왼쪽 지도 영역 정도(px)로 잡고, 글자 폭은 한글 한 글자 = 글자 크기 × 밀도로 근사한다.
 */
class DistrictLabelLayoutTest {

    @Serializable
    private data class Dto(val code: String, val name: String, val polygons: List<List<List<Double>>>)

    private val regions: List<Dto> by lazy {
        val f = listOf("src/main/assets/pilot-city-boundaries.json", "app/src/main/assets/pilot-city-boundaries.json")
            .map(::File).first { it.exists() }
        Json.decodeFromString<List<Dto>>(f.readText())
    }

    private fun layoutCity(sido: String, width: Float, height: Float, density: Float): Triple<List<Dto>, List<DistrictLabelLayout.Placed<String>>, List<DistrictLabelLayout.District<String>>> {
        val city = regions.filter { it.code.startsWith(sido) }
        val pts = city.flatMap { it.polygons.flatten() }
        val minLat = pts.minOf { it[0] }; val maxLat = pts.maxOf { it[0] }
        val minLng = pts.minOf { it[1] }; val maxLng = pts.maxOf { it[1] }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val pad = 16 * density
        val scale = min((width - 2 * pad) / ((maxLng - minLng) * kx), (height - 2 * pad) / (maxLat - minLat))
        val ox = (width - (maxLng - minLng) * kx * scale) / 2
        val oy = (height - (maxLat - minLat) * scale) / 2
        fun px(lat: Double, lng: Double) = floatArrayOf(
            (ox + (lng - minLng) * kx * scale).toFloat(), (oy + (maxLat - lat) * scale).toFloat(),
        )
        val districts = city.map { r ->
            val rings = r.polygons.map { ring -> ring.flatMap { px(it[0], it[1]).toList() }.toFloatArray() }
            // 대표 지점 대신 가장 큰 고리의 평균점(테스트용)
            val big = rings.maxBy { it.size }
            val cx = (0 until big.size / 2).map { big[2 * it] }.average().toFloat()
            val cy = (0 until big.size / 2).map { big[2 * it + 1] }.average().toFloat()
            DistrictLabelLayout.District(
                key = r.code, rings = rings, centerX = cx, centerY = cy,
                formats = DistrictLabelLayout.candidateTexts(r.name).map { (t, sp) ->
                    val lines = t.split("\n")
                    DistrictLabelLayout.Format(
                        t, sp,
                        w = lines.maxOf { it.length } * sp * density + 4 * density,
                        h = lines.size * sp * 1.08f * density + 4 * density,
                    )
                },
            )
        }
        return Triple(city, DistrictLabelLayout.layout(districts, width, height, margin = 3 * density), districts)
    }

    @Test
    fun `시범 46개 구·군 경계가 모두 있다`() {
        assertEquals(46, regions.size)
        assertEquals(25, regions.count { it.code.startsWith("11") })
        assertEquals(16, regions.count { it.code.startsWith("26") })
        assertEquals(5, regions.count { it.code.startsWith("30") })
    }

    @Test
    fun `세 도시 모두 이름을 빠짐없이 전체 이름으로 겹치지 않게 놓는다`() {
        for (sido in listOf("11", "30", "26")) {
            // 차량 가로 화면(1920x1080, 밀도 1.5)의 지도 영역 근사
            val (city, placed, _) = layoutCity(sido, width = 940f, height = 900f, density = 1.5f)
            assertEquals("$sido 이름 수", city.size, placed.size)
            placed.forEach { p ->
                val name = city.first { it.code == p.key }.name
                assertEquals("이름을 줄이지 않는다", name, p.format.text.replace("\n", ""))
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("$sido ${placed[i].key}/${placed[j].key} 겹침", placed[i].box.intersects(placed[j].box))
            }
            println("$sido: " + placed.joinToString { "${it.format.text.replace("\n", "/")}${if (it.needsHalo) "*" else ""}" })
        }
    }

    @Test
    fun `글자 중심은 항상 자기 구역 안이라 글자와 구역 터치가 같은 지역을 고른다`() {
        for (sido in listOf("11", "30", "26")) {
            val (city, placed, districts) = layoutCity(sido, width = 940f, height = 900f, density = 1.5f)
            placed.forEach { p ->
                val d = districts.first { it.key == p.key }
                val name = city.first { it.code == p.key }.name
                assertTrue("$name 글자 중심이 구역 밖", DistrictLabelLayout.inside(p.box.x, p.box.y, d.rings))
                val others = districts.filter { it.key != p.key }
                assertFalse("$name 글자 중심이 다른 구역 위", others.any { DistrictLabelLayout.inside(p.box.x, p.box.y, it.rings) })
            }
        }
        // 부산진구·동래구는 이름 전체가 구역 안에 들어간다
        val (city, placed, _) = layoutCity("26", width = 940f, height = 900f, density = 1.5f)
        listOf("부산진구", "동래구").forEach { name ->
            assertFalse("$name 은 구역 안에 들어가야 한다", placed.first { it.key == city.first { c -> c.name == name }.code }.needsHalo)
        }
    }
}
