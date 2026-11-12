package com.tfassbender.shoppinglist.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfassbender.shoppinglist.R
import com.tfassbender.shoppinglist.data.sync.SyncStatus
import com.tfassbender.shoppinglist.domain.Category
import com.tfassbender.shoppinglist.domain.MergeWarning
import com.tfassbender.shoppinglist.domain.WarningType
import com.tfassbender.shoppinglist.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Header badges (port of the web app's SyncIndicator): "Offline" with the number of unsaved changes,
 * "Saving..." while queued changes are sent, and the ⚠ button for merge notes (unless the user hides them).
 */
@Composable
fun SyncIndicator(compact: Boolean) {
    val container = LocalAppContainer.current
    val online by container.online.collectAsStateWithLifecycle(true)
    val status by container.syncEngine.status.collectAsStateWithLifecycle()
    val pending by container.syncEngine.pendingCount.collectAsStateWithLifecycle(0)
    val warnings by container.syncEngine.warnings.collectAsStateWithLifecycle(emptyList())
    val user by container.account.user.collectAsStateWithLifecycle(null)
    var open by remember { mutableStateOf(false) }
    val showWarnings = warnings.isNotEmpty() && user?.user?.settings?.ignoreMergeWarnings != true
    val colors = AppTheme.colors

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!online) OfflineBadge(pending)
        if (online && status == SyncStatus.SYNCING && pending > 0) {
            Badge { Text(stringResource(R.string.offline_saving), fontSize = 15.sp, color = colors.brandInk) }
        }
        if (showWarnings) {
            val label = stringResource(R.string.warnings_open, warnings.size)
            Box(
                Modifier
                    .size(if (compact) 40.dp else 48.dp)
                    .clip(CircleShape)
                    .clickable { open = true }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text("⚠", fontSize = if (compact) 19.sp else 21.sp, color = colors.brandInk)
            }
        }
    }
    if (open) WarningsSheet(warnings, onDismiss = { open = false })
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(AppTheme.colors.brandInk.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) { content() }
}

/** Compact: "Offline" plus the number of unsaved changes in a bubble; screen readers get the full sentence. */
@Composable
private fun OfflineBadge(pending: Int) {
    val colors = AppTheme.colors
    val text = if (pending > 0) stringResource(R.string.offline_badgePending, pending) else stringResource(R.string.offline_badge)
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.brandInk.copy(alpha = 0.1f))
            .semantics(mergeDescendants = true) { contentDescription = text }
            .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.offline_badge), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.brandInk)
        if (pending > 0) {
            Text(
                pending.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.brandInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 20.dp)
                    .clip(CircleShape)
                    .background(colors.brandInk.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun WarningsSheet(warnings: List<MergeWarning>, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    // Category names for "category" changes, from the cached catalogs of the spaces involved.
    val categories by produceState(emptyMap<String, List<Category>>(), warnings) {
        value = warnings.map { it.spaceId }.distinct().associateWith { spaceId ->
            container.catalog.catalog(spaceId).first()?.categories.orEmpty()
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale) }
    AppSheet(stringResource(R.string.warnings_title), onDismiss) {
        Text(stringResource(R.string.warnings_explanation), color = colors.muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            warnings.reversed().forEach { warning ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.raised)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(min = 24.dp),
                ) {
                    val names = categories[warning.spaceId].orEmpty()
                    Text(warningText(warning) { id -> names.firstOrNull { it.id == id }?.name ?: id ?: "-" }, style = MaterialTheme.typography.bodySmall)
                    Text(formatter.format(warning.at.atZone(ZoneId.systemDefault())), fontSize = 13.sp, color = colors.muted)
                }
            }
        }
        SheetActions {
            AppButton(stringResource(R.string.common_close), onDismiss, variant = ButtonVariant.GHOST)
            AppButton(stringResource(R.string.warnings_clear), {
                scope.launch { container.syncEngine.clearWarnings() }
                onDismiss()
            })
        }
    }
}

/** Human-readable text of a merge warning, e.g. "Anna had set the quantity of "Milk" to "3" ...". */
@Composable
fun warningText(w: MergeWarning, categoryName: (String?) -> String): String {
    val by = w.byName ?: stringResource(R.string.warnings_someone)
    val checked = stringResource(R.string.warnings_checked)
    val unchecked = stringResource(R.string.warnings_unchecked)
    return when (w.type) {
        WarningType.OVERWRITTEN -> {
            val value = { v: String? ->
                when (w.field) {
                    "CATEGORY" -> categoryName(v)
                    "CHECKED" -> if (v == "true") checked else unchecked
                    else -> v ?: "-"
                }
            }
            if (w.itemId == null) {
                stringResource(R.string.warnings_listRenamed, by, value(w.theirValue), value(w.yourValue))
            } else {
                stringResource(R.string.warnings_overwritten, by, fieldName(w.field), w.itemName.orEmpty(), value(w.theirValue), value(w.yourValue))
            }
        }
        WarningType.RESTORED -> stringResource(R.string.warnings_restored, by, w.itemName.orEmpty())
        WarningType.DELETE_SKIPPED -> stringResource(R.string.warnings_deleteSkipped, by, w.itemName.orEmpty())
        WarningType.LIST_DELETED -> stringResource(R.string.warnings_listDeleted, w.listName.orEmpty())
        WarningType.REJECTED -> stringResource(R.string.warnings_rejected, errorCodeText(w.code))
    }
}

@Composable
private fun fieldName(field: String?): String = when (field) {
    "NAME" -> stringResource(R.string.warnings_field_NAME)
    "QUANTITY" -> stringResource(R.string.warnings_field_QUANTITY)
    "UNIT" -> stringResource(R.string.warnings_field_UNIT)
    "NOTE" -> stringResource(R.string.warnings_field_NOTE)
    "CATEGORY" -> stringResource(R.string.warnings_field_CATEGORY)
    "CHECKED" -> stringResource(R.string.warnings_field_CHECKED)
    else -> field.orEmpty()
}

/** Before login the language can be switched here (EN/DE); afterwards it is a user setting. */
@Composable
fun LanguageToggle() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val current = LocalConfiguration.current.locales[0].language
    val other = if (current == "de") "en" else "de"
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { scope.launch { container.uiPreferences.setDeviceLanguage(other) } }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(other.uppercase(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppTheme.colors.brandInk)
    }
}
