package io.github.sorjo.pdnsautoswitch

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ObservationRunState { STOPPED, STARTING, RUNNING }
data class ObservationViewState(
    val runState: ObservationRunState = ObservationRunState.STOPPED,
    val lines: List<String> = emptyList(),
    val ready: Boolean = false,
    val storageError: Boolean = false,
    val startFailed: Boolean = false,
    val loggingEnabled: Boolean = true,
)

/** Single writer per process. Running state is deliberately never persisted or restored. */
class ObservationRepository private constructor(context: Context) {
    private sealed interface Command {
        data class Append(val entry: ObservationEntry) : Command
        data object Clear : Command
        data class Flush(val done: CountDownLatch) : Command
        data class Snapshot(val result: CompletableDeferred<List<String>>) : Command
    }
    private val log = ObservationLog(File(context.noBackupFilesDir, "network-observation.log"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val preferences = context.getSharedPreferences("observation", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ObservationViewState(
        loggingEnabled = preferences.getBoolean("logging_enabled", true),
    ))
    val state: StateFlow<ObservationViewState> = mutable.asStateFlow()

    init {
        scope.launch {
            try { mutable.update { it.copy(lines = log.read(), ready = true) } }
            catch (_: IOException) { mutable.update { it.copy(ready = true, storageError = true) } }
            for (command in commands) {
                if (command is Command.Flush) {
                    try { log.sync() }
                    catch (_: IOException) { mutable.update { it.copy(storageError = true) } }
                    finally { command.done.countDown() }
                    continue
                }
                if (command is Command.Snapshot) {
                    if (mutable.value.storageError) command.result.completeExceptionally(IOException("History unavailable"))
                    else command.result.complete(mutable.value.lines)
                    continue
                }
                try {
                    val lines = when (command) {
                        is Command.Snapshot -> error("Snapshot handled above")
                        is Command.Flush -> error("Flush handled above")
                        is Command.Append -> log.append(command.entry)
                        Command.Clear -> { log.clear(); emptyList() }
                    }
                    mutable.update { it.copy(lines = lines, storageError = false) }
                } catch (_: IOException) {
                    mutable.update { it.copy(storageError = true) }
                }
            }
        }
    }

    fun record(entry: ObservationEntry) {
        if (state.value.loggingEnabled) commands.trySend(Command.Append(entry))
    }
    fun setLoggingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("logging_enabled", enabled).apply()
        mutable.update { it.copy(loggingEnabled = enabled) }
    }
    suspend fun snapshot(): List<String> {
        val result = CompletableDeferred<List<String>>()
        commands.send(Command.Snapshot(result)) // FIFO barrier: includes all earlier writes/clears.
        return result.await()
    }
    fun clear() { commands.trySend(Command.Clear) }
    /** Best effort shutdown barrier; IO stays on its writer, main waits at most one second. */
    fun flushOnStop() {
        val done = CountDownLatch(1)
        if (commands.trySend(Command.Flush(done)).isSuccess) {
            try { done.await(1, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
    fun setRunState(value: ObservationRunState, failed: Boolean = false) {
        mutable.update { it.copy(runState = value, startFailed = failed) }
    }

    companion object {
        @Volatile private var instance: ObservationRepository? = null
        fun get(context: Context): ObservationRepository = instance ?: synchronized(this) {
            instance ?: ObservationRepository(context.applicationContext).also { instance = it }
        }
    }
}
