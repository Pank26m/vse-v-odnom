package com.pank.vsevodnom.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ================= Дела =================

/** Задача. due — дата (epochDay) и необязательное время (минуты от полуночи). */
@Entity(tableName = "task", indices = [Index("dueDay")])
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val dueDay: Long? = null,
    val dueMinute: Int? = null,
    val priority: Int = 0,          // 0 обычный, 1 важный, 2 срочный
    val list: String = "",          // своя папка/список
    val repeat: String = "",        // "" | daily | weekly | monthly | weekdays
    val remindBefore: Int? = null,  // минут до срока
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val tags: String = "",          // через запятую
    @ColumnInfo(defaultValue = "") val image: String = "",         // путь к фото внутри приложения
    val durationMin: Int? = null,                                  // длительность (для «17:00–18:00»)
)

@Entity(tableName = "habit")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val tint: Int = 0,
    val daysMask: Int = 0b1111111,  // в какие дни недели (пн = бит 0)
    val perDay: Int = 1,            // сколько раз в день
    val remindMinute: Int? = null,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val icon: String = "",          // эмодзи
    @ColumnInfo(defaultValue = "") val goal: String = "",          // своя формулировка цели
    @ColumnInfo(defaultValue = "") val section: String = "",       // Утро / После обеда / Ночь / Другие
    @ColumnInfo(defaultValue = "0") val freqType: Int = 0,         // 0 по дням недели, 1 N раз в неделю, 2 каждые N дней
    @ColumnInfo(defaultValue = "1") val freqN: Int = 1,
    val startDay: Long? = null,
    val goalDays: Int? = null,                                     // null — вечно
    @ColumnInfo(defaultValue = "") val reminders: String = "",     // минуты от полуночи через запятую
    @ColumnInfo(defaultValue = "0") val autoLog: Boolean = false,  // после отметки спросить заметку
)

@Entity(tableName = "habit_log", indices = [Index(value = ["habitId", "epochDay"], unique = true)])
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val epochDay: Long,
    val count: Int,
)

/** Сессия фокусировки: каждая завершённая — дерево в «лесу». */
@Entity(tableName = "focus")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val minutes: Int,
    val label: String = "",
    val completed: Boolean,
    val tree: Int = 0,              // вид дерева
)

/** Пункт своих списков: фильмы, сериалы, книги, игры… list — название списка. */
@Entity(tableName = "media_item", indices = [Index("list")])
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val list: String,
    val title: String,
    val status: Int = 0,            // 0 хочу, 1 в процессе, 2 готово
    val rating: Int = 0,            // 0…5
    val note: String = "",
    val link: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val pageId: Long = 0,
    @ColumnInfo(defaultValue = "") val kind: String = "",          // film | series
    @ColumnInfo(defaultValue = "") val poster: String = "",
    @ColumnInfo(defaultValue = "") val year: String = "",
    @ColumnInfo(defaultValue = "") val details: String = "",       // JSON: режиссёр, актёры, жанр, рейтинг…
    @ColumnInfo(defaultValue = "") val extId: String = "",         // Wikidata Q…
)

@Entity(tableName = "note")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = "",
    val tint: Int = 4,
    val pinned: Boolean = false,
    val tags: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val pageId: Long = 0,
)

/** Страница в «Списках»: фильмы, покупки, заметки или своя. */
@Entity(tableName = "list_page")
data class ListPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,               // MOVIES | SHOP | NOTES
    val tint: Int = 0,
    val sort: Int = 0,
)

/** Пункт списка покупок. */
@Entity(tableName = "list_entry", indices = [Index("pageId")])
data class ListEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val text: String,
    val done: Boolean = false,
    val sort: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "birthday")
data class Birthday(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val day: Int,
    val month: Int,
    val year: Int? = null,
    val note: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "0") val tint: Int = 0,
    @ColumnInfo(defaultValue = "birthday") val kind: String = "birthday",   // birthday | holiday | anniversary | other
)

// ================= Деньги =================

@Entity(tableName = "account")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currency: String = "UAH",
    val initial: Double = 0.0,
    val tint: Int = 1,
    val archived: Boolean = false,
    val sort: Int = 0,
)

/** Категория дохода/расхода; parentId — для подкатегорий. */
@Entity(tableName = "category", indices = [Index("parentId")])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val income: Boolean,
    val parentId: Long? = null,
    val tint: Int = 0,
    val archived: Boolean = false,
    @ColumnInfo(defaultValue = "") val icon: String = "",          // имя значка из набора CAT_ICONS
)

/** Операция. type: EXPENSE, INCOME, TRANSFER (со счёта на счёт), ADJUST (корректировка). */
@Entity(tableName = "txn", indices = [Index("at"), Index("accountId"), Index("categoryId")])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val type: String,
    val amount: Double,             // всегда положительная, в валюте счёта
    val accountId: Long,
    val toAccountId: Long? = null,
    val toAmount: Double? = null,   // для переводов между валютами
    val categoryId: Long? = null,
    val comment: String = "",
    val tags: String = "",
    val source: String = "",        // manual | import | mono | push
    val extId: String = "",         // id в источнике — чтобы не дублировать
)

// ================= Карта =================

@Entity(tableName = "place")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val folder: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Трек: точки в виде «lat,lon,timeSec;…». */
@Entity(tableName = "track")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folder: String = "",
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val points: String,
)

// ================= DAO =================

