package com.tfassbender.shoppinglist

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tfassbender.shoppinglist.ui.AppRoot

/** The single activity; all pages are Compose (see ui/AppRoot.kt). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The top bar is light blue in both themes: dark status bar icons always.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        val container = (application as ShoppingListApp).container
        setContent { AppRoot(container) }
    }
}
