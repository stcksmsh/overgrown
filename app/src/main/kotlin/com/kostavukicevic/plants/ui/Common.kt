// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberGraph(): Graph = LocalContext.current.graph

/** Recompose when anything in the garden changes. */
@Composable
fun changeTick(): Int {
    val tick by rememberGraph().changes.collectAsState()
    return tick
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}

/** The top-bar icon on the two tabs: a small menu with Places and Settings. */
@Composable
fun TopMenu(onPlaces: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.top_menu)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            val dot = LocalContentColor.current
            Canvas(Modifier.size(Spacing.grid * 6)) {
                val step = (Spacing.grid * 1.5f).toPx()
                for (i in -1..1) drawCircle(dot, radius = (Spacing.grid / 2).toPx(), center = center + Offset(0f, i * step))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.places_title)) }, onClick = { open = false; onPlaces() })
            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { open = false; onSettings() })
        }
    }
}

/** A row in the Plants and Places lists. Edit and Delete name the item for TalkBack. */
@Composable
fun ListRow(title: String, subtitle: String?, onOpen: (() -> Unit)?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val editLabel = stringResource(R.string.edit_named, title)
    val deleteLabel = stringResource(R.string.delete_named, title)
    Card(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)) {
        Row(Modifier.padding(start = Spacing.cardPadding, top = Spacing.grid, bottom = Spacing.grid), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteLabel }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Delete with Undo and no soft-delete column: the item is hidden at once and deleted for real only when its
 * snackbar closes without Undo, including when the next delete replaces it. If the app dies first, the item
 * survives.
 */
class UndoDelete(private val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    /** Keys of items waiting to be deleted, or deleted; lists leave them out. */
    val hidden = mutableStateListOf<String>()

    fun delete(key: String, message: String, undoLabel: String, commit: suspend () -> Unit) {
        hidden += key
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // With an action, showSnackbar defaults to Indefinite; the delete must commit on its own.
            if (snackbar.showSnackbar(message, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) hidden -= key
            else withContext(NonCancellable) { commit() }
        }
    }

    fun say(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

fun positive(s: String): Double? = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
