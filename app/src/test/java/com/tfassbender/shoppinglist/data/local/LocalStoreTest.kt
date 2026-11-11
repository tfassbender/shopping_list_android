package com.tfassbender.shoppinglist.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `space files are shared per space, listed and deleted`() = runTest {
        val store = LocalStore(File(tmp.root, "store"))
        val a = store.spaceFile("space-a", Int.serializer()) { 0 }
        assertSame(a, store.spaceFile("space-a", Int.serializer()) { 0 })
        a.set(1)
        store.spaceFile("space-b", Int.serializer()) { 0 }.set(2)

        assertEquals(setOf("space-a", "space-b"), store.storedSpaceIds().toSet())

        store.deleteSpace("space-a")
        assertEquals(listOf("space-b"), store.storedSpaceIds())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a crafted space id can't escape the store`() {
        LocalStore(File(tmp.root, "store")).spaceFile("../me", Int.serializer()) { 0 }
    }

    @Test
    fun `clear deletes everything`() = runTest {
        val root = File(tmp.root, "store")
        val store = LocalStore(root)
        store.spaces.set(emptyList())
        store.spaceFile("s1", Int.serializer()) { 0 }.set(5)

        store.clear()

        assertFalse(root.exists())
        assertEquals(0, store.spaceFile("s1", Int.serializer()) { 0 }.get())
    }
}
