package com.axis.translate.domain

import com.axis.translate.domain.model.BatchState
import com.axis.translate.domain.model.BatchTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process batch translation queue (SPEC #64): sequential execution with
 * pause / resume / cancel / retry semantics, observable by both the UI
 * ([com.axis.translate.ui.batch.BatchScreen]) and the foreground
 * [com.axis.translate.service.BatchTranslationService].
 */
class BatchQueue {

    /** UI-facing item state. */
    data class TaskState(
        val task: BatchTask,
        val state: BatchState = BatchState.PENDING,
        val result: String? = null,
        val error: String? = null,
        val index: Int = 0
    )

    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<TaskState>>(emptyList())
    val items: StateFlow<List<TaskState>> = _items.asStateFlow()

    private val runningState = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = runningState.asStateFlow()

    private val pausedState = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = pausedState.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private var runJob: Job? = null

    fun add(task: BatchTask) {
        _items.value = _items.value + TaskState(task = task, index = _items.value.size)
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.task.id == id }
    }

    fun clear() {
        cancelRequested.set(true)
        _items.value = emptyList()
    }

    fun retry(id: String) {
        _items.value = _items.value.map {
            if (it.task.id == id) it.copy(state = BatchState.PENDING, error = null, result = null) else it
        }
    }

    fun retryAllFailed() {
        _items.value = _items.value.map {
            if (it.state == BatchState.FAILED) it.copy(state = BatchState.PENDING, error = null) else it
        }
    }

    fun pause() {
        pausedState.value = true
    }

    fun resume() {
        pausedState.value = false
    }

    fun cancel() {
        cancelRequested.set(true)
        runJob?.cancel()
    }

    /**
     * Runs all pending tasks sequentially through [translate], updating
     * per-item state. Pause is cooperative (checked between tasks).
     * Returns the number of successfully completed tasks.
     */
    suspend fun runAll(translate: suspend (BatchTask) -> String): Result<Int> {
        mutex.withLock {
            if (runningState.value) return Result.failure(IllegalStateException("already running"))
            cancelRequested.set(false)
            runningState.value = true
            var completed = 0
            try {
                while (true) {
                    if (cancelRequested.get()) break
                    val current = _items.value.firstOrNull { it.state == BatchState.PENDING } ?: break
                    updateItem(current.task.id) { it.copy(state = BatchState.RUNNING) }
                    try {
                        val output = translate(current.task)
                        updateItem(current.task.id) {
                            it.copy(state = BatchState.DONE, result = output)
                        }
                        completed++
                    } catch (ce: CancellationException) {
                        updateItem(current.task.id) {
                            it.copy(state = BatchState.CANCELLED)
                        }
                        throw ce
                    } catch (error: Throwable) {
                        updateItem(current.task.id) {
                            it.copy(state = BatchState.FAILED, error = error.message ?: "failed")
                        }
                    }
                    // cooperative pause
                    while (pausedState.value && !cancelRequested.get()) {
                        kotlinx.coroutines.delay(PAUSE_POLL_MS)
                    }
                }
                return Result.success(completed)
            } finally {
                runningState.value = false
            }
        }
    }

    private fun updateItem(id: String, transform: (TaskState) -> TaskState) {
        _items.value = _items.value.map { if (it.task.id == id) transform(it) else it }
    }

    companion object {
        private const val PAUSE_POLL_MS = 200L

        /** Default shared instance used by the service and the UI. */
        @Volatile
        private var shared: BatchQueue? = null

        fun shared(): BatchQueue = shared ?: synchronized(this) {
            shared ?: BatchQueue().also { shared = it }
        }
    }
}
