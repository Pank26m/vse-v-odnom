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
    @ColumnInfo(defaultValue = "") val photo: String = "",      // Uri картинки
    @ColumnInfo(defaultValue = "") val link: String = "",       // ссылка на товар
    @ColumnInfo(defaultValue = "") val link2: String = "",      // ссылка на объявление аренды
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

/** Пара из расписания. Текст хранится как в исходном файле. */
@Entity(tableName = "lesson", indices = [Index("epochDay")])
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val pair: Int,
    val start: String,
    val end: String,
    val subject: String,
    val kind: String,
    val room: String,
    val teacher: String,
)

/** Расшифровка сокращения предмета и его цвет. */
@Entity(tableName = "subject_alias")
data class SubjectAlias(
    @PrimaryKey val abbr: String,
    val fullName: String = "",
    val tint: Int = 0,
)

/** Своё событие в календаре. */
@Entity(tableName = "cal_event", indices = [Index("startMillis")])
data class CalEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val note: String = "",
    val tint: Int = 3,
)

/** Запись дневника настроения: 1 ужасно … 5 супер. */
@Entity(tableName = "mood", indices = [Index("at")])
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val level: Int,
    val note: String = "",
    @ColumnInfo(defaultValue = "") val photo: String = "",   // Uri фото из галереи
    @ColumnInfo(defaultValue = "") val audio: String = "",   // путь к голосовой заметке
)

/** Корзина: что хочу купить. */
@Entity(tableName = "cart_item")
data class CartItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String = "",
    val title: String,
    val price: Double? = null,
    val currency: String = "",
    val image: String = "",
    val store: String = "",
    val note: String = "",
    val priority: Int = 1,
    val addedAt: Long = System.currentTimeMillis(),
    val bought: Boolean = false,
    @ColumnInfo(defaultValue = "") val tags: String = "",
    val planDay: Long? = null,                               // когда купить (+ в календарь)
    @ColumnInfo(defaultValue = "") val photo: String = "",
    val boughtAt: Long? = null,
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

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM lesson WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay, pair")
    fun lessons(from: Long, to: Long): Flow<List<Lesson>>

    @Query("SELECT * FROM lesson WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay, pair")
    fun lessonsSync(from: Long, to: Long): List<Lesson>

    @Query("SELECT DISTINCT subject FROM lesson ORDER BY subject")
    fun subjects(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM lesson")
    fun count(): Flow<Int>

    @Query("SELECT MIN(epochDay) FROM lesson")
    suspend fun firstDay(): Long?

    @Query("SELECT MAX(epochDay) FROM lesson")
    suspend fun lastDay(): Long?

    @Query("DELETE FROM lesson WHERE epochDay BETWEEN :from AND :to")
    suspend fun deleteRange(from: Long, to: Long)

    @Query("DELETE FROM lesson")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(l: List<Lesson>)

    /** Импорт: заменяем пары за период файла, ничего не дублируя. */
    @Transaction
    suspend fun replaceRange(from: Long, to: Long, l: List<Lesson>) { deleteRange(from, to); insert(l) }

    @Query("SELECT * FROM subject_alias")
    fun aliases(): Flow<List<SubjectAlias>>

    @Query("SELECT * FROM subject_alias")
    fun aliasesSync(): List<SubjectAlias>

    @Upsert
    suspend fun upsertAlias(a: SubjectAlias)

    @Query("SELECT * FROM cal_event WHERE startMillis < :to AND endMillis >= :from ORDER BY startMillis")
    fun events(from: Long, to: Long): Flow<List<CalEvent>>

    @Query("SELECT * FROM cal_event WHERE startMillis < :to AND endMillis >= :from ORDER BY startMillis")
    fun eventsSync(from: Long, to: Long): List<CalEvent>

    @Upsert
    suspend fun upsertEvent(e: CalEvent)

    @Delete
    suspend fun deleteEvent(e: CalEvent)
}

@Dao
interface LifeDao {
    @Query("SELECT * FROM mood ORDER BY at DESC")
    fun moods(): Flow<List<MoodEntry>>
    @Insert suspend fun addMood(m: MoodEntry)
    @Delete suspend fun deleteMood(m: MoodEntry)

    @Query("SELECT * FROM cart_item ORDER BY bought, priority DESC, addedAt DESC")
    fun cart(): Flow<List<CartItem>>
    @Upsert suspend fun upsertCart(c: CartItem): Long
    @Delete suspend fun deleteCart(c: CartItem)
}

// ---------- База ----------

