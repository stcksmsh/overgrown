// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.caredb.Context as CareContext
import com.kostavukicevic.plants.data.CareSource
import com.kostavukicevic.plants.data.EventDateBeforeAcquired
import com.kostavukicevic.plants.data.EventDateFuture
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.OutlookDay
import com.kostavukicevic.plants.data.PhotoEntity
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.File
import java.io.IOException
import kotlin.time.Instant

@Composable
fun PlantScreen(plantId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val res = LocalResources.current
    val tick = changeTick()
    val status by produceState<PlantStatus?>(null, tick) { value = g.garden.status(plantId) }
    val outlook by produceState(emptyList<OutlookDay>(), status) { value = status?.let { g.garden.outlook(it) } ?: emptyList() }
    val events by g.garden.events(plantId).collectAsState(null)
    val weighing by g.settings.weighing.collectAsState(false)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Its own snackbar: deleting a history entry offers Undo on this screen.
    val undo = remember { UndoDelete(snackbar, scope) }
    val uri = LocalUriHandler.current
    var askRanOut by remember { mutableStateOf(false) }
    val photos by g.garden.photos(plantId).collectAsState(emptyList())
    var askPhotoSource by remember { mutableStateOf(false) }
    var deletePhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    // Survives the activity being recreated while the camera app is in front.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun act(block: suspend () -> Unit) = scope.launch { block(); g.scheduler.reschedule(); g.changed() }

    fun addPhoto(read: () -> ByteArray?) = scope.launch {
        photoMessage = try {
            // A camera that reports success without writing, or an empty file, leaves nothing to store.
            val bytes = withContext(Dispatchers.IO) { read() }?.takeIf { it.isNotEmpty() }
                ?: throw IOException(res.getString(R.string.photo_unreadable))
            val today = g.garden.today()
            val date = takenOn(bytes)?.takeIf { it <= today } ?: today
            g.garden.addPhoto(plantId, withContext(Dispatchers.Default) { forStorage(bytes) }, date)
            null
        } catch (e: IOException) {
            res.getString(R.string.photo_failed, e.message)
        } catch (e: IllegalArgumentException) {
            res.getString(R.string.photo_failed, e.message)
        } catch (_: OutOfMemoryError) {
            res.getString(R.string.photo_failed, res.getString(R.string.photo_too_large))
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile?.let(::File) ?: return@rememberLauncherForActivityResult
        cameraFile = null
        if (taken) addPhoto { file.takeIf { it.isFile }?.readBytes().also { file.delete() } } else file.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        addPhoto { c.contentResolver.openInputStream(picked)?.use { it.readBytes() } }
    }

    val colors = Overgrown.colors
    Scaffold(
        containerColor = colors.ground,
        snackbarHost = {
            SnackbarHost(snackbar) { Snackbar(it, containerColor = colors.ink, contentColor = colors.onInk, actionColor = colors.onInk) }
        },
    ) { padding ->
        val s = status
        val log = events
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.grid * 2),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        ) {
            if (s == null || log == null) return@Column
            val today = g.garden.today()
            NameBlock(
                name = s.plant.name,
                typeAndPlace = plantType(s)?.let { stringResource(R.string.plant_type_and_place, it, s.place.name) } ?: s.place.name,
                glyph = glyphFor(s.plant.archetypeId),
                waterLeft = (1 - s.state.depletion).toFloat(),
                onBack = onBack,
                onEdit = onEdit,
            )

            SurfaceCard {
                // "Nothing to do" next to a frost warning reads as "no need to protect it"; only watering is meant.
                val action = if (s.today == Action.None && outlook.any { it.frost }) stringResource(R.string.today_nothing_to_water) else Texts.action(c, s.today)
                Text(action, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.ink)
                Texts.reason(c, s)?.let { Text(it, style = Type.secondary, color = colors.ink) }
                for (r in s.reminders) Text(Texts.reminder(c, r), style = Type.secondary, color = colors.ink)
                for (a in s.alerts) Text(Texts.alert(c, a), style = Type.secondary, color = colors.errorText)
                s.snoozedUntil?.let { Text(stringResource(R.string.snoozed_until, Texts.date(it)), style = Type.secondary, color = colors.ink) }
                Text(
                    s.nextPrompt?.let { (d, a) -> stringResource(R.string.next_prompt, Texts.action(c, a), Texts.date(d)) }
                        ?: stringResource(R.string.next_prompt_none),
                    style = Type.secondary,
                    color = colors.muted,
                )
            }

            if (outlook.isNotEmpty()) DayStrip(outlook, next = s.nextPrompt?.first)

            LearningDots(
                name = s.plant.name,
                // The engine keeps no answer count; every logged soil answer is one (check-ins and answers given when watering).
                // Entries waiting out a delete's Undo no longer count.
                answers = log.count { it.moisture != null && "event:${it.id}" !in undo.hidden },
                learned = s.state.belief.sd < Coefficients.CONFIDENT_SD,
            )

            Text(stringResource(R.string.plant_check_in), style = Type.label, color = colors.ink)
            AnswerButtons { m -> act { g.garden.checkIn(plantId, m.name) } }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                PrimaryButton(stringResource(R.string.plant_watered), onClick = { askRanOut = true }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.plant_snooze), onClick = { act { g.garden.snooze(plantId) } }, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                SecondaryButton(stringResource(R.string.plant_moved), onClick = { act { g.garden.moved(plantId) } })
                SecondaryButton(stringResource(R.string.plant_repotted), onClick = { act { g.garden.repotted(plantId) } })
                SecondaryButton(stringResource(R.string.plant_fed), onClick = { act { g.garden.fed(plantId) } })
                SecondaryButton(stringResource(R.string.photo_add), onClick = { askPhotoSource = true })
            }
            photoMessage?.let { Text(it, style = Type.secondary, color = colors.errorText) }

            SurfaceCard {
                Text(stringResource(R.string.plant_history), style = Type.cardTitle, color = colors.ink)
                // Newest first; on the same day a photo comes after that day's events. Capped like the events,
                // because the page is not a lazy list and every photo shown is decoded.
                val shown = log.filter { "event:${it.id}" !in undo.hidden }.take(50)
                val timeline = (shown.map { LocalDate.parse(it.date) to it } + photos.take(MAX_TIMELINE_PHOTOS).map { LocalDate.parse(it.date) to it })
                    .sortedByDescending { it.first }
                if (timeline.isEmpty()) Text(stringResource(R.string.plant_history_empty), style = Type.secondary, color = colors.ink)
                for ((monday, items) in byWeek(timeline) { it.first }) {
                    WeekHeading(weekHeading(c, monday, today))
                    for ((_, item) in items) when (item) {
                        is EventEntity -> {
                            val line = historyLine(c, item)
                            HistoryRow(
                                line,
                                onEdit = { editError = null; editing = item },
                                onDelete = {
                                    undo.delete("event:${item.id}", res.getString(R.string.deleted, line), res.getString(R.string.undo), res.getString(R.string.delete_failed, line)) {
                                        g.garden.deleteEvent(item.id)
                                        g.scheduler.reschedule()
                                        g.changed()
                                    }
                                },
                            )
                        }
                        is PhotoEntity -> TimelinePhoto(item, onDelete = { deletePhoto = item })
                    }
                }
            }

            SurfaceCard { CareInfo(s, onReport = { uri.openUri(reportUrl(s)) }) }

            if (weighing) SurfaceCard { Calibration(plantId) }

            TextButton(onClick = { act { g.garden.deletePlant(plantId) }; onBack() }, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.delete), style = Type.label, color = colors.errorText)
            }
        }
    }

    editing?.let { e ->
        EditEntryDialog(
            e,
            error = editError,
            onDismiss = { editing = null },
            onSave = { edited ->
                scope.launch {
                    editError = try {
                        g.garden.updateEvent(edited)
                        null
                    } catch (x: CancellationException) {
                        throw x
                    } catch (_: EventDateFuture) {
                        res.getString(R.string.plant_entry_future)
                    } catch (x: EventDateBeforeAcquired) {
                        res.getString(R.string.plant_entry_before_acquired, Texts.date(x.acquiredOn))
                    } catch (_: Exception) {
                        // A database failure, or a value the dialog should not have offered; the details mean nothing to the person.
                        res.getString(R.string.plant_entry_failed)
                    }
                    if (editError == null) {
                        editing = null
                        // The engine replays from the events, so the plant and Today recompute.
                        g.scheduler.reschedule()
                        g.changed()
                    }
                }
            },
        )
    }

    if (askRanOut) {
        AlertDialog(
            onDismissRequest = { askRanOut = false },
            title = { Text(stringResource(R.string.plant_watered_question)) },
            confirmButton = { TextButton(onClick = { askRanOut = false; act { g.garden.watered(plantId, ranOutOfBottom = true) } }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { askRanOut = false; act { g.garden.watered(plantId, ranOutOfBottom = false) } }) { Text(stringResource(R.string.no)) } },
        )
    }

    if (askPhotoSource) {
        AlertDialog(
            onDismissRequest = { askPhotoSource = false },
            title = { Text(stringResource(R.string.photo_add_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.photo_add_private), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        askPhotoSource = false
                        val (file, target) = cameraTarget(c)
                        cameraFile = file.path
                        try {
                            camera.launch(target)
                        } catch (_: ActivityNotFoundException) {
                            cameraFile = null
                            photoMessage = res.getString(R.string.photo_no_camera)
                        }
                    }) { Text(stringResource(R.string.photo_camera)) }
                    TextButton(onClick = {
                        askPhotoSource = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.photo_gallery)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askPhotoSource = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deletePhoto?.let { photo ->
        AlertDialog(
            onDismissRequest = { deletePhoto = null },
            title = { Text(stringResource(R.string.photo_delete_question)) },
            confirmButton = {
                TextButton(onClick = { deletePhoto = null; scope.launch { g.garden.deletePhoto(photo.id) } }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletePhoto = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The species' accepted name or the archetype's name, when the care database has it. */
@Composable
private fun plantType(s: PlantStatus): String? {
    val db = rememberGraph().careDb
    return s.plant.speciesSlug?.let { db?.species(it)?.taxon?.acceptedName }
        ?: s.plant.archetypeId?.let { db?.archetypes?.get(it)?.name }
}

@Composable
private fun TimelinePhoto(photo: PhotoEntity, onDelete: () -> Unit) {
    val g = rememberGraph()
    val longSidePx = with(LocalDensity.current) { THUMBNAIL_DP.dp.roundToPx() }
    val thumb by produceState<Thumb?>(null, photo.file) {
        value = withContext(Dispatchers.IO) {
            val file = g.garden.photoFile(photo)
            Thumb(thumbnail(file, longSidePx)?.asImageBitmap(), present = file.isFile)
        }
    }
    val date = Texts.date(LocalDate.parse(photo.date))
    Text(stringResource(R.string.history_photo, date), style = MaterialTheme.typography.bodySmall)
    val t = thumb ?: return
    when {
        t.bitmap != null -> Image(
            t.bitmap,
            contentDescription = stringResource(R.string.photo_description, date),
            modifier = Modifier.heightIn(max = THUMBNAIL_DP.dp),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
        // A backup restore brings the rows back but not the files, which are left out of backups.
        !t.present -> Text(stringResource(R.string.photo_missing), style = MaterialTheme.typography.bodySmall)
        else -> Text(stringResource(R.string.photo_unreadable), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onDelete) { Text(stringResource(R.string.photo_delete)) }
}

private class Thumb(val bitmap: ImageBitmap?, val present: Boolean)

/** Photos shown in the history; older ones stay stored and exported. */
private const val MAX_TIMELINE_PHOTOS = 30
private const val THUMBNAIL_DP = 240

@Composable
private fun CareInfo(s: PlantStatus, onReport: () -> Unit) {
    val g = rememberGraph()
    val species = s.plant.speciesSlug?.let { g.careDb?.species(it) }
    Text(
        when (s.careSource) {
            CareSource.SPECIES -> stringResource(R.string.plant_care_source_species, species?.taxon?.acceptedName ?: s.plant.speciesSlug ?: "")
            CareSource.ARCHETYPE -> stringResource(R.string.plant_care_source_archetype, g.careDb?.archetypes?.get(s.plant.archetypeId)?.name ?: "")
            CareSource.GENERIC -> stringResource(R.string.plant_care_source_generic)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    // In-ground hardiness is shown only as such: a pot's roots freeze far sooner.
    species?.groupsFor(CareContext.IN_GROUND)?.temperature?.minTempC?.let {
        Text(stringResource(R.string.cold_in_ground, it.toString()), style = MaterialTheme.typography.bodySmall)
    }
    if (s.careSource != CareSource.GENERIC) TextButton(onClick = onReport) { Text(stringResource(R.string.plant_report_wrong_data)) }
}

@Composable
private fun Calibration(plantId: Long) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    val entries by g.garden.calibration(plantId).collectAsState(emptyList())
    var grams by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Text(stringResource(R.string.calibration_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.calibration_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(grams, { grams = it; error = false }, label = { Text(stringResource(R.string.calibration_grams)) }, isError = error, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.calibration_note)) }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val v = positive(grams)
        if (v == null) error = true else scope.launch { g.garden.logMass(plantId, v, note.trim()); grams = ""; note = "" }
    }) { Text(stringResource(R.string.calibration_log)) }
    if (error) Text(stringResource(R.string.calibration_invalid), color = MaterialTheme.colorScheme.error)
    for (e in entries.asReversed().take(30)) {
        Text(stringResource(R.string.calibration_entry, Texts.dateTime(Instant.fromEpochMilliseconds(e.atEpochMs)), e.grams.toString(), e.note), style = MaterialTheme.typography.bodySmall)
    }
}

/** A prefilled issue in the data repository. */
private fun reportUrl(s: PlantStatus): String {
    val what = s.plant.speciesSlug ?: s.plant.archetypeId ?: "unknown"
    val title = Uri.encode("Wrong data: $what")
    val body = Uri.encode("Entry: $what\n\nWhat is wrong:\n\nSource that shows it (a link and a short quote):\n")
    return "https://github.com/stcksmsh/overgrown-data/issues/new?title=$title&body=$body"
}
