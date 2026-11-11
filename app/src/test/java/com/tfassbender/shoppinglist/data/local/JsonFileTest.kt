package com.tfassbender.shoppinglist.data.local

import com.tfassbender.shoppinglist.domain.Space
import com.tfassbender.shoppinglist.domain.Role
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class JsonFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Counter(val count: Int = 0, val label: String = "")

    private fun counterFile(file: File = File(tmp.root, "dir/counter.json")) = JsonFile(file, Counter.serializer()) { Counter() }

    @Test
    fun `missing file gives the default, saved values survive a new instance`() = runTest {
        val file = File(tmp.root, "dir/counter.json")
        assertEquals(Counter(), counterFile(file).get())

        counterFile(file).update { it.copy(count = 3) }

        assertEquals(Counter(3), counterFile(file).get())
        assertTrue(file.readText().contains("\"schemaVersion\":1"))
        assertFalse(File(file.parentFile, "counter.json.tmp").exists())
    }

    @Test
    fun `unreadable file falls back to the default`() = runTest {
        val file = File(tmp.root, "broken.json").apply { writeText("{not json") }

        assertEquals(Counter(), counterFile(file).get())
    }

    @Test
    fun `unknown fields of a newer format are ignored, missing ones get defaults`() = runTest {
        val file = File(tmp.root, "old.json").apply { writeText("""{"schemaVersion":1,"data":{"count":2,"future":true}}""") }

        assertEquals(Counter(2, ""), counterFile(file).get())
    }

    @Test
    fun `concurrent updates are not lost`() = runTest {
        val counter = counterFile()

        (1..50).map { async { counter.update { it.copy(count = it.count + 1) } } }.awaitAll()

        assertEquals(50, counterFile(File(tmp.root, "dir/counter.json")).get().count)
    }

    @Test
    fun `flow emits updates and delete resets to the default`() = runTest {
        val counter = counterFile()
        counter.update { it.copy(label = "a") }
        assertEquals("a", counter.flow.first().label)

        counter.delete()

        assertEquals(Counter(), counter.flow.first())
        assertFalse(File(tmp.root, "dir/counter.json").exists())
    }

    @Test
    fun `domain models with instants round trip`() = runTest {
        val file = JsonFile(File(tmp.root, "spaces.json"), ListSerializer(Space.serializer())) { emptyList() }
        val space = Space("s1", "Home", Role.OWNER, 2, Instant.parse("2026-11-11T10:00:00Z"))

        file.set(listOf(space))

        assertEquals(listOf(space), JsonFile(File(tmp.root, "spaces.json"), ListSerializer(Space.serializer())) { emptyList() }.get())
    }
}
