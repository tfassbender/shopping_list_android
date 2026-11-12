package com.tfassbender.shoppinglist.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.ui.theme.AppTheme

/**
 * A vertical list whose rows can be reordered by dragging the handle (⠿) - port of the web app's
 * SortableList. Screen readers get "Move up" / "Move down" actions instead. The parent owns the order:
 * [onReorder] receives the new key order when a drag ends. Meant for short lists (categories, shop order),
 * so it is a plain Column inside the page's scroll.
 */
@Composable
fun <T> SortableColumn(
    items: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    onReorder: (List<String>) -> Unit,
    enabled: Boolean = true,
    row: @Composable (T) -> Unit,
) {
    val colors = AppTheme.colors
    val byKey = items.associateBy(key)
    // The order shown while dragging; otherwise the parent's.
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val order = (dragOrder ?: items.map(key)).filter { it in byKey }
    val currentOrder by rememberUpdatedState(order)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val moveUp = stringResource(R.string.sortable_moveUp)
    val moveDown = stringResource(R.string.sortable_moveDown)

    fun move(from: Int, to: Int) {
        val keys = currentOrder.toMutableList()
        keys.add(to, keys.removeAt(from))
        currentOnReorder(keys)
    }

    Column(Modifier.outlinedBox()) {
        order.forEachIndexed { index, itemKey ->
            // key: a row keeps its state (and its running drag gesture) when the order changes.
            key(itemKey) {
                val item = byKey.getValue(itemKey)
                val dragging = itemKey == draggedKey
                val handleLabel = stringResource(R.string.sortable_handle, label(item))
                Column(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) offset else 0f }
                        .then(if (dragging) Modifier.shadow(8.dp) else Modifier)
                        .background(colors.surface)
                        .onSizeChanged { heights[itemKey] = it.height },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = handleLabel
                                    customActions = listOfNotNull(
                                        if (index > 0) CustomAccessibilityAction(moveUp) { move(index, index - 1); true } else null,
                                        if (index < order.lastIndex) CustomAccessibilityAction(moveDown) { move(index, index + 1); true } else null,
                                    )
                                }
                                .then(
                                    if (!enabled) Modifier else Modifier.pointerInput(itemKey) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragOrder = currentOrder
                                                draggedKey = itemKey
                                                offset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                offset += amount.y
                                                val keys = dragOrder?.toMutableList() ?: return@detectDragGestures
                                                var i = keys.indexOf(itemKey)
                                                // Swap with a neighbour once the row is dragged over half of it.
                                                while (i < keys.lastIndex && offset > (heights[keys[i + 1]] ?: 0) / 2f) {
                                                    offset -= heights[keys[i + 1]] ?: 0
                                                    keys.add(i + 1, keys.removeAt(i))
                                                    i++
                                                }
                                                while (i > 0 && offset < -(heights[keys[i - 1]] ?: 0) / 2f) {
                                                    offset += heights[keys[i - 1]] ?: 0
                                                    keys.add(i - 1, keys.removeAt(i))
                                                    i--
                                                }
                                                dragOrder = keys
                                            },
                                            onDragEnd = {
                                                val result = dragOrder
                                                if (result != null && result != items.map(key)) currentOnReorder(result)
                                                dragOrder = null
                                                draggedKey = null
                                                offset = 0f
                                            },
                                            onDragCancel = {
                                                dragOrder = null
                                                draggedKey = null
                                                offset = 0f
                                            },
                                        )
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.DragIndicator, contentDescription = null, tint = colors.muted)
                        }
                        Box(Modifier.weight(1f)) { row(item) }
                    }
                    if (index < order.lastIndex) HorizontalDivider(color = colors.line)
                }
            }
        }
    }
}
