package com.axis.translate.data.repo

import com.axis.translate.domain.model.FavoriteItem
import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow

/** History persistence contract (SPEC #26). */
interface HistoryRepository {
    fun observe(): Flow<List<HistoryItem>>
    fun observeFavorites(): Flow<List<HistoryItem>>
    suspend fun get(id: Long): HistoryItem?
    suspend fun search(query: String): Flow<List<HistoryItem>>
    suspend fun add(item: HistoryItem): Long
    suspend fun update(item: HistoryItem)
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** Cross-table sync: (un)star every history row matching a favorite's content. */
    suspend fun setFavoriteByContent(sourceCode: String, targetCode: String, sourceText: String, favorite: Boolean)

    /** Bulk star reset used when the favorites list is cleared in Settings. */
    suspend fun clearFavoriteFlags()

    suspend fun delete(id: Long)
    suspend fun clear()
}

/** Favorites persistence contract (SPEC #28). */
interface FavoritesRepository {
    fun observe(): Flow<List<FavoriteItem>>
    suspend fun search(query: String): Flow<List<FavoriteItem>>

    /** Inserts or returns the existing row id — same source text + pair is one favorite. */
    suspend fun add(item: FavoriteItem): Long
    suspend fun update(item: FavoriteItem)
    suspend fun delete(id: Long)

    /** Cross-table sync: remove the favorite rows matching an un-starred history entry. */
    suspend fun deleteByContent(sourceCode: String, targetCode: String, sourceText: String)

    suspend fun clear()
}

/** Glossary persistence contract (SPEC #29). */
interface GlossaryRepository {
    fun observe(): Flow<List<GlossaryTerm>>
    fun observeEnabled(): Flow<List<GlossaryTerm>>
    suspend fun add(term: GlossaryTerm): Long
    suspend fun update(term: GlossaryTerm)
    suspend fun delete(id: Long)
    suspend fun clear()
}
