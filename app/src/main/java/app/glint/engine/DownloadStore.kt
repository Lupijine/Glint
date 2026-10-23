package app.glint.engine

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A download as stored on disk. Progress, speed and file checks are live state and stay out. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val type: String,
    val formatSelector: String,
    val formatLabel: String,
    val audioFormat: String,
    val status: String,
    val title: String,
    val thumbnail: String?,
    val source: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val filePath: String?,
    val fileUri: String?,
    val error: String?,
    val folder: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val inQueue: Boolean,
    val customArgs: String?,
    val errorDetail: String?,
    val sectionStart: Int?,
    val sectionEnd: Int?,
    @ColumnInfo(defaultValue = "0") val preciseCut: Boolean,
    val subscriptionId: String?,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun all(): List<DownloadEntity>

    @Upsert
    suspend fun upsert(entities: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Database(entities = [DownloadEntity::class], version = 3, exportSchema = false)
abstract class GlintDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: GlintDatabase? = null

        fun get(context: Context): GlintDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                GlintDatabase::class.java,
                                "glint.db",
                            )
                            .addMigrations(AddCommandsAndErrorDetail, AddClipsAndFollowing)
                            .build()
                            .also { instance = it }
                }
    }
}

/** Version 2 adds saved command arguments and the full error output. */
private val AddCommandsAndErrorDetail =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN customArgs TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN errorDetail TEXT")
        }
    }

/** Version 3 adds clips and the followed channel a download came from. */
private val AddClipsAndFollowing =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN sectionStart INTEGER")
            db.execSQL("ALTER TABLE downloads ADD COLUMN sectionEnd INTEGER")
            db.execSQL("ALTER TABLE downloads ADD COLUMN preciseCut INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN subscriptionId TEXT")
        }
    }

internal fun DownloadItem.toEntity(): DownloadEntity =
    DownloadEntity(
        id = id,
        url = url,
        type = type.name,
        formatSelector = formatSelector,
        formatLabel = formatLabel,
        audioFormat = audioFormat.name,
        status = status.name,
        title = title,
        thumbnail = thumbnail,
        source = source,
        durationSeconds = durationSeconds,
        sizeBytes = sizeBytes,
        filePath = filePath,
        fileUri = fileUri,
        error = error,
        folder = folder,
        createdAt = createdAt,
        completedAt = completedAt,
        inQueue = inQueue,
        customArgs = customArgs,
        errorDetail = errorDetail,
        sectionStart = sectionStart,
        sectionEnd = sectionEnd,
        preciseCut = preciseCut,
        subscriptionId = subscriptionId,
    )

/** Null for a row this version cannot read, such as one written by a newer Glint. */
internal fun DownloadEntity.toItemOrNull(): DownloadItem? {
    val storedType = type
    val storedStatus = status
    val storedAudio = audioFormat
    val mediaType = MediaType.entries.firstOrNull { it.name == storedType } ?: return null
    val itemStatus = DownloadItem.Status.entries.firstOrNull { it.name == storedStatus } ?: return null
    val audio = AudioFormat.entries.firstOrNull { it.name == storedAudio } ?: AudioFormat.Original
    return DownloadItem(
        id = id,
        url = url,
        type = mediaType,
        formatSelector = formatSelector,
        formatLabel = formatLabel,
        audioFormat = audio,
        status = itemStatus,
        title = title,
        thumbnail = thumbnail,
        source = source,
        durationSeconds = durationSeconds,
        sizeBytes = sizeBytes,
        filePath = filePath,
        fileUri = fileUri,
        error = error,
        folder = folder,
        createdAt = createdAt,
        completedAt = completedAt,
        inQueue = inQueue,
        customArgs = customArgs,
        errorDetail = errorDetail,
        sectionStart = sectionStart,
        sectionEnd = sectionEnd,
        preciseCut = preciseCut,
        subscriptionId = subscriptionId,
    )
}
