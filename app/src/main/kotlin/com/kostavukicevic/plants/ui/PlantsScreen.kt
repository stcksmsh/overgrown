// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.voicedResource

@Composable
fun PlantsScreen(
    undo: UndoDelete,
    onPlant: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
    onPlaces: () -> Unit,
    onSettings: () -> Unit,
) {
    val g = rememberGraph()
    val plants by g.garden.plants.collectAsState(null)
    val places by g.garden.places.collectAsState(null)
    val deleted = stringResource(R.string.deleted)
    val undoLabel = stringResource(R.string.undo)
    val failed = stringResource(R.string.delete_failed)

    ListPage(
        title = stringResource(R.string.plants_title),
        onBack = null,
        addLabel = stringResource(R.string.add_plant),
        onAdd = onAdd,
        empty = voicedResource(R.string.plants_empty),
        undo = undo,
        menu = { TopMenu(onPlaces, onSettings) },
        items = plants?.map { p ->
            val key = "plant:${p.id}"
            ListItem(
                key = key,
                title = p.name,
                subtitle = places?.find { it.id == p.placeId }?.name,
                onOpen = { onPlant(p.id) },
                onEdit = { onEdit(p.id) },
                onDelete = {
                    undo.delete(key, deleted.format(p.name), undoLabel, failed.format(p.name)) {
                        g.garden.deletePlant(p.id)
                        g.scheduler.reschedule()
                        g.changed()
                    }
                },
            )
        },
    )
}
