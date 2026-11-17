package com.impulsolab.impulsotv.data.local

import androidx.room.*
import com.google.gson.Gson
import com.impulsolab.impulsotv.data.model.PlaylistItem

@Entity(tableName = "playlist_cache")
data class PlaylistItemEntity(
    @PrimaryKey val id: String,
    val json: String
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_cache")
    suspend fun getAll(): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_cache")
    suspend fun deleteAll()
}

@Database(entities = [PlaylistItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
}

class OfflinePlaylistCache(context: android.content.Context) {
    private val db = Room.databaseBuilder(
        context, AppDatabase::class.java, "impulsotv.db"
    ).build()
    private val gson = Gson()
    
    suspend fun savePlaylist(items: List<PlaylistItem>) {
        val entities = items.map { 
            PlaylistItemEntity(it.id, gson.toJson(it))
        }
        db.playlistDao().deleteAll()
        db.playlistDao().insertAll(entities)
    }
    
    suspend fun getOfflinePlaylist(): List<PlaylistItem> {
        return db.playlistDao().getAll().mapNotNull {
            gson.fromJson(it.json, PlaylistItem::class.java)
        }
    }
    
    suspend fun clearCache() {
        db.playlistDao().deleteAll()
    }
}
