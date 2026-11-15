package com.team.travelmap.visit

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.settings.AppSettings
import com.team.travelmap.settings.MarkStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 담당: 1번(지도·화면). 여행 완료 기록. 같은 지역을 여러 번 가면 행이 여러 개 쌓인다. */
@Entity(tableName = "visits")
data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionCode: String,
    val visitedAt: Long,
    val distanceKm: Double?,
    val durationMinutes: Int?,
    /** 사진 채우기용 이미지 URI. 정차 중에만 등록 가능. */
    val photoUri: String? = null,
    /**
     * false = 아직 기록하지 않은 방문(설정 "자동 방문 기록" 꺼짐). 완료 화면에서 "기록하기"를 누르면 true.
     * 지도·방문 수·여행 기록에는 true 만 나온다.
     */
    val confirmed: Boolean = true,
    /** true = 여행 없이 내 지도에서 직접 체크한 기록. */
    val manual: Boolean = false,
    /** 이 구에서 실제로 간 장소(목적지·주차한 경유지). 기록 카드 사진을 이 장소로 찾는다. 모르면 null. */
    val placeName: String? = null,
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    /** 기록 상세의 "나만의 메모"(여러 줄). 없으면 null. */
    val memo: String? = null,
)

/** 칸별 표시(무늬/색/이모지). 고르지 않은 칸은 설정의 기본 표시를 따른다. */
@Entity(tableName = "region_marks")
data class RegionMarkEntity(
    @PrimaryKey val regionCode: String,
    val style: String,
)

@Dao
interface VisitDao {
    @Insert
    suspend fun insert(visit: VisitEntity): Long

    @Query("SELECT DISTINCT regionCode FROM visits WHERE confirmed = 1")
    fun observeVisitedCodes(): Flow<List<String>>

