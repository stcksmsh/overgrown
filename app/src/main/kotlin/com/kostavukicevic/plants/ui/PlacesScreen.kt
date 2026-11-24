// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import kotlinx.coroutines.flow.first

@Composable
fun PlacesScreen(undo: UndoDelete, onBack: () -> Unit, onEdit: (Long) -> Unit, onAdd: () -> Unit) {
    val g = rememberGraph()
    val places by g.garden.places.collectAsState(null)
    val plants by g.garden.plants.collectAsState(null)
    val deleted = stringResource(R.string.deleted)
    val inUse = stringResource(R.string.place_in_use)
    val undoLabel = stringResource(R.string.undo)

    Page(
        title = stringResource(R.string.places_title),
        onBack = onBack,
        actions = { TextButton(onClick = onAdd) { Text(stringResource(R.string.add_place)) } },
    ) {
        val list = places?.filter { "place:${it.id}" !in undo.hidden } ?: return@Page
        if (list.isEmpty()) {
            Text(stringResource(R.string.places_empty))
            Button(onClick = onAdd) { Text(stringResource(R.string.add_place)) }
            return@Page
        }
        for (p in list) {
            ListRow(
                title = p.name,
                subtitle = null,
                onOpen = null,
                onEdit = { onEdit(p.id) },
                onDelete = {
                    // Deleting a place deletes its plants with it, so a place in use is never deleted from here.
                    // Plants waiting on their own Undo still count: they still exist.
                    val users = plants?.filter { it.placeId == p.id } ?: return@ListRow
                    if (users.isNotEmpty()) {
                        undo.say(inUse.format(p.name, users.joinToString(", ") { it.name }))
                    } else {
                        undo.delete("place:${p.id}", deleted.format(p.name), undoLabel) {
                            // A plant may have been put here while the snackbar was showing.
                            if (g.garden.plants.first().none { it.placeId == p.id }) g.garden.deletePlace(p.id) else undo.hidden -= "place:${p.id}"
                            g.changed()
                        }
                    }
                },
            )
        }
    }
}
