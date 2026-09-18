package com.axis.translate.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axis.translate.data.db.AxisDatabase
import com.axis.translate.data.db.HistoryDao
import com.axis.translate.data.db.HistoryEntity
import com.axis.translate.data.repo.FavoritesRepositoryImpl
import com.axis.translate.data.repo.GlossaryRepositoryImpl
import com.axis.translate.data.repo.HistoryRepositoryImpl
import com.axis.translate.domain.model.FavoriteItem
import com.axis.translate.domain.model.GlossaryTerm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room DAO + repository tests over an in-memory [AxisDatabase]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDaoTest {

    private lateinit var database: AxisDatabase
    private lateinit var historyDao: HistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AxisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyDao = database.historyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `history insert and getAll order by timestamp desc`() = runTest {
        historyDao.insert(historyEntity(timestamp = 1_000L, sourceText = "old", translatedText = "lama"))
        historyDao.insert(historyEntity(timestamp = 3_000L, sourceText = "new", translatedText = "baru"))
        historyDao.insert(historyEntity(timestamp = 2_000L, sourceText = "middle", translatedText = "tengah"))

        val all = historyDao.getAll().first()

        assertEquals(listOf("new", "middle", "old"), all.map { it.sourceText })
    }

    @Test
    fun `history search matches source or translated text`() = runTest {
        historyDao.insert(historyEntity(sourceText = "good morning", translatedText = "selamat pagi"))
        historyDao.insert(historyEntity(sourceText = "good evening", translatedText = "selamat malam"))
        historyDao.insert(historyEntity(sourceText = "coffee", translatedText = "kopi"))

        val bySource = historyDao.search("morning").first()
        assertEquals(listOf("good morning"), bySource.map { it.sourceText })

        val byTranslation = historyDao.search("kopi").first()
        assertEquals(listOf("coffee"), byTranslation.map { it.sourceText })

        assertTrue(historyDao.search("nonexistent").first().isEmpty())
    }

    @Test
    fun `history repository search escapes LIKE wildcards`() = runTest {
        val repository = HistoryRepositoryImpl(historyDao)
        historyDao.insert(historyEntity(sourceText = "100% correct", translatedText = "benar"))
        historyDao.insert(historyEntity(sourceText = "100x correct", translatedText = "benar sekali"))
        historyDao.insert(historyEntity(sourceText = "snake_case", translatedText = "ular"))
        historyDao.insert(historyEntity(sourceText = "snakeXcase", translatedText = "ular palsu"))
        historyDao.insert(historyEntity(sourceText = "a\\b", translatedText = "huruf"))

        val percent = repository.search("100%").first()
        assertEquals(listOf("100% correct"), percent.map { it.sourceText })

        val underscore = repository.search("snake_case").first()
        assertEquals(listOf("snake_case"), underscore.map { it.sourceText })

        val backslash = repository.search("a\\b").first()
        assertEquals(listOf("a\\b"), backslash.map { it.sourceText })

        assertTrue(repository.search("50%").first().isEmpty())
    }

    @Test
    fun `setFavorite updates the row and filters observeFavorites`() = runTest {
        val repository = HistoryRepositoryImpl(historyDao)
        val starredId = historyDao.insert(historyEntity(sourceText = "starred", translatedText = "dibintangi"))
        historyDao.insert(historyEntity(sourceText = "plain", translatedText = "polos"))

        historyDao.setFavorite(starredId, true)
        assertEquals(true, historyDao.getById(starredId)?.isFavorite)
        assertEquals(listOf("starred"), historyDao.observeFavorites().first().map { it.sourceText })
        assertEquals(2, historyDao.getAll().first().size)

        historyDao.setFavorite(starredId, false)
        assertEquals(false, historyDao.getById(starredId)?.isFavorite)
        assertTrue(historyDao.observeFavorites().first().isEmpty())
        assertTrue(repository.observeFavorites().first().isEmpty())
    }

    @Test
    fun `history update deleteById and deleteAll`() = runTest {
        val id = historyDao.insert(historyEntity(sourceText = "before", translatedText = "sebelum"))

        val stored = historyDao.getById(id)
        assertNotNull(stored)
        historyDao.update(stored!!.copy(translatedText = "after", isFavorite = true))
        val updated = historyDao.getById(id)
        assertNotNull(updated)
        assertEquals("before", updated!!.sourceText)
        assertEquals("after", updated.translatedText)
        assertTrue(updated.isFavorite)

        historyDao.deleteById(id)
        assertNull(historyDao.getById(id))

        historyDao.insert(historyEntity(sourceText = "one", translatedText = "satu"))
        historyDao.insert(historyEntity(sourceText = "two", translatedText = "dua"))
        historyDao.deleteAll()
        assertTrue(historyDao.getAll().first().isEmpty())
    }

    @Test
    fun `favorites add update search and delete through the repository`() = runTest {
        val repository = FavoritesRepositoryImpl(database.favoriteDao())

        val firstId = repository.add(
            favoriteItem(sourceText = "kopi", translatedText = "coffee", note = "morning drink")
        )
        repository.add(favoriteItem(sourceText = "teh", translatedText = "tea"))
        assertEquals(2, repository.observe().first().size)

        repository.update(
            favoriteItem(
                id = firstId,
                sourceText = "kopi susu",
                translatedText = "milk coffee",
                note = "updated"
            )
        )
        val afterUpdate = repository.observe().first()
        assertEquals(2, afterUpdate.size)
        assertEquals("kopi susu", afterUpdate.first { it.id == firstId }.sourceText)
        assertEquals("updated", afterUpdate.first { it.id == firstId }.note)

        assertEquals(1, repository.search("milk coffee").first().size)
        assertEquals(1, repository.search("kopi").first().size)
        assertEquals(0, repository.search("cappuccino").first().size)

        repository.delete(firstId)
        assertEquals(1, repository.observe().first().size)

        repository.clear()
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun `favorites add dedups by source text and language pair`() = runTest {
        val repository = FavoritesRepositoryImpl(database.favoriteDao())

        val firstId = repository.add(favoriteItem(sourceText = "kopi", translatedText = "coffee"))
        val secondId = repository.add(favoriteItem(sourceText = "kopi", translatedText = "coffee (updated)"))

        assertEquals(firstId, secondId)
        val items = repository.observe().first()
        assertEquals(1, items.size)
        assertEquals("coffee (updated)", items.first().translatedText)
    }

    @Test
    fun `history star flags stay in sync with favorites removal`() = runTest {
        val historyRepository = HistoryRepositoryImpl(historyDao)
        val favoritesRepository = FavoritesRepositoryImpl(database.favoriteDao())

        val historyId = historyDao.insert(historyEntity(sourceText = "kopi", translatedText = "coffee"))
        historyRepository.setFavorite(historyId, true)
        favoritesRepository.add(favoriteItem(sourceText = "kopi", translatedText = "coffee"))
        assertNotNull(historyRepository.get(historyId))
        assertTrue(historyRepository.get(historyId)!!.isFavorite)

        // Un-star via the favorites side: history flags must follow.
        favoritesRepository.deleteByContent("en", "id", "kopi")
        assertTrue(!historyRepository.get(historyId)!!.isFavorite)

        // Re-star and clear the whole favorites list: flags reset again.
        historyRepository.setFavoriteByContent("en", "id", "kopi", true)
        favoritesRepository.clear()
        historyRepository.clearFavoriteFlags()
        assertTrue(!historyRepository.get(historyId)!!.isFavorite)
    }

    @Test
    fun `glossary add observe ordering enabled filter update and delete`() = runTest {
        val repository = GlossaryRepositoryImpl(database.glossaryDao())

        repository.add(glossaryTerm(source = "phone", target = "telepon", createdAt = 100L))
        repository.add(glossaryTerm(source = "computer", target = "komputer", createdAt = 300L, enabled = false))
        repository.add(glossaryTerm(source = "mouse", target = "tikus", createdAt = 200L))

        val all = repository.observe().first()
        assertEquals(listOf("computer", "mouse", "phone"), all.map { it.source })

        val enabled = repository.observeEnabled().first()
        assertEquals(listOf("mouse", "phone"), enabled.map { it.source })

        val mouse = all.first { it.source == "mouse" }
        repository.update(mouse.copy(target = "tetikus", caseSensitive = true, enabled = false))
        val afterUpdate = repository.observe().first()
        val updatedTerm = afterUpdate.first { it.id == mouse.id }
        assertEquals("tetikus", updatedTerm.target)
        assertTrue(updatedTerm.caseSensitive)
        assertTrue(repository.observeEnabled().first().none { it.id == mouse.id })

        repository.delete(mouse.id)
        assertEquals(2, repository.observe().first().size)

        repository.clear()
        assertTrue(repository.observe().first().isEmpty())
    }

    private fun historyEntity(
        timestamp: Long = 1_000L,
        sourceCode: String = "en",
        targetCode: String = "id",
        sourceText: String = "source",
        translatedText: String = "terjemahan"
    ) = HistoryEntity(
        timestamp = timestamp,
        sourceCode = sourceCode,
        targetCode = targetCode,
        sourceText = sourceText,
        translatedText = translatedText,
        inputType = "TEXT",
        isFavorite = false,
        detectedLanguageCode = null,
        photoPath = null,
        ocrText = null,
        durationMs = 250L
    )

    private fun favoriteItem(id: Long = 0L, sourceText: String, translatedText: String, note: String = "") = FavoriteItem(
        id = id,
        timestamp = 1_000L,
        sourceCode = "en",
        targetCode = "id",
        sourceText = sourceText,
        translatedText = translatedText,
        note = note
    )

    private fun glossaryTerm(source: String, target: String, createdAt: Long, enabled: Boolean = true) = GlossaryTerm(
        source = source,
        target = target,
        sourceCode = "en",
        targetCode = "id",
        caseSensitive = false,
        enabled = enabled,
        createdAt = createdAt
    )
}