    @Query("SELECT * FROM visits WHERE confirmed = 1 ORDER BY visitedAt DESC")
    fun observeAll(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE id = :id")
    fun observe(id: Long): Flow<VisitEntity?>

    @Query("UPDATE visits SET photoUri = :uri WHERE id = :id")
    suspend fun setPhoto(id: Long, uri: String)

    @Query("UPDATE visits SET memo = :memo WHERE id = :id")
    suspend fun setMemo(id: Long, memo: String?)

    @Query("UPDATE visits SET confirmed = 1 WHERE id = :id")
    suspend fun confirm(id: Long)

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM visits WHERE regionCode = :code")
    suspend fun deleteRegion(code: String)

    @Query("DELETE FROM visits")
    suspend fun deleteAll()

    @Query("SELECT * FROM region_marks")
    fun observeMarks(): Flow<List<RegionMarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMark(mark: RegionMarkEntity)

    @Query("DELETE FROM region_marks WHERE regionCode = :code")
    suspend fun clearMark(code: String)

    @Query("DELETE FROM region_marks")
    suspend fun clearAllMarks()
}

@Database(entities = [VisitEntity::class, RegionMarkEntity::class, ReviewEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun visitDao(): VisitDao
    abstract fun reviewDao(): ReviewDao

    companion object {
        /** v1 → v2: 기록 확인 여부·직접 체크 칸, 칸별 표시 테이블. 기존 기록은 모두 확인된 기록으로 둔다. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visits ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE visits ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS region_marks (regionCode TEXT NOT NULL, style TEXT NOT NULL, PRIMARY KEY(regionCode))")
            }
        }

        /** v2 → v3: 경유지 리뷰 테이블. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, placeName TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, longitude REAL NOT NULL, regionCode TEXT, rating INTEGER NOT NULL, " +
                        "wouldRevisit INTEGER, comment TEXT, createdAt INTEGER NOT NULL)",
                )
            }
        }

        /** v3 → v4: 방문 기록에 실제로 간 장소(이름·좌표). 기존 기록은 비워 둔다. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visits ADD COLUMN placeName TEXT")
                db.execSQL("ALTER TABLE visits ADD COLUMN placeLat REAL")
                db.execSQL("ALTER TABLE visits ADD COLUMN placeLng REAL")
            }
        }

        /**
         * v4 → v5: 리뷰를 별점 대신 주차·웨이팅·재방문 선택형으로. 별점 칸은 비워도 되게(NULL) 바꾸고
         * 기존 리뷰와 별점 값은 그대로 옮긴다(가짜 값은 채우지 않음). SQLite 는 NOT NULL 을 못 바꾸므로 표를 새로 만든다.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reviews_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, placeName TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, longitude REAL NOT NULL, regionCode TEXT, rating INTEGER, parking TEXT, waiting TEXT, " +
                        "wouldRevisit INTEGER, comment TEXT, createdAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO reviews_new (id, placeName, latitude, longitude, regionCode, rating, wouldRevisit, comment, createdAt) " +
                        "SELECT id, placeName, latitude, longitude, regionCode, rating, wouldRevisit, comment, createdAt FROM reviews",
                )
                db.execSQL("DROP TABLE reviews")
                db.execSQL("ALTER TABLE reviews_new RENAME TO reviews")
            }
        }

        /** v5 → v6: 방문 기록 메모, 리뷰를 방문 기록에 잇는 visitId. 기존 기록·리뷰는 그대로(빈 값). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visits ADD COLUMN memo TEXT")
                db.execSQL("ALTER TABLE reviews ADD COLUMN visitId INTEGER")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "travelmap.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}

class VisitRepository(private val dao: VisitDao, private val settings: AppSettings) {
    /** 기록된(확인된) 방문 지역. */
    val visitedRegions: Flow<Set<RegionId>> =
        dao.observeVisitedCodes().map { codes -> codes.map(::RegionId).toSet() }

    /** 기록된 방문 전체, 최신순. */
    val visits: Flow<List<VisitEntity>> = dao.observeAll()

    /** 지역별 방문 횟수. */
    val visitCounts: Flow<Map<RegionId, Int>> =
        visits.map { list -> list.groupingBy { RegionId(it.regionCode) }.eachCount() }

    /** 칸별로 고른 표시. */
    val marks: Flow<Map<RegionId, MarkStyle>> = dao.observeMarks().map { list ->
        list.mapNotNull { m -> MarkStyle.parse(m.style)?.let { RegionId(m.regionCode) to it } }.toMap()
    }

    fun observe(visitId: Long): Flow<VisitEntity?> = dao.observe(visitId)

    /**
     * 여행 완료 시 호출(2번 TripCoordinator). 설정 "자동 방문 기록"이 꺼져 있으면
     * 확인 전 기록으로 저장하고, 완료 화면에서 사용자가 [confirm] 또는 [delete] 한다.
     */
    suspend fun markVisited(regionId: RegionId, distanceKm: Double?, durationMinutes: Int?, place: Place? = null): Long =
        dao.insert(
            VisitEntity(
                regionCode = regionId.code,
                visitedAt = System.currentTimeMillis(),
                distanceKm = distanceKm,
                durationMinutes = durationMinutes,
                confirmed = settings.state.value.autoRecordVisit,
                placeName = place?.name, placeLat = place?.point?.latitude, placeLng = place?.point?.longitude,
            ),
        )

    /** 내 지도에서 직접 체크. */
    suspend fun checkManually(regionId: RegionId, place: Place? = null): Long =
        dao.insert(
            VisitEntity(
                regionCode = regionId.code, visitedAt = System.currentTimeMillis(),
                distanceKm = null, durationMinutes = null, manual = true,
                placeName = place?.name, placeLat = place?.point?.latitude, placeLng = place?.point?.longitude,
            ),
        )

    suspend fun confirm(visitId: Long) = dao.confirm(visitId)
    suspend fun setMemo(visitId: Long, memo: String?) = dao.setMemo(visitId, memo?.trim()?.takeIf { it.isNotEmpty() })
    suspend fun delete(visitId: Long) = dao.delete(visitId)

    /** 그 칸의 기록과 표시를 모두 지운다. */
    suspend fun deleteRegion(regionId: RegionId) {
        dao.deleteRegion(regionId.code)
        dao.clearMark(regionId.code)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        dao.clearAllMarks()
    }

    suspend fun setMark(regionId: RegionId, style: MarkStyle?) {
        if (style == null) dao.clearMark(regionId.code) else dao.setMark(RegionMarkEntity(regionId.code, style.serialize()))
    }

    suspend fun setPhoto(visitId: Long, uri: String) = dao.setPhoto(visitId, uri)
}
