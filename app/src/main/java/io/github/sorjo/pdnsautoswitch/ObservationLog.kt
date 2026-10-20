package io.github.sorjo.pdnsautoswitch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

/** Single-writer bounded history: append normally, compact in batches to avoid repeated full rewrites. */
class ObservationLog(private val file: File, private val capacity: Int = 2500) {
    init { require(capacity > 0) }
    private var history: ArrayDeque<String>? = null
    private var normalized = false

    fun read(): List<String> {
        if (history == null) {
            val loaded = ArrayDeque<String>()
            var trimmed = false
            if (file.exists()) file.forEachLine { line ->
                if (line.length <= 4096 && line.split(" - ").size == 3) {
                    if (loaded.size == capacity) { loaded.removeFirst(); trimmed = true }
                    loaded.addLast(line)
                }
            }
            if (trimmed) {
                // Apply a smaller limit on load, even if no new network event is appended.
                write(loaded.toList())
                normalized = true
            }
            history = loaded
        }
        return history!!.toList()
    }

    fun append(entry: ObservationEntry): List<String> {
        read()
        val line = entry.line()
        require(line.length <= 4096) { "Observation entry too long" }
        val current = history!!
        try {
            if (!normalized || current.size >= capacity) {
                val remove = if (current.size >= capacity) maxOf(1, capacity / 10) else 0
                val next = current.drop(remove) + line
                write(next)
                history = ArrayDeque(next)
                normalized = true
            } else {
                file.appendText("$line\n")
                current.addLast(line)
            }
        } catch (error: IOException) {
            history = null
            normalized = false
            throw error
        }
        return history!!.toList()
    }

    fun clear() {
        write(emptyList())
        history = ArrayDeque()
        normalized = true
    }

    fun sync() {
        if (file.exists()) FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun write(lines: List<String>) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.tmp")
        pending.bufferedWriter().use { writer -> lines.forEach { writer.append(it).append('\n') } }
        try {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
