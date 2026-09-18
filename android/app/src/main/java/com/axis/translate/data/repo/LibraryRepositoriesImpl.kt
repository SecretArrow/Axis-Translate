package com.axis.translate.data.repo

import com.axis.translate.data.db.FavoriteDao
import com.axis.translate.data.db.FavoriteEntity
import com.axis.translate.data.db.GlossaryDao
import com.axis.translate.data.db.GlossaryEntity
import com.axis.translate.data.db.HistoryDao
import com.axis.translate.data.db.HistoryEntity
import com.axis.translate.domain.model.FavoriteItem
import com.axis.translate.domain.model.GlossaryTerm
import com.axis.translate.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val LIKE_ESCAPE_CHAR = '\\'

/**
 * Escapes SQL LIKE wildcards (`%`, `_`) and the escape character itself so a
 * raw user query matches literally inside the `LIKE ... ESCAPE '\'` DAO
 * queries. See [HistoryDao]/[FavoriteDao] search methods.
 */
private fun String.escapeForLike(): String = buildString {
    for (char in this@escapeForLike) {
        if (char == LIKE_ESCAPE_CHAR || char == '%' || char == '_') {
            append(LIKE_ESCAPE_CHAR)
        }
        append(char)
    }
}

/** Room-backed implementation of [HistoryRepository]. */
class HistoryRepositoryImpl(private val dao: HistoryDao) : HistoryRepository {

    override fun observe(): Flow<List<HistoryItem>> = dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<HistoryItem>> = dao.observeFavorites().map { entities -> entities.map { it.toDomain() } }

    override suspend fun get(id: Long): HistoryItem? = dao.getById(id)?.toDomain()

    override suspend fun search(query: String): Flow<List<HistoryItem>> = dao.search(query.escapeForLike()).map { entities -> entities.map { it.toDomain() } }

    override suspend fun add(item: HistoryItem): Long = dao.insert(HistoryEntity.fromDomain(item))

    override suspend fun update(item: HistoryItem) = dao.update(HistoryEntity.fromDomain(item))

    override suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    override suspend fun setFavoriteByContent(sourceCode: String, targetCode: String, sourceText: String, favorite: Boolean) =
        dao.setFavoriteByContent(sourceCode, targetCode, sourceText, favorite)

    override suspend fun clearFavoriteFlags() = dao.clearFavoriteFlags()

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}

/** Room-backed implementation of [FavoritesRepository]. */
class FavoritesRepositoryImpl(private val dao: FavoriteDao) : FavoritesRepository {

    override fun observe(): Flow<List<FavoriteItem>> = dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun search(query: String): Flow<List<FavoriteItem>> = dao.search(query.escapeForLike()).map { entities -> entities.map { it.toDomain() } }

    override suspend fun add(item: FavoriteItem): Long {
        // Dedup: starring the same text in the same language pair twice keeps a
        // single row and just returns the existing id.
        val existing = dao.findByContent(item.sourceCode, item.targetCode, item.sourceText)
        if (existing != null) {
            if (existing.translatedText != item.translatedText) {
                dao.update(FavoriteEntity.fromDomain(item.copy(id = existing.id)))
            }
            return existing.id
        }
        return dao.insert(FavoriteEntity.fromDomain(item))
    }

    override suspend fun update(item: FavoriteItem) = dao.update(FavoriteEntity.fromDomain(item))

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun deleteByContent(sourceCode: String, targetCode: String, sourceText: String) = dao.deleteByContent(sourceCode, targetCode, sourceText)

    override suspend fun clear() = dao.deleteAll()
}

/** Room-backed implementation of [GlossaryRepository]. */
class GlossaryRepositoryImpl(private val dao: GlossaryDao) : GlossaryRepository {

    override fun observe(): Flow<List<GlossaryTerm>> = dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<GlossaryTerm>> = dao.observeEnabled().map { entities -> entities.map { it.toDomain() } }

    override suspend fun add(term: GlossaryTerm): Long = dao.insert(GlossaryEntity.fromDomain(term))

    override suspend fun update(term: GlossaryTerm) = dao.update(GlossaryEntity.fromDomain(term))

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}