@Database(
    entities = [
        WeightEntry::class, PiggyGoal::class, PiggyTx::class, AlarmEntity::class, WorldCity::class, WeatherCity::class,
        Lesson::class, SubjectAlias::class, CalEvent::class, MoodEntry::class, CartItem::class,
        TaskItem::class, Habit::class, HabitLog::class, FocusSession::class, MediaItem::class, Note::class, Birthday::class,
        Account::class, Category::class, Txn::class, Place::class, Track::class,
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDb : RoomDatabase() {
    abstract fun weight(): WeightDao
    abstract fun piggy(): PiggyDao
    abstract fun alarms(): AlarmDao
    abstract fun cities(): CityDao
    abstract fun schedule(): ScheduleDao
    abstract fun life(): LifeDao
    abstract fun todo(): TodoDao
    abstract fun money(): MoneyDao
    abstract fun map(): MapDao

    companion object {
        @Volatile private var inst: AppDb? = null

        /** Закрыть базу перед заменой файла из резервной копии. */
        fun closeForRestore() = synchronized(this) { inst?.close(); inst = null }

        /** v3 → v4: дела, привычки, фокус, списки, заметки, дни рождения, деньги, карта; фото/голос у настроения; теги/архив у корзины. */
        val M3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `mood` ADD COLUMN `photo` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `mood` ADD COLUMN `audio` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `cart_item` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `cart_item` ADD COLUMN `planDay` INTEGER")
                db.execSQL("ALTER TABLE `cart_item` ADD COLUMN `photo` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `cart_item` ADD COLUMN `boughtAt` INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `note` TEXT NOT NULL, `dueDay` INTEGER, `dueMinute` INTEGER, `priority` INTEGER NOT NULL, `list` TEXT NOT NULL, `repeat` TEXT NOT NULL, `remindBefore` INTEGER, `done` INTEGER NOT NULL, `doneAt` INTEGER, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_dueDay` ON `task` (`dueDay`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `habit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `tint` INTEGER NOT NULL, `daysMask` INTEGER NOT NULL, `perDay` INTEGER NOT NULL, `remindMinute` INTEGER, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `habit_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `count` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_log_habitId_epochDay` ON `habit_log` (`habitId`, `epochDay`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `focus` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `label` TEXT NOT NULL, `completed` INTEGER NOT NULL, `tree` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `list` TEXT NOT NULL, `title` TEXT NOT NULL, `status` INTEGER NOT NULL, `rating` INTEGER NOT NULL, `note` TEXT NOT NULL, `link` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_item_list` ON `media_item` (`list`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `tint` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `tags` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `birthday` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `day` INTEGER NOT NULL, `month` INTEGER NOT NULL, `year` INTEGER, `note` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, `initial` REAL NOT NULL, `tint` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `sort` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `income` INTEGER NOT NULL, `parentId` INTEGER, `tint` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_parentId` ON `category` (`parentId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `txn` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `accountId` INTEGER NOT NULL, `toAccountId` INTEGER, `toAmount` REAL, `categoryId` INTEGER, `comment` TEXT NOT NULL, `tags` TEXT NOT NULL, `source` TEXT NOT NULL, `extId` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_at` ON `txn` (`at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_accountId` ON `txn` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_categoryId` ON `txn` (`categoryId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `place` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `folder` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `folder` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `points` TEXT NOT NULL)")
            }
        }

        /** v2 → v3: фото и ссылки у копилок, дневник настроения, корзина. */
        val M2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `piggy_goal` ADD COLUMN `photo` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `piggy_goal` ADD COLUMN `link` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `piggy_goal` ADD COLUMN `link2` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `mood` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `level` INTEGER NOT NULL, `note` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mood_at` ON `mood` (`at`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cart_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `price` REAL, `currency` TEXT NOT NULL, `image` TEXT NOT NULL, `store` TEXT NOT NULL, `note` TEXT NOT NULL, `priority` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `bought` INTEGER NOT NULL)")
            }
        }

        /** v1 → v2: расписание, сокращения предметов, свои события. Данные v1 не трогаем. */
        val M1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `lesson` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, `pair` INTEGER NOT NULL, `start` TEXT NOT NULL, `end` TEXT NOT NULL, `subject` TEXT NOT NULL, `kind` TEXT NOT NULL, `room` TEXT NOT NULL, `teacher` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lesson_epochDay` ON `lesson` (`epochDay`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `subject_alias` (`abbr` TEXT NOT NULL, `fullName` TEXT NOT NULL, `tint` INTEGER NOT NULL, PRIMARY KEY(`abbr`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cal_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, `allDay` INTEGER NOT NULL, `note` TEXT NOT NULL, `tint` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cal_event_startMillis` ON `cal_event` (`startMillis`)")
            }
        }
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "app.db")
                .addMigrations(M1_2, M2_3, M3_4)
                .build().also { inst = it }
        }
    }
}
