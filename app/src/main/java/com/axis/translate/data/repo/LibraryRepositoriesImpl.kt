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

    override fun observe(): Flow<List<HistoryItem>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<HistoryItem>> =
        dao.observeFavorites().map { entities -> entities.map { it.toDomain() } }

    override suspend fun get(id: Long): HistoryItem? = dao.getById(id)?.toDomain()

    override suspend fun search(query: String): Flow<List<HistoryItem>> =
        dao.search(query.escapeForLike()).map { entities -> entities.map { it.toDomain() } }

    override suspend fun add(item: HistoryItem): Long = dao.insert(HistoryEntity.fromDomain(item))

    override suspend fun update(item: HistoryItem) = dao.update(HistoryEntity.fromDomain(item))

    override suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}

/** Room-backed implementation of [FavoritesRepository]. */
class FavoritesRepositoryImpl(private val dao: FavoriteDao) : FavoritesRepository {

    override fun observe(): Flow<List<FavoriteItem>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun search(query: String): List<FavoriteItem> =
        dao.search(query.escapeForLike()).map { it.toDomain() }

    override suspend fun add(item: FavoriteItem): Long = dao.insert(FavoriteEntity.fromDomain(item))

    override suspend fun update(item: FavoriteItem) = dao.update(FavoriteEntity.fromDomain(item))

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}

/** Room-backed implementation of [GlossaryRepository]. */
class GlossaryRepositoryImpl(private val dao: GlossaryDao) : GlossaryRepository {

    override fun observe(): Flow<List<GlossaryTerm>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<GlossaryTerm>> =
        dao.observeEnabled().map { entities -> entities.map { it.toDomain() } }

    override suspend fun add(term: GlossaryTerm): Long = dao.insert(GlossaryEntity.fromDomain(term))

    override suspend fun update(term: GlossaryTerm) = dao.update(GlossaryEntity.fromDomain(term))

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.deleteAll()
}
