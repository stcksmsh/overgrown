// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
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
import com.kostavukicevic.plants.ui.theme.BlockButton
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Type
import kotlinx.coroutines.CancellationException
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
    /** The display-size title of a tab's root screen. */
    large: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val ground = Overgrown.colors.ground
    Scaffold(
        containerColor = ground,
        topBar = {
            TopAppBar(
                title = { Text(title, style = if (large) Type.display else Type.cardTitle) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { actions() },
                // The bar is the screen's own ground, not a band of another colour.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ground, scrolledContainerColor = ground),
                expandedHeight = if (large) Spacing.grid * 20 else TopAppBarDefaults.TopAppBarExpandedHeight,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.grid * 2),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            content = content,
        )
    }
}

/** A secondary button on a state block: white with dark ink in both themes, as in the Today mockup (D41). */
@Composable
internal fun OnBlockButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = Spacing.buttonHeight),
    shape = RoundedCornerShape(Radius.button),
    colors = ButtonDefaults.buttonColors(containerColor = BlockButton.fill, contentColor = BlockButton.text),
) { Text(label, style = Type.label) }

/** Destructive: brick fill (DESIGN section 5). Only where a confirmation or an Undo follows. */
@Composable
internal fun DeleteButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = stringResource(R.string.delete)) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = Spacing.buttonHeight),
    shape = RoundedCornerShape(Radius.button),
    colors = ButtonDefaults.buttonColors(containerColor = StateBlock.Brick.fill, contentColor = StateBlock.Brick.text),
    contentPadding = PaddingValues(horizontal = Spacing.grid * 3, vertical = Spacing.grid * 2),
) { Text(label, style = Type.label) }

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        // Named, so TalkBack says which switch it is and tests can find it.
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // A long label at a large font size takes two lines: the chip grows around it and the rows keep a gap.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
            for ((value, text) in options) {
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text, Modifier.padding(vertical = Spacing.grid * 2)) },
                )
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

/** One row of the Plants or Places list. [key] is what [UndoDelete] hides while its delete is pending. */
class ListItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val onOpen: (() -> Unit)?,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
)

/** The Plants and Places lists: rows with Edit and Delete, or one line and an Add button when empty. */
@Composable
fun ListPage(
    title: String,
    onBack: (() -> Unit)?,
    addLabel: String,
    onAdd: () -> Unit,
    empty: String,
    undo: UndoDelete,
    items: List<ListItem>?,
    menu: @Composable () -> Unit = {},
) {
    Page(
        title = title,
        onBack = onBack,
        actions = {
            TextButton(onClick = onAdd) { Text(addLabel) }
            menu()
        },
    ) {
        val list = items?.filter { it.key !in undo.hidden } ?: return@Page
        if (list.isEmpty()) {
            Text(empty)
            Button(onClick = onAdd) { Text(addLabel) }
            return@Page
        }
        for (item in list) ListRow(item)
    }
}

/** Edit and Delete name the item for TalkBack. The card padding lets the row grow with the text size. */
@Composable
private fun ListRow(item: ListItem) {
    val editLabel = stringResource(R.string.edit_named, item.title)
    val deleteLabel = stringResource(R.string.delete_named, item.title)
    Card(Modifier.fillMaxWidth().then(if (item.onOpen != null) Modifier.clickable(onClick = item.onOpen) else Modifier)) {
        Row(
            Modifier.heightIn(min = Spacing.minTouchTarget).padding(start = Spacing.cardPadding, top = Spacing.cardPadding, bottom = Spacing.cardPadding, end = Spacing.grid),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = item.onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(stringResource(R.string.edit)) }
            DeleteButton(item.onDelete, Modifier.semantics { contentDescription = deleteLabel })
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

    /** [failed] is shown, and the item comes back, if [commit] throws. */
    fun delete(key: String, message: String, undoLabel: String, failed: String, commit: suspend () -> Unit) {
        hidden += key
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            // With an action, showSnackbar defaults to Indefinite; the delete must commit on its own.
            if (snackbar.showSnackbar(message, undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                hidden -= key
                return@launch
            }
            val ok = withContext(NonCancellable) {
                try {
                    commit()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("UndoDelete", "delete of $key failed", e)
                    false
                }
            }
            if (!ok) {
                hidden -= key
                say(failed)
            }
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
