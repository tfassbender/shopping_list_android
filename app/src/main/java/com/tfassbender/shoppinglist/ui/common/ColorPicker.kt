package com.tfassbender.shoppinglist.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.ui.list.parseColor
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Soft colors that fit the light blue design and stay readable as small dots (incl. the colors of the
 * default categories) - the web app's palette, so a space's categories look like one set on both clients.
 */
val PALETTE = listOf(
    "#e57373", "#f06292", "#ba68c8", "#9575cd",
    "#7986cb", "#64b5f6", "#90caf9", "#4dd0e1",
    "#4db6ac", "#4caf50", "#aed581", "#fff176",
    "#ffb74d", "#d4a056", "#a1887f", "#78909c",
)

private val HEX = Regex("^#[0-9a-f]{6}$")

/** A round swatch showing the current color; tapping it opens the color sheet. */
@Composable
fun ColorSwatch(value: String, label: String, onChange: (String) -> Unit, size: Dp = 36.dp, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(parseColor(value))
            .border(1.dp, AppTheme.colors.line, CircleShape)
            .clickable(enabled = enabled, role = Role.Button) { open = true }
            .semantics { contentDescription = label },
    )
    if (open) {
        ColorSheet(value.lowercase(), label, onSave = {
            open = false
            onChange(it)
        }, onDismiss = { open = false })
    }
}

/** Three ways to pick, all editing one draft color: a palette tap, the hue slider or a hex code. */
@Composable
private fun ColorSheet(initial: String, label: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var color by rememberSaveable { mutableStateOf(initial) }
    // The hex field keeps what the user types, even while it is incomplete ("#4d").
    var hexText by rememberSaveable { mutableStateOf(initial) }
    val swatches = if (initial in PALETTE) PALETTE else listOf(initial) + PALETTE
    val valid = HEX.matches(color) && HEX.matches(normalizeHex(hexText))
    fun choose(next: String) {
        color = next
        hexText = next
    }

    AppSheet(stringResource(R.string.color_title), onDismiss) {
        Column(Modifier.semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            swatches.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { swatch ->
                        val selected = swatch == color
                        Box(
                            Modifier
                                .size(52.dp)
                                .then(if (selected) Modifier.border(3.dp, colors.accent, CircleShape).padding(5.dp) else Modifier)
                                .clip(CircleShape)
                                .background(parseColor(swatch))
                                .clickable(role = Role.RadioButton) { choose(swatch) }
                                .semantics {
                                    contentDescription = swatch
                                    this.selected = selected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                    repeat(4 - row.size) { Box(Modifier.size(52.dp)) }
                }
            }
        }
        HorizontalDivider(color = colors.line)
        Text(stringResource(R.string.color_custom), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient((0..6).map { softColorValue(it * 60f) }))
                    .align(Alignment.Center),
            )
            val hueLabel = stringResource(R.string.color_hue)
            Slider(
                value = hueOf(color).toFloat(),
                onValueChange = { choose(softColor(it)) },
                valueRange = 0f..359f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                modifier = Modifier.semantics { contentDescription = hueLabel },
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(if (valid) parseColor(color) else Color.Transparent)
                    .border(1.dp, colors.line, CircleShape),
            )
            AppTextField(
                hexText,
                { text ->
                    hexText = text
                    val normalized = normalizeHex(text)
                    if (HEX.matches(normalized)) color = normalized
                },
                stringResource(R.string.color_hex),
                modifier = Modifier.width(160.dp),
                maxLength = 7,
                keyboardType = KeyboardType.Ascii,
                onImeAction = { if (valid) onSave(color) },
            )
        }
        SheetActions {
            AppButton(stringResource(R.string.common_cancel), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.common_apply), { onSave(color) }, enabled = valid)
        }
    }
}

private fun normalizeHex(text: String): String = (if (text.startsWith("#")) text else "#$text").trim().lowercase()

/** Saturation and lightness of the slider colors: as soft as the palette (same as the web app). */
private const val SATURATION = 0.6f
private const val LIGHTNESS = 0.6f

private fun softChannels(hue: Float): List<Int> {
    val a = SATURATION * min(LIGHTNESS, 1 - LIGHTNESS)
    return listOf(0, 8, 4).map { n ->
        val k = (n + hue / 30f) % 12
        val value = LIGHTNESS - a * max(-1f, minOf(k - 3, 9 - k, 1f))
        (value * 255).roundToInt()
    }
}

/** The color at [hue] (0-359°) with the soft saturation/lightness, as #rrggbb. */
fun softColor(hue: Float): String = "#" + softChannels(hue).joinToString("") { "%02x".format(it) }

private fun softColorValue(hue: Float): Color = softChannels(hue).let { (r, g, b) -> Color(r, g, b) }

/** The hue (0-359°) of a #rrggbb color, so the slider starts where the current color is (0 for greys). */
fun hueOf(hex: String): Int {
    if (!HEX.matches(hex)) return 0
    val (r, g, b) = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255f }
    val maxC = maxOf(r, g, b)
    val delta = maxC - minOf(r, g, b)
    if (abs(delta) < 1e-6) return 0
    val sector = when (maxC) {
        r -> ((g - b) / delta) % 6
        g -> (b - r) / delta + 2
        else -> (r - g) / delta + 4
    }
    return ((sector * 60 + 360) % 360).roundToInt() % 360
}
