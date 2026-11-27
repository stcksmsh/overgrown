// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.theme.BlockButton
import com.kostavukicevic.plants.ui.theme.Motion
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
import kotlin.math.roundToInt
import kotlin.math.sign

/** The shared-element scope as screens change (D44); null while switching tabs, with animations removed, and outside App. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** The enter and exit of the screen this is drawn in; null outside App. */
internal val LocalScreenTransition = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** False in a screen that is leaving: its back handlers stand down so a back press goes to the screen below (D44). */
internal val LocalScreenIsTop = compositionLocalOf { true }

/**
 * Marks [part] ("card", "name" or "glyph") of the plant or list item [key] as the same thing on Today, on Plants
 * and on the plant screen, so it grows from one into the other as the screen changes. Does nothing otherwise.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedPlant(part: String, key: String): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val screen = LocalScreenTransition.current ?: return this
    return with(shared) {
        this@sharedPlant.sharedBounds(rememberSharedContentState("$part/$key"), screen, boundsTransform = { _, _ -> tween(Motion.MEDIUM2, easing = Motion.Emphasized) })
    }
}

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
                title = {
                    val style = if (large) Type.display else Type.cardTitle
                    Text(title, Modifier.semantics { heading() }, style = style, autoSize = WholeWords(style.fontSize))
                },
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
        // One answer of several: TalkBack says "radio button, 1 of 3" and "selected", not "checkbox".
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
            for ((value, text) in options) {
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text, Modifier.padding(vertical = Spacing.grid * 2)) },
                    modifier = Modifier.semantics { role = Role.RadioButton },
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
    Card(Modifier.fillMaxWidth().sharedPlant("card", item.key).then(if (item.onOpen != null) Modifier.clickable(onClick = item.onOpen) else Modifier)) {
        BesideOrUnder(
            Modifier.heightIn(min = Spacing.minTouchTarget).padding(start = Spacing.cardPadding, top = Spacing.cardPadding, bottom = Spacing.cardPadding, end = Spacing.grid),
            main = {
                Column {
                    Text(item.title, Modifier.sharedPlant("name", item.key), style = MaterialTheme.typography.titleMedium)
                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = item.onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(stringResource(R.string.edit)) }
                DeleteButton(item.onDelete, Modifier.semantics { contentDescription = deleteLabel })
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

/**
 * [main] with [trailing] at its end, as a Row whose main part takes the room left. [trailing] goes under [main],
 * [under] apart, only when beside it [main]'s longest word would not fit, as at 2.0x font scale on a narrow phone.
 * Under an unbounded width the two sit side by side, each at its own width.
 */
@Composable
internal fun BesideOrUnder(modifier: Modifier = Modifier, under: Dp = 0.dp, main: @Composable () -> Unit, trailing: @Composable () -> Unit) =
    Layout(listOf(main, trailing), modifier) { (mainItems, trailingItems), constraints ->
        val m = mainItems.single()
        val t = trailingItems.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        // Unbounded, as when a parent asks for the intrinsic width, there is room for both side by side.
        if (!constraints.hasBoundedWidth) {
            val mp = m.measure(constraints.copy(minWidth = 0))
            val h = maxOf(mp.height, t.height, constraints.minHeight)
            return@Layout layout(mp.width + t.width, h) {
                mp.placeRelative(0, Alignment.CenterVertically.align(mp.height, h))
                t.placeRelative(mp.width, Alignment.CenterVertically.align(t.height, h))
            }
        }
        val width = constraints.maxWidth
        val room = width - t.width
        if (m.minIntrinsicWidth(constraints.maxHeight) <= room) {
            val mp = m.measure(Constraints(minWidth = room, maxWidth = room, maxHeight = constraints.maxHeight))
            val h = maxOf(mp.height, t.height, constraints.minHeight)
            layout(width, h) {
                mp.placeRelative(0, Alignment.CenterVertically.align(mp.height, h))
                t.placeRelative(room, Alignment.CenterVertically.align(t.height, h))
            }
        } else {
            val mp = m.measure(Constraints(minWidth = width, maxWidth = width))
            val gap = under.roundToPx()
            layout(width, maxOf(mp.height + gap + t.height, constraints.minHeight)) {
                mp.placeRelative(0, 0)
                t.placeRelative(0, mp.height + gap)
            }
        }
    }

/** True if a line ends inside a word: the box is narrower than the word, so it reads as cut text. */
internal fun TextLayoutResult.breaksAWord(): Boolean {
    val text = layoutInput.text.text
    return (0 until lineCount - 1).map { getLineEnd(it) }.any { e -> e in 1 until text.length && text[e - 1].isLetterOrDigit() && text[e].isLetterOrDigit() }
}

/**
 * Text size for a display title: [max], its style's size, stepped down only as far as needed for every word to fit
 * on a line. At normal font scales nothing changes; at 2.0x a long name gets smaller instead of breaking mid-word.
 */
internal data class WholeWords(private val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max
        while (size > smallest && performLayout(constraints, text, size).breaksAWord()) size *= 0.9f
        return size
    }

    /** The smallest size it steps down to. Near half the style's size: below it a title stops reading as one. */
    val smallest: TextUnit
        get() {
            var size = max
            while (size.value * 0.9f >= max.value / 2) size *= 0.9f
            return size
        }
}

/**
 * Items of equal width, [columns] to a row, each row as tall as its tallest item. If an item's longest word would
 * not fit that width, as at 2.0x font scale on a narrow phone, it takes fewer columns rather than break the word.
 * Under an unbounded width all items share one row, each at its own width.
 */
@Composable
internal fun EvenRows(columns: Int, modifier: Modifier = Modifier, gap: Dp = Spacing.grid * 2, content: @Composable () -> Unit) =
    Layout(content, modifier) { items, constraints ->
        val gapPx = gap.roundToPx()
        // Unbounded, as when a parent asks for the intrinsic width, every item fits one row at its own width.
        if (!constraints.hasBoundedWidth) {
            val row = items.map { it.measure(constraints.copy(minWidth = 0)) }
            val h = row.maxOfOrNull { it.height } ?: 0
            return@Layout layout(row.sumOf { it.width } + gapPx * (row.size - 1).coerceAtLeast(0), h) {
                var x = 0
                row.forEach { it.placeRelative(x, 0); x += it.width + gapPx }
            }
        }
        val width = constraints.maxWidth
        // Widths as Row gives weighted items: each the rounded even share, the pixel or two left over or short
        // taken up by the first items.
        fun widths(n: Int): List<Int> {
            val space = (width - gapPx * (n - 1)).coerceAtLeast(0)
            val share = (space.toFloat() / n).roundToInt()
            var rest = space - share * n
            return List(n) { val one = rest.sign; rest -= one; share + one }
        }
        val n = (columns downTo 2).firstOrNull { cols -> widths(cols).min().let { w -> items.all { it.minIntrinsicWidth(constraints.maxHeight) <= w } } } ?: 1
        val w = widths(n)
        val rows = items.chunked(n).map { row ->
            // An item that fills the height takes the tallest one's; the others keep their own.
            val h = row.withIndex().maxOf { (i, it) -> it.minIntrinsicHeight(w[i]) }
            row.mapIndexed { i, it -> it.measure(Constraints(minWidth = w[i], maxWidth = w[i], maxHeight = h)) }
        }
        val height = rows.sumOf { r -> r.maxOf { it.height } } + gapPx * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            for (r in rows) {
                var x = 0
                r.forEach { it.placeRelative(x, y); x += it.width + gapPx }
                y += r.maxOf { it.height } + gapPx
            }
        }
    }

