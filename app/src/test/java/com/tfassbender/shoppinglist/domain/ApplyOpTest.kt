package com.tfassbender.shoppinglist.domain

import com.tfassbender.shoppinglist.data.local.StoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApplyOpTest {

    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val base: ListsById = mapOf(
        "l1" to ShoppingList(
            "l1", "s1", "Groceries", now, seq = 3,
            items = listOf(
                Item("i1", "Milk", quantity = "2", categoryId = "dairy", createdAt = now, seq = 2),
                Item("i2", "Bread", checked = true, createdAt = now, seq = 3),
            ),
        ),
    )

    private fun apply(op: SyncOp, lists: ListsById = base) = applyOp(lists, op, "s1", now)

    @Test
    fun `create and rename list, names trimmed`() {
        val created = apply(SyncOp.CreateList("c1", "l2", " Hardware "))
        assertEquals("Hardware", created.getValue("l2").name)
        assertEquals(0L, created.getValue("l2").seq)

        assertEquals("Food", apply(SyncOp.RenameList("c2", "l1", " Food ")).getValue("l1").name)
    }

    @Test
    fun `creating an existing list or adding an existing item changes nothing`() {
        assertSame(base, apply(SyncOp.CreateList("c1", "l1", "Other")))
        assertEquals(base, apply(SyncOp.AddItem("c1", "l1", "i1", "Other")))
    }

    @Test
    fun `add item at the end, blank optional fields become null`() {
        val item = apply(SyncOp.AddItem("c1", "l1", "i3", " Eggs ", quantity = " 10 ", unit = " ", note = null, categoryId = "")).getValue("l1").items.last()

        assertEquals(Item("i3", "Eggs", quantity = "10", createdAt = now), item)
    }

    @Test
    fun `edit fields, clearing optional ones, never clearing the name`() {
        var lists = apply(SyncOp.EditItem("c1", "l1", "i1", ItemField.QUANTITY, ""))
        lists = apply(SyncOp.EditItem("c2", "l1", "i1", ItemField.NOTE, " lactose free "), lists)
        lists = apply(SyncOp.EditItem("c3", "l1", "i1", ItemField.NAME, "  "), lists)
        lists = apply(SyncOp.EditItem("c4", "l1", "i1", ItemField.CATEGORY, null), lists)

        val milk = lists.getValue("l1").items.first()
        assertNull(milk.quantity)
        assertEquals("lactose free", milk.note)
        assertEquals("Milk", milk.name)
        assertNull(milk.categoryId)
    }

    @Test
    fun `check, uncheck and delete`() {
        assertTrue(apply(SyncOp.CheckItem("c1", "l1", "i1")).getValue("l1").items.first().checked)
        assertEquals(false, apply(SyncOp.UncheckItem("c1", "l1", "i2")).getValue("l1").items.last().checked)
        assertEquals(listOf("i2"), apply(SyncOp.DeleteItem("c1", "l1", "i1")).getValue("l1").items.map { it.id })
    }

    @Test
    fun `remove checked only removes the items the user saw checked`() {
        val lists = apply(SyncOp.RemoveChecked("c1", "l1", listOf("i1")))
        assertEquals(listOf("i2"), lists.getValue("l1").items.map { it.id })
    }

    @Test
    fun `changes to an unknown list are ignored (the server will reject them)`() {
        assertSame(base, apply(SyncOp.CheckItem("c1", "gone", "i1")))
    }

    @Test
    fun `sync ops serialize with the server's type names`() {
        val op: SyncOp = SyncOp.EditItem("c1", "l1", "i1", ItemField.QUANTITY, "3")
        val json = StoreJson.encodeToString(SyncOp.serializer(), op)

        assertTrue(json, json.contains("\"type\":\"EDIT_ITEM\""))
        assertEquals(op, StoreJson.decodeFromString(SyncOp.serializer(), json))
    }

    // --- applyPull ---

    private fun pull(
        lists: List<SyncList>,
        listIds: List<String>,
        fullResync: Boolean = false,
    ) = PullResult(seq = 10, fullResync = fullResync, lists = lists, listIds = listIds, catalog = null, layouts = null, layoutIds = emptyList())

    @Test
    fun `pull merges changed items, removes tombstones and keeps untouched items`() {
        val changed = SyncList(
            "l1", "Groceries", now, 10, deleted = false,
            items = listOf(
                SyncItem(Item("i1", "Milk", quantity = "3", createdAt = now, seq = 10), deleted = false),
                SyncItem(Item("i2", "Bread", createdAt = now, seq = 9), deleted = true),
                SyncItem(Item("i3", "Eggs", createdAt = now, seq = 10), deleted = false),
            ),
        )

        val result = applyPull(base, pull(listOf(changed), listOf("l1")), "s1").getValue("l1")

        assertEquals(listOf("i1", "i3"), result.items.map { it.id })
        assertEquals("3", result.items.first().quantity)
        assertEquals(10L, result.seq)
    }

    @Test
    fun `deleted lists and lists missing in listIds disappear`() {
        val withSecond = base + ("l2" to ShoppingList("l2", "s1", "Old", now))
        val deleted = SyncList("l1", "Groceries", now, 10, deleted = true, items = emptyList())

        assertEquals(emptySet<String>(), applyPull(withSecond, pull(listOf(deleted), listOf()), "s1").keys)
    }

    @Test
    fun `full resync drops everything not in the answer`() {
        val fresh = SyncList("l9", "New", now, 10, deleted = false, items = listOf(SyncItem(Item("x", "X", createdAt = now), false)))

        val result = applyPull(base, pull(listOf(fresh), listOf("l9"), fullResync = true), "s1")

        assertEquals(setOf("l9"), result.keys)
        assertEquals("s1", result.getValue("l9").spaceId)
    }
}