@Dao
interface TodoDao {
    @Query("SELECT * FROM task ORDER BY done, CASE WHEN dueDay IS NULL THEN 1 ELSE 0 END, dueDay, dueMinute, priority DESC, createdAt")
    fun tasks(): Flow<List<TaskItem>>
    @Query("SELECT * FROM task WHERE done = 0 AND dueDay <= :day")
    fun tasksUntilSync(day: Long): List<TaskItem>
    @Upsert suspend fun upsertTask(t: TaskItem): Long
    @Delete suspend fun deleteTask(t: TaskItem)

    @Query("SELECT * FROM habit WHERE archived = 0 ORDER BY createdAt") fun habits(): Flow<List<Habit>>
    @Query("SELECT * FROM habit WHERE archived = 0") fun habitsSync(): List<Habit>
    @Upsert suspend fun upsertHabit(h: Habit): Long
    @Delete suspend fun deleteHabit(h: Habit)
    @Query("SELECT * FROM habit_log WHERE epochDay BETWEEN :from AND :to") fun habitLogs(from: Long, to: Long): Flow<List<HabitLog>>
    @Query("SELECT * FROM habit_log WHERE epochDay = :day") fun habitLogsSync(day: Long): List<HabitLog>
    @Upsert suspend fun upsertHabitLog(l: HabitLog)
    @Query("SELECT * FROM habit_log WHERE habitId = :h AND epochDay = :d") suspend fun habitLog(h: Long, d: Long): HabitLog?

    @Query("SELECT * FROM focus ORDER BY startedAt DESC") fun focus(): Flow<List<FocusSession>>
    @Insert suspend fun addFocus(f: FocusSession)

    @Query("SELECT * FROM media_item ORDER BY status, addedAt DESC") fun media(): Flow<List<MediaItem>>
    @Upsert suspend fun upsertMedia(m: MediaItem): Long
    @Delete suspend fun deleteMedia(m: MediaItem)

    @Query("SELECT * FROM note ORDER BY pinned DESC, updatedAt DESC") fun notes(): Flow<List<Note>>
    @Upsert suspend fun upsertNote(n: Note): Long
    @Delete suspend fun deleteNote(n: Note)

    @Query("SELECT * FROM list_page ORDER BY sort, id") fun pages(): Flow<List<ListPage>>
    @Query("SELECT * FROM list_page") suspend fun pagesOnce(): List<ListPage>
    @Upsert suspend fun upsertPage(p: ListPage): Long
    @Delete suspend fun deletePage(p: ListPage)
    @Query("SELECT * FROM list_entry WHERE pageId = :page ORDER BY done, sort, createdAt") fun entries(page: Long): Flow<List<ListEntry>>
    @Upsert suspend fun upsertEntry(e: ListEntry): Long
    @Delete suspend fun deleteEntry(e: ListEntry)
    @Query("DELETE FROM list_entry WHERE pageId = :page AND done = 1") suspend fun clearDone(page: Long)
    @Query("UPDATE media_item SET pageId = :page WHERE pageId = 0") suspend fun adoptMedia(page: Long)
    @Query("UPDATE note SET pageId = :page WHERE pageId = 0") suspend fun adoptNotes(page: Long)

    @Query("SELECT * FROM birthday ORDER BY month, day") fun birthdays(): Flow<List<Birthday>>
    @Query("SELECT * FROM birthday") fun birthdaysSync(): List<Birthday>
    @Upsert suspend fun upsertBirthday(b: Birthday): Long
    @Delete suspend fun deleteBirthday(b: Birthday)
}

data class CatSum(val categoryId: Long?, val total: Double)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM account ORDER BY archived, sort, id") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM account") suspend fun accountsOnce(): List<Account>
    @Upsert suspend fun upsertAccount(a: Account): Long
    @Delete suspend fun deleteAccount(a: Account)

    @Query("SELECT * FROM category ORDER BY income, parentId IS NOT NULL, name") fun categories(): Flow<List<Category>>
    @Query("SELECT * FROM category") suspend fun categoriesOnce(): List<Category>
    @Upsert suspend fun upsertCategory(c: Category): Long
    @Delete suspend fun deleteCategory(c: Category)

    @Query("SELECT * FROM txn WHERE at BETWEEN :from AND :to ORDER BY at DESC") fun txns(from: Long, to: Long): Flow<List<Txn>>
    @Query("SELECT * FROM txn ORDER BY at DESC") fun allTxns(): Flow<List<Txn>>
    @Upsert suspend fun upsertTxn(t: Txn): Long
    @Insert suspend fun insertTxns(t: List<Txn>)
    @Delete suspend fun deleteTxn(t: Txn)
    @Query("SELECT COUNT(*) FROM txn WHERE extId = :ext AND extId != ''") suspend fun countExt(ext: String): Int
    @Query("SELECT extId FROM txn WHERE extId != ''") suspend fun allExtIds(): List<String>
}

@Dao
interface MapDao {
    @Query("SELECT * FROM place ORDER BY folder, name") fun places(): Flow<List<Place>>
    @Upsert suspend fun upsertPlace(p: Place): Long
    @Delete suspend fun deletePlace(p: Place)
    @Query("SELECT * FROM track ORDER BY startedAt DESC") fun tracks(): Flow<List<Track>>
    @Upsert suspend fun upsertTrack(t: Track): Long
    @Delete suspend fun deleteTrack(t: Track)
}
