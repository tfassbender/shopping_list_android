package com.tfassbender.shoppinglist.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Mirrors the hue cases of the web app's `ColorPicker.test.tsx`. */
class ColorPickerTest {

    @Test
    fun `hue of hex colors, greys and invalid input have hue 0`() {
        assertEquals(0, hueOf("#ff0000"))
        assertEquals(120, hueOf("#00ff00"))
        assertEquals(240, hueOf("#0000ff"))
        assertEquals(0, hueOf("#808080"))
        assertEquals(0, hueOf("not a color"))
    }

    @Test
    fun `soft colors are valid hex codes and keep their hue`() {
        for (hue in listOf(0, 45, 120, 200, 300)) {
            val color = softColor(hue.toFloat())
            assertTrue(color, Regex("^#[0-9a-f]{6}$").matches(color))
            assertTrue("$hue -> ${hueOf(color)}", abs(hueOf(color) - hue) <= 5)
        }
    }

    @Test
    fun `palette has 16 valid colors`() {
        assertEquals(16, PALETTE.size)
        assertTrue(PALETTE.all { Regex("^#[0-9a-f]{6}$").matches(it) })
    }
}
