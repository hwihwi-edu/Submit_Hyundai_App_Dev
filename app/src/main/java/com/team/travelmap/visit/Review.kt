package com.team.travelmap.visit

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.team.travelmap.core.model.Place
import kotlinx.coroutines.flow.Flow

/**
 * 담당: 1번(DB·화면). 경유지 리뷰. 2번 신호(경유지에 주차)가 오면 화면에서 짧게 남긴다.
 * 기획(2차 8번): 주차 / 웨이팅 / 재방문 3문항 선택형 + 한 줄(선택). 별점은 쓰지 않는다(v5 에서 뺌).
 * "핫" 표시(재방문 의사 높은 곳) 계산은 3번이 [wouldRevisit] 를 읽어 쓴다.
 */
enum class ParkingAnswer(val label: String) { EASY("편했어요"), OK("보통이에요"), HARD("불편했어요") }
enum class WaitingAnswer(val label: String) { NONE("바로 들어갔어요"), SHORT("조금 기다렸어요"), LONG("오래 기다렸어요") }

@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    /** 장소의 시·군·구 코드(있을 때). */
    val regionCode: String?,
    /** 예전(v4 이하) 별점. 새 리뷰는 null. 화면에 보이지 않고 기존 데이터 보존용으로만 남긴다. */
    val rating: Int? = null,
    /** 주차 편의([ParkingAnswer] 이름). 답 안 함 = null. */
    val parking: String? = null,
    /** 웨이팅([WaitingAnswer] 이름). 답 안 함 = null. */
    val waiting: String? = null,
    /** 다시 오고 싶다 = true, 글쎄요 = false, 답 안 함 = null. */
    val wouldRevisit: Boolean?,
    val comment: String?,
    val createdAt: Long,
    /** 기록 상세에서 쓴 리뷰면 그 방문 기록 id. 경유지에서 바로 쓴 리뷰는 null. */
    val visitId: Long? = null,
)

@Dao
interface ReviewDao {
    @Insert
    suspend fun insert(review: ReviewEntity): Long

    @Query("SELECT * FROM reviews ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReviewEntity>>

    @androidx.room.Update
    suspend fun update(review: ReviewEntity)

    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reviews")
    suspend fun deleteAll()
}

class ReviewRepository(private val dao: ReviewDao) {
    val reviews: Flow<List<ReviewEntity>> = dao.observeAll()

    suspend fun save(place: Place, parking: ParkingAnswer?, waiting: WaitingAnswer?, wouldRevisit: Boolean?, comment: String?): Long =
        dao.insert(
            ReviewEntity(
                placeName = place.name, latitude = place.point.latitude, longitude = place.point.longitude,
                regionCode = place.regionId?.code, parking = parking?.name, waiting = waiting?.name, wouldRevisit = wouldRevisit,
                comment = comment?.trim()?.takeIf { it.isNotEmpty() }, createdAt = System.currentTimeMillis(),
            ),
        )

    /**
     * 기록 상세에서 저장: 이미 이어진 리뷰(existing)가 있으면 고치고, 없으면 새로 만든다(이 방문 기록에 잇는다).
     * 고른 것이 하나도 없고 한 줄도 비면 저장하지 않는다.
     */
    suspend fun saveForVisit(
        existing: ReviewEntity?, visitId: Long, place: Place,
        parking: ParkingAnswer?, waiting: WaitingAnswer?, wouldRevisit: Boolean?, comment: String?,
    ) {
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }
        if (parking == null && waiting == null && wouldRevisit == null && text == null) return
        if (existing != null) dao.update(existing.copy(parking = parking?.name, waiting = waiting?.name, wouldRevisit = wouldRevisit, comment = text, visitId = visitId))
        else dao.insert(
            ReviewEntity(
                placeName = place.name, latitude = place.point.latitude, longitude = place.point.longitude,
                regionCode = place.regionId?.code, parking = parking?.name, waiting = waiting?.name, wouldRevisit = wouldRevisit,
                comment = text, createdAt = System.currentTimeMillis(), visitId = visitId,
            ),
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()
}

fun ReviewEntity.parkingAnswer(): ParkingAnswer? = ParkingAnswer.entries.firstOrNull { it.name == parking }
fun ReviewEntity.waitingAnswer(): WaitingAnswer? = WaitingAnswer.entries.firstOrNull { it.name == waiting }
