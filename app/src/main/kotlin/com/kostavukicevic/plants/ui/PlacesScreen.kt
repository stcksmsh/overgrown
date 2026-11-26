// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.voicedResource
import kotlinx.coroutines.flow.first

@Composable
fun PlacesScreen(undo: UndoDelete, onBack: () -> Unit, onEdit: (Long) -> Unit, onAdd: () -> Unit) {
    val g = rememberGraph()
    val places by g.garden.places.collectAsState(null)
    val plants by g.garden.plants.collectAsState(null)
    val deleted = stringResource(R.string.deleted)
    val inUse = stringResource(R.string.place_in_use)
    val undoLabel = stringResource(R.string.undo)
    val failed = stringResource(R.string.delete_failed)

    ListPage(
        title = stringResource(R.string.places_title),
        onBack = onBack,
        addLabel = stringResource(R.string.add_place),
        onAdd = onAdd,
        empty = voicedResource(R.string.places_empty),
        undo = undo,
        // Shown once the plants are known too, so a delete tap never lands before the in-use check can run.
        items = places?.takeIf { plants != null }?.map { p ->
            val key = "place:${p.id}"
            ListItem(
                key = key,
                title = p.name,
                subtitle = null,
                onOpen = null,
                onEdit = { onEdit(p.id) },
                onDelete = onDelete@{
                    // Deleting a place deletes its plants with it, so a place in use is never deleted from here.
                    // Plants waiting on their own Undo still count: they still exist.
                    val users = plants.orEmpty().filter { it.placeId == p.id }
                    if (users.isNotEmpty()) {
                        undo.say(inUse.format(p.name, users.joinToString(", ") { it.name }))
                        return@onDelete
                    }
                    undo.delete(key, deleted.format(p.name), undoLabel, failed.format(p.name)) {
                        // A plant may have been put here while the snackbar was showing.
                        if (g.garden.plants.first().none { it.placeId == p.id }) g.garden.deletePlace(p.id) else undo.hidden -= key
                        g.changed()
                    }
                },
            )
        },
    )
}
