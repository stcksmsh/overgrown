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

    Page(
        title = stringResource(R.string.plants_title),
        actions = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            TopMenu(onPlaces, onSettings)
        },
    ) {
        val list = plants?.filter { "plant:${it.id}" !in undo.hidden } ?: return@Page
        if (list.isEmpty()) {
            Text(stringResource(R.string.plants_empty))
            Button(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            return@Page
        }
        for (p in list) {
            ListRow(
                title = p.name,
                subtitle = places?.find { it.id == p.placeId }?.name,
                onOpen = { onPlant(p.id) },
                onEdit = { onEdit(p.id) },
                onDelete = {
                    undo.delete("plant:${p.id}", deleted.format(p.name), undoLabel) {
                        g.garden.deletePlant(p.id)
                        g.scheduler.reschedule()
                        g.changed()
                    }
                },
            )
        }
    }
}
