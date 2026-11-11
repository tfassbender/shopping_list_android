package com.tfassbender.shoppinglist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {

    @Test
    fun `normalization matches the server`() {
        assertEquals("whole milk", normalizeName("  Whole \t MILK "))
    }

    @Test
    fun `limits give the server's error codes`() {
        assertNull(Limits.checkName("Groceries"))
        assertEquals("INVALID_NAME", Limits.checkName(" "))
        assertEquals("INVALID_NAME", Limits.checkName("x".repeat(61)))
        assertNull(Limits.checkItemName("x".repeat(100)))
        assertEquals("INVALID_ITEM_NAME", Limits.checkItemName("x".repeat(101)))
        assertNull(Limits.checkItemField(ItemField.QUANTITY, null))
        assertEquals("INVALID_ITEM_FIELD", Limits.checkItemField(ItemField.NOTE, "x".repeat(201)))
        assertEquals("INVALID_ITEM_NAME", Limits.checkItemField(ItemField.NAME, ""))
    }
}
