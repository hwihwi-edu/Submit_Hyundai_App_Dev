package com.team.travelmap.curation.weather

import com.team.travelmap.core.model.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class WeatherHttpResponse(val status: Int, val body: String)

fun interface WeatherTransport {
    fun get(operation: String, params: Map<String, String>): WeatherHttpResponse
}

/** URL·요청 파라미터·응답 본문은 로깅하지 않는다. Decoding 일반 인증키를 한 번만 인코딩한다. */
class KmaHttpsTransport : WeatherTransport {
    override fun get(operation: String, params: Map<String, String>): WeatherHttpResponse {
        require(WeatherKind.entries.any { it.operation == operation })
        val query = params.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val connection = URL("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/$operation?$query")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
            WeatherHttpResponse(status, body)
        } finally { connection.disconnect() }
    }
}

/** 담당: 3번. 설정 off 시 호출 없음. 오래된 캐시·샘플 날씨로 실제 예보를 대체하지 않는다. */
class KmaWeatherRepository(
    private val serviceKey: () -> String,
    private val transport: WeatherTransport = KmaHttpsTransport(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun load(point: GeoPoint, kind: WeatherKind, enabled: Boolean): WeatherResult {
        if (!enabled) return WeatherResult.Disabled
        val key = serviceKey().trim()
        if (key.isEmpty()) return WeatherResult.Failure(WeatherFailure.MISSING_KEY)
        return withContext(Dispatchers.IO) {
            try {
                val grid = KmaGrid.from(point)
                val issued = KmaSchedule.latest(kind, LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul")))
                val rows = mutableListOf<JsonObject>()
                var page = 1
                var expectedTotal: Int? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val response = transport.get(kind.operation, mapOf(
                        "serviceKey" to key, "dataType" to "JSON", "pageNo" to page.toString(), "numOfRows" to "1000",
                        "base_date" to issued.format(DateTimeFormatter.BASIC_ISO_DATE),
                        "base_time" to issued.format(DateTimeFormatter.ofPattern("HHmm")),
                        "nx" to grid.x.toString(), "ny" to grid.y.toString(),
                    ))
                    if (response.status != 200) return@withContext WeatherResult.Failure(when (response.status) {
                        401, 403 -> WeatherFailure.AUTHORIZATION
                        429 -> WeatherFailure.QUOTA
                        else -> WeatherFailure.HTTP_ERROR
                    }, response.status)
                    val parsed = KmaWeatherParser.page(response.body, page)
                    if (expectedTotal != null && expectedTotal != parsed.total) throw InvalidWeather()
                    expectedTotal = parsed.total
                    rows += parsed.items
                    if (rows.size == parsed.total) break
                    if (parsed.items.isEmpty() || rows.size > parsed.total) throw InvalidWeather()
                    page++
                }
                WeatherResult.Success(KmaWeatherParser.snapshot(rows, kind, grid, issued, clock.instant()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: InvalidWeather) {
                WeatherResult.Failure(failure.reason)
            } catch (_: java.io.IOException) {
                WeatherResult.Failure(WeatherFailure.NETWORK)
            } catch (_: java.time.DateTimeException) {
                WeatherResult.Failure(WeatherFailure.INVALID_RESPONSE)
            } catch (_: IllegalArgumentException) {
                WeatherResult.Failure(WeatherFailure.INVALID_RESPONSE)
            } catch (_: IllegalStateException) {
                WeatherResult.Failure(WeatherFailure.INVALID_RESPONSE)
            }
        }
    }
}

internal class InvalidWeather(val reason: WeatherFailure = WeatherFailure.INVALID_RESPONSE) : RuntimeException()

internal object KmaWeatherParser {
    data class Page(val total: Int, val items: List<JsonObject>)
    private val dateTime = DateTimeFormatter.ofPattern("uuuuMMddHHmm").withResolverStyle(ResolverStyle.STRICT)
    private fun text(row: JsonObject, key: String) = row[key]?.jsonPrimitive?.content ?: throw InvalidWeather()

    fun page(raw: String, expectedPage: Int): Page {
        // 게이트웨이 XML 오류도 본문이나 키를 출력하지 않고 실패로 구분한다.
        if (raw.trimStart().startsWith("<")) {
            val code = Regex("<(?:returnReasonCode|resultCode)>\\s*([^<]+)").find(raw)?.groupValues?.get(1)?.trim()
            throw InvalidWeather(codeFailure(code))
        }
        val root = Json.parseToJsonElement(raw).jsonObject["response"]?.jsonObject ?: throw InvalidWeather()
        val header = root["header"]?.jsonObject ?: throw InvalidWeather()
        val code = text(header, "resultCode")
        if (code != "00" && code != "0000") throw InvalidWeather(codeFailure(code))
        val body = root["body"]?.jsonObject ?: throw InvalidWeather()
        if (text(body, "pageNo").toIntOrNull() != expectedPage) throw InvalidWeather()
        val total = text(body, "totalCount").toIntOrNull()?.takeIf { it >= 0 } ?: throw InvalidWeather()
        if (total == 0) throw InvalidWeather(WeatherFailure.NO_DATA)
        val item = body["items"]?.jsonObject?.get("item")
        val items = when (item) {
            is JsonArray -> item.map { it.jsonObject }
            is JsonObject -> listOf(item)
            else -> emptyList()
        }
        return Page(total, items)
    }

    private fun codeFailure(code: String?) = when (code) {
        "03" -> WeatherFailure.NO_DATA
        "22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR" -> WeatherFailure.QUOTA
        "20", "21", "30", "31", "32", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" -> WeatherFailure.AUTHORIZATION
        else -> WeatherFailure.INVALID_RESPONSE
    }

    fun snapshot(rows: List<JsonObject>, kind: WeatherKind, grid: WeatherGrid,
                 issued: LocalDateTime, fetched: java.time.Instant): WeatherSnapshot {
        if (rows.isEmpty()) throw InvalidWeather(WeatherFailure.NO_DATA)
        val frames = sortedMapOf<LocalDateTime, MutableMap<String, String>>()
        var responseIssue: LocalDateTime? = null
        for (row in rows) {
            val base = LocalDateTime.parse(text(row, "baseDate") + text(row, "baseTime"), dateTime)
            val sameUltraCycle = kind == WeatherKind.ULTRA_FORECAST && issued.minute == 30 && base == issued.withMinute(0)
            if ((base != issued && !sameUltraCycle) || text(row, "nx").toIntOrNull() != grid.x || text(row, "ny").toIntOrNull() != grid.y) throw InvalidWeather()
            if (responseIssue != null && responseIssue != base) throw InvalidWeather()
            responseIssue = base
            val validAt = if (kind == WeatherKind.OBSERVATION) base else {
                val day = text(row, "fcstDate")
                val time = text(row, "fcstTime")
                if (time == "2400") LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay()
                else LocalDateTime.parse(day + time, dateTime)
            }
            val category = text(row, "category")
            val value = text(row, if (kind == WeatherKind.OBSERVATION) "obsrValue" else "fcstValue")
            val values = frames.getOrPut(validAt) { mutableMapOf() }
            if (category in values && values[category] != value) throw InvalidWeather()
            values[category] = value
        }
        return WeatherSnapshot(kind, grid, responseIssue ?: throw InvalidWeather(), fetched,
            frames.map { WeatherFrame(it.key, it.value.toMap()) }, requestedBaseAt = issued)
    }
}
