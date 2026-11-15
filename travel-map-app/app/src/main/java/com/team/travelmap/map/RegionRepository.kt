package com.team.travelmap.map

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 1번(지도·화면). assets/regions.json 을 읽어 시·군·구 목록과 경계(다각형)를 제공한다.
 * regions.json 은 tools/build_regions.py 로 행정구역 경계 GeoJSON 에서 만든다(README 참고).
 *
 * 형식: [{ "code":"44770", "sido":"충청남도", "name":"서천군", "center":[위도,경도],
 *          "polygons":[ [[위도,경도], ...], ... ] }]   ← 좌표는 (위도, 경도) 순서
 *
 * 도시 확대 보기는 assets/pilot-city-boundaries.json(시범 46개 구·군 정밀 경계)을 쓴다.
 *  형식: [{ "code":"30200", "name":"유성구", "polygons":[ [[위도,경도], ...], ... ] }]
 *  출처: 국가데이터처 SGIS 2025년 2분기 시군구 경계(공공데이터포털 15129688) 가공.
 *  인접 구가 경계선을 공유하므로 구 사이에 틈이 생기지 않고, 부산의 해안선·섬도 유지된다.
 *  Region(이름·대표 지점·ID)은 regions.json 의 것을 그대로 써서 전국/확대 보기의 지역 키가 같다.
 */
data class RegionShape(val region: Region, val polygons: List<List<GeoPoint>>)

class RegionRepository(private val context: Context) {

    @Serializable
    private data class RegionDto(
        val code: String,
        val sido: String,
        val name: String,
        val center: List<Double>,
        val polygons: List<List<List<Double>>>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val shapes: List<RegionShape> by lazy {
        val text = context.assets.open("regions.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<List<RegionDto>>(text).map { dto ->
            RegionShape(
                region = Region(
                    id = RegionId(dto.code),
                    sidoName = dto.sido,
                    name = dto.name,
                    center = GeoPoint(dto.center[0], dto.center[1]),
                ),
                polygons = dto.polygons.map { ring -> ring.map { GeoPoint(it[0], it[1]) } },
            )
        }
    }

    @Serializable
    private data class DetailDto(
        val code: String,
        val name: String,
        val polygons: List<List<List<Double>>>,
    )

    /** 도시 확대용 정밀 경계. 정밀 데이터가 없는 지역은 전국 경계로 대신한다. */
    val cityShapes: List<RegionShape> by lazy {
        val text = context.assets.open("pilot-city-boundaries.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val detail = json.decodeFromString<List<DetailDto>>(text).associateBy { it.code }
        shapes.map { shape ->
            val d = detail[shape.region.id.code] ?: return@map shape
            shape.copy(polygons = d.polygons.map { ring -> ring.map { GeoPoint(it[0], it[1]) } })
        }
    }

    /** 시·도 하나의 확대 보기용 경계(정밀 데이터). */
    fun cityShapesOf(sidoCode: String): List<RegionShape> = cityShapes.filter { it.region.id.sidoCode == sidoCode }

    val regions: List<Region> get() = shapes.map { it.region }

    fun find(id: RegionId): Region? = shapes.firstOrNull { it.region.id == id }?.region
}
