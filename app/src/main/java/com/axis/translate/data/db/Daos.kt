package com.axis.translate.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * History table access. Search queries use `LIKE ... ESCAPE '\'`; callers must
 * pre-escape the raw user query (see [com.axis.translate.data.repo] LIKE
 * escaping helper) so `%` and `_` match literally.
 */
@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun observeFavorites(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntity?

    @Query(
        "SELECT * FROM history WHERE sourceText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR translatedText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY timestamp DESC",
    )
    fun search(query: String): Flow<List<HistoryEntity>>

    @Query("UPDATE history SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

/** Favorites table access. */
@Dao
interface FavoriteDao {

    @Insert
    suspend fun insert(entity: FavoriteEntity): Long

    @Update
    suspend fun update(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()

    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAll(): Flow<List<FavoriteEntity>>

    @Query(
        "SELECT * FROM favorites WHERE sourceText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR translatedText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY timestamp DESC",
    )
    suspend fun search(query: String): List<FavoriteEntity>
}

/** Glossary table access. */
@Dao
interface GlossaryDao {

    @Insert
    suspend fun insert(entity: GlossaryEntity): Long

    @Update
    suspend fun update(entity: GlossaryEntity)

    @Query("DELETE FROM glossary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM glossary")
    suspend fun deleteAll()

    @Query("SELECT * FROM glossary ORDER BY createdAt DESC")
    fun getAll(): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossary WHERE enabled = 1 ORDER BY createdAt DESC")
    fun observeEnabled(): Flow<List<GlossaryEntity>>
}
