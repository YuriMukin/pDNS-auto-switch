package io.github.sorjo.pdnsautoswitch

import android.content.Context
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

enum class ObservationRunState { STOPPED, STARTING, RUNNING }
data class ObservationViewState(
    val runState: ObservationRunState = ObservationRunState.STOPPED,
    val lines: List<String> = emptyList(),
    val ready: Boolean = false,
    val storageError: Boolean = false,
    val startFailed: Boolean = false,
)

/** Single writer per process. Running state is deliberately never persisted or restored. */
class ObservationRepository private constructor(context: Context) {
    private sealed interface Command {
        data class Append(val entry: ObservationEntry) : Command
        data object Clear : Command
    }
    private val log = ObservationLog(File(context.noBackupFilesDir, "network-observation.log"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(ObservationViewState())
    val state: StateFlow<ObservationViewState> = mutable.asStateFlow()

    init {
        scope.launch {
            try { mutable.update { it.copy(lines = log.read(), ready = true) } }
            catch (_: IOException) { mutable.update { it.copy(ready = true, storageError = true) } }
            for (command in commands) {
                try {
                    val lines = when (command) {
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

    fun record(entry: ObservationEntry) { commands.trySend(Command.Append(entry)) }
    fun clear() { commands.trySend(Command.Clear) }
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
