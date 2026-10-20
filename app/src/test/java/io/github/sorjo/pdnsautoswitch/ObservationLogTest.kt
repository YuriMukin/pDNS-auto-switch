package io.github.sorjo.pdnsautoswitch

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObservationLogTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `format has three columns and embedded newlines cannot forge events`() {
        val line = ObservationEntry("2026-10-19T12:00:00+03:00", "WIFI\nFAKE - NEW", "OFF\rMORE").line()
        assertEquals(3, line.split(" - ").size)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
    }

    @Test fun `history is bounded durable and can be cleared independently`() {
        val file = folder.root.resolve("observation.log")
        val log = ObservationLog(file, capacity = 3)
        for (number in 1..5) log.append(ObservationEntry("t$number", "MOBILE", "HOSTNAME"))
        val restored = ObservationLog(file, capacity = 3)
        assertEquals(listOf("t3", "t4", "t5"), restored.read().map { it.substringBefore(" - ") })
        restored.clear()
        assertTrue(ObservationLog(file).read().isEmpty())
    }

    @Test fun `broken and excessively long stored lines are ignored`() {
        val file = folder.root.resolve("damaged.log")
        file.writeText("broken\n" + "x".repeat(5000) + " - WIFI - OFF\n" + "t1 - MOBILE - HOSTNAME\n")
        assertEquals(listOf("t1 - MOBILE - HOSTNAME"), ObservationLog(file).read())
    }

    @Test fun `legacy history is trimmed on load and compacts only the oldest tenth`() {
        val file = folder.root.resolve("large.log")
        file.bufferedWriter().use { writer ->
            for (number in 1..50_010) writer.append("t$number - WIFI - OFF\n")
        }
        val log = ObservationLog(file)
        assertEquals(2500, log.read().size)
        assertEquals(2500, file.readLines().size)
        assertTrue(log.read().first().startsWith("t47511 - "))
        log.append(ObservationEntry("t50011", "MOBILE", "HOSTNAME"))
        val restored = ObservationLog(file).read()
        assertEquals(2251, restored.size)
        assertTrue(restored.first().startsWith("t47761 - "))
        assertTrue(restored.last().startsWith("t50011 - "))
    }

    @Test fun `append repairs interrupted tail and preserves valid history`() {
        val file = folder.root.resolve("interrupted.log")
        file.writeText("t1 - WIFI - OFF\npartial")
        val log = ObservationLog(file)
        log.read()
        log.append(ObservationEntry("t2", "MOBILE", "HOSTNAME"))
        log.append(ObservationEntry("t3", "MOBILE", "HOSTNAME"))
        assertEquals(listOf("t1", "t2", "t3"), ObservationLog(file).read().map { it.substringBefore(" - ") })
    }


    @Test fun `history at the limit stays intact until the next append`() {
        val file = folder.root.resolve("full.log")
        file.bufferedWriter().use { writer ->
            for (number in 1..2500) writer.append("t$number - MOBILE - HOSTNAME\n")
        }
        val log = ObservationLog(file)
        assertEquals(2500, log.read().size)
        assertEquals("t1 - MOBILE - HOSTNAME", log.read().first())
        log.append(ObservationEntry("t2501", "MOBILE", "HOSTNAME"))
        assertEquals(2251, log.read().size)
        assertEquals("t251 - MOBILE - HOSTNAME", log.read().first())
        assertEquals(2251, file.readLines().size)
    }
}
