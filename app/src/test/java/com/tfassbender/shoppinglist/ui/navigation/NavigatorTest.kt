package com.tfassbender.shoppinglist.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {

    @Test
    fun `push, back and to start`() {
        val navigator = Navigator(listOf(Entry(Screen.Home)))
        assertFalse(navigator.canGoBack)
        navigator.push(Screen.ListPage("s", "l"))
        navigator.push(Screen.ListPage("s", "l")) // a double tap doesn't stack the page twice
        navigator.push(Screen.Catalog("s"))
        assertEquals(3, navigator.stack.size)
        navigator.back()
        assertEquals(Screen.ListPage("s", "l"), navigator.current.screen)
        navigator.replace(Screen.Spaces)
        assertEquals(listOf(Screen.Home, Screen.Spaces), navigator.stack.map { it.screen })
        navigator.toStart()
        assertEquals(listOf(Screen.Home), navigator.stack.map { it.screen })
        navigator.back() // the root stays
        assertTrue(navigator.stack.size == 1)
    }

    @Test
    fun `the back stack survives saving and restoring, with its entry ids`() {
        val navigator = Navigator(listOf(Entry(Screen.Home)))
        navigator.push(Screen.LayoutEditor("s", "aldi"))
        val saved = with(Navigator.saver) { SaverScope { true }.save(navigator) }!!
        val restored = Navigator.saver.restore(saved)!!
        assertEquals(navigator.stack.toList(), restored.stack.toList())
    }
}
