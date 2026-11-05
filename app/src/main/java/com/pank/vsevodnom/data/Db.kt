package com.pank.vsevodnom.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ---------- Сущности ----------

@Entity(tableName = "weight")
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val kg: Double,
)

/** kind: SAVE — копилка с целью; PAYBACK — окупаемость (цель = стоимость вещи). */
@Entity(tableName = "piggy_goal")
data class PiggyGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String = KIND_SAVE,
    val target: Double = 0.0,
    val tint: Int = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_SAVE = "SAVE"
        const val KIND_PAYBACK = "PAYBACK"
    }
}

@Entity(
    tableName = "piggy_tx",
    foreignKeys = [ForeignKey(PiggyGoal::class, ["id"], ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId")]
)
data class PiggyTx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amount: Double,
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

data class GoalWithSum(
    @Embedded val goal: PiggyGoal,
    val saved: Double,
    val txCount: Int,
)

/** daysMask: бит 0 = понедельник … бит 6 = воскресенье. 0 = однократный. */
@Entity(tableName = "alarm")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val vibrate: Boolean = true,
)

@Entity(tableName = "world_city")
data class WorldCity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val zoneId: String,
    val name: String,
    val sort: Int = 0,
)

@Entity(tableName = "weather_city")
data class WeatherCity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double,
    val sort: Int = 0,
    val isCurrentLocation: Boolean = false,
    val cachedJson: String? = null,
    val cachedAt: Long = 0,
)

// ---------- DAO ----------

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight ORDER BY epochDay ASC")
    fun all(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight WHERE epochDay = :day LIMIT 1")
    suspend fun byDay(day: Long): WeightEntry?

    @Upsert
    suspend fun upsert(e: WeightEntry)

    @Delete
    suspend fun delete(e: WeightEntry)
}

@Dao
interface PiggyDao {
    @Query(
        """SELECT g.*, COALESCE(SUM(t.amount), 0) AS saved, COUNT(t.id) AS txCount
           FROM piggy_goal g LEFT JOIN piggy_tx t ON t.goalId = g.id
           GROUP BY g.id ORDER BY g.createdAt ASC"""
    )
    fun goals(): Flow<List<GoalWithSum>>

    @Query("SELECT * FROM piggy_goal WHERE id = :id")
    fun goal(id: Long): Flow<PiggyGoal?>

    @Query("SELECT * FROM piggy_tx WHERE goalId = :goalId ORDER BY at DESC")
    fun txs(goalId: Long): Flow<List<PiggyTx>>

    @Upsert
    suspend fun upsertGoal(g: PiggyGoal): Long

    @Delete
    suspend fun deleteGoal(g: PiggyGoal)

    @Insert
    suspend fun addTx(t: PiggyTx)

    @Delete
    suspend fun deleteTx(t: PiggyTx)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarm ORDER BY hour, minute")
    fun all(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarm")
    suspend fun allOnce(): List<AlarmEntity>

    @Query("SELECT * FROM alarm WHERE id = :id")
    suspend fun byId(id: Long): AlarmEntity?

    @Upsert
    suspend fun upsert(a: AlarmEntity): Long

    @Delete
    suspend fun delete(a: AlarmEntity)
}

@Dao
interface CityDao {
    @Query("SELECT * FROM world_city ORDER BY sort, id")
    fun world(): Flow<List<WorldCity>>

    @Insert
    suspend fun addWorld(c: WorldCity)

    @Delete
    suspend fun deleteWorld(c: WorldCity)

    @Query("SELECT * FROM weather_city ORDER BY isCurrentLocation DESC, sort, id")
    fun weather(): Flow<List<WeatherCity>>

    @Query("SELECT * FROM weather_city ORDER BY isCurrentLocation DESC, sort, id")
    suspend fun weatherOnce(): List<WeatherCity>

    @Query("SELECT * FROM weather_city WHERE isCurrentLocation = 1 LIMIT 1")
    suspend fun currentLocation(): WeatherCity?

    @Upsert
    suspend fun upsertWeather(c: WeatherCity): Long

    @Delete
    suspend fun deleteWeather(c: WeatherCity)

    @Query("UPDATE weather_city SET cachedJson = :json, cachedAt = :at WHERE id = :id")
    suspend fun cacheWeather(id: Long, json: String, at: Long)
}

// ---------- База ----------

@Database(
    entities = [WeightEntry::class, PiggyGoal::class, PiggyTx::class, AlarmEntity::class, WorldCity::class, WeatherCity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun weight(): WeightDao
    abstract fun piggy(): PiggyDao
    abstract fun alarms(): AlarmDao
    abstract fun cities(): CityDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "app.db")
                .build().also { inst = it }
        }
    }
}
