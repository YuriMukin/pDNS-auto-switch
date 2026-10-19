package io.github.sorjo.pdnsautoswitch

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ObservationEntry(val timestamp: String, val condition: String, val recommendation: String) {
    fun line(): String = listOf(timestamp, condition, recommendation).joinToString(" - ") {
        it.replace('\n', ' ').replace('\r', ' ').replace(" - ", " / ")
    }

    companion object {
        fun now(condition: String, recommendation: String) = ObservationEntry(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(ZoneId.systemDefault())),
            condition, recommendation,
        )
    }
}

/** Private bounded history; file is placed in noBackupFilesDir by the Android adapter. */
class ObservationLog(private val file: File, private val capacity: Int = 200) {
    init { require(capacity > 0) }

    fun read(): List<String> = if (file.exists()) {
        // Keep memory bounded even if a previous/interrupted version left a larger file.
        val history = ArrayDeque<String>()
        file.forEachLine { line ->
            if (line.length <= 4096 && line.contains(" - ")) {
                if (history.size == capacity) history.removeFirst()
                history.addLast(line)
            }
        }
        history.toList()
    } else emptyList()

    fun append(entry: ObservationEntry): List<String> {
        val lines = (read() + entry.line()).takeLast(capacity)
        write(lines)
        return lines
    }

    fun clear() = write(emptyList())

    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.tmp")
        pending.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        try {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
