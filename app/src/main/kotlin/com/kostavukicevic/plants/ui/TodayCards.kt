// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.AlertKind
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.alertEvents
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.ui.theme.LightColors
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.PotGauge
import com.kostavukicevic.plants.ui.theme.PotGaugeSize
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Type
import com.kostavukicevic.plants.ui.theme.waterLeftPercent
import kotlinx.datetime.LocalDate

/** An action that asks the person for something today: a "Do now" card. Holds wait, so they are not. */
private val Action.isDoNow get() = this is Action.WaterNow || this is Action.CheckSoil || this == Action.RepotOrWaterDaily

/** Today's plants in the order they are shown: weather alerts, then "Do now", then "All fine". */
internal class TodayGroups(list: List<PlantStatus>) {
    val alerts = list.filter { it.alerts.isNotEmpty() }
    val doNow = list.filter { it.today.isDoNow }
    val fine = list.filterNot { it.today.isDoNow }
}

/** Water left, 0 to 1, as the plant screen reads it from the engine's state. */
private val PlantStatus.waterLeft get() = 1 - state.depletion

/**
 * "Watered" on Today. A notification cannot ask whether water ran out of the pot, and neither does this one-tap
 * button: as from a notification, the dose includes extra to run through a drained pot, and an undrained pot cannot.
 */
internal suspend fun waterFromToday(garden: Garden, plant: PlantEntity) = garden.watered(plant.id, ranOutOfBottom = plant.drainageHoles)

/** The quick soil answers. "Dry" covers the Dry and Bone dry bands together (D31.4). */
private val answers = listOf(R.string.answer_wet to Moisture.TOO_WET, R.string.answer_ok to Moisture.OK, R.string.answer_dry to Moisture.DRY_OR_BONE_DRY)

/**
 * The Today screen below its top bar. With no plants, one sentence and one button and nothing else. Otherwise
 * one alert card per weather event in [events], "Do now" cards and "All fine" tiles, then [weather], which the
 * caller leaves empty where no weather applies. [events] defaults to every event, none marked Done.
 */
@Composable
internal fun TodayContent(
    list: List<PlantStatus>,
    today: LocalDate,
    onPlant: (Long) -> Unit,
    onAdd: () -> Unit,
    onWatered: (PlantStatus) -> Unit,
    onAnswer: (PlantStatus, Moisture) -> Unit,
    events: List<AlertEvent> = alertEvents(list, today),
    onDone: (AlertEvent) -> Unit = {},
    onDismiss: (AlertEvent) -> Unit = {},
    weather: @Composable () -> Unit,
) {
    if (list.isEmpty()) {
        EmptyToday(onAdd)
        return
    }
    val c = LocalContext.current
    val groups = TodayGroups(list)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        for (e in events) AlertCard(e, today, onDone = { onDone(e) }, onDismiss = { onDismiss(e) })
        if (groups.doNow.isEmpty()) {
            val alerts = groups.alerts.flatMap { it.alerts }
            Text(
                if (alerts.isEmpty()) stringResource(R.string.today_all_fine)
                else "${stringResource(R.string.today_nothing_to_water)} ${Texts.alertSummary(c, alerts, today)}",
                style = Type.body,
                color = Overgrown.colors.ink,
            )
        }
        for (s in groups.doNow) ActionCard(s, onOpen = { onPlant(s.plant.id) }, onWatered = { onWatered(s) }, onAnswer = { onAnswer(s, it) })
        for (row in groups.fine.chunked(2)) {
            // Tiles in a row share the taller one's height.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                for (s in row) RestingTile(s, today, Modifier.weight(1f).fillMaxHeight(), onOpen = { onPlant(s.plant.id) })
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        weather()
    }
}

@Composable
internal fun EmptyToday(onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid * 4)) {
        Text(stringResource(R.string.today_empty), style = Type.body, color = Overgrown.colors.ink)
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.buttonHeight),
            shape = RoundedCornerShape(Radius.button),
            colors = ButtonDefaults.buttonColors(containerColor = Overgrown.colors.ink, contentColor = Overgrown.colors.onInk),
        ) { Text(stringResource(R.string.today_add_first), style = Type.label) }
    }
}

/** The icon for an alert card, one per kind of weather. */
private val AlertKind.icon get() = when (this) {
    AlertKind.COLD -> R.drawable.ic_alert_cold
    AlertKind.HEAT -> R.drawable.ic_alert_heat
    AlertKind.HEAVY_RAIN, AlertKind.WATERLOGGING -> R.drawable.ic_alert_rain
    AlertKind.WIND -> R.drawable.ic_alert_wind
}

/** One brick card per weather event, naming every plant in it, with Done and Not now (D35). */
@Composable
internal fun AlertCard(e: AlertEvent, today: LocalDate, onDone: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current
    val block = StateBlock.Brick
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(block.fill).padding(Spacing.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 3),
    ) {
        Icon(painterResource(e.kind.icon), contentDescription = null, Modifier.padding(top = Spacing.grid / 2).size(Spacing.grid * 7), tint = block.text)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3)) {
            Text(Texts.event(c, e, today), style = Type.label, color = block.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                OnBlockButton(stringResource(R.string.event_done), onDone)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Spacing.buttonHeight)) {
                    Text(stringResource(R.string.event_not_now), style = Type.label, color = block.text)
                }
            }
        }
    }
}

/** A secondary button on a state block: light surface and ink in both themes, like the block itself. */
@Composable
private fun OnBlockButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = Spacing.buttonHeight),
    shape = RoundedCornerShape(Radius.button),
    colors = ButtonDefaults.buttonColors(containerColor = LightColors.surface, contentColor = LightColors.ink),
) { Text(label, style = Type.label) }

/** A "Do now" card: sky when the plant needs water, marigold when the app has a question. */
@Composable
internal fun ActionCard(s: PlantStatus, onOpen: () -> Unit, onWatered: () -> Unit, onAnswer: (Moisture) -> Unit) {
    val c = LocalContext.current
    val action = s.today
    val block = if (action is Action.WaterNow) StateBlock.Sky else StateBlock.Marigold
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(block.fill).clickable(onClick = onOpen).padding(Spacing.cardPaddingLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 3)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.grid / 2)) {
                Text(s.plant.name, style = Type.title, color = block.text)
                val body = when (action) {
                    is Action.WaterNow -> stringResource(R.string.today_water_dose, Texts.roundDose(action.doseMl))
                    is Action.CheckSoil -> stringResource(R.string.today_soil_question)
                    else -> Texts.action(c, action)
                }
                Text(body, style = Type.body, color = block.text)
                for (r in s.reminders) Text(Texts.reminder(c, r), style = Type.secondary, color = block.text)
            }
            Glyph(glyphFor(s.plant.archetypeId), block.text, contentDescription = null, size = Spacing.grid * 16)
        }
        when (action) {
            is Action.WaterNow -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 3)) {
                PotGauge(s.waterLeft.toFloat(), PotGaugeSize.Medium, block = block)
                Text(
                    stringResource(R.string.today_level, Texts.waterLevel(c, s.waterLeft), waterLeftPercent(s.waterLeft.toFloat())),
                    Modifier.weight(1f),
                    style = Type.secondary,
                    color = block.text,
                )
                // Primary on a state block: the block's own ink, the same in both themes like the block itself.
                Button(
                    onClick = onWatered,
                    modifier = Modifier.heightIn(min = Spacing.buttonHeight),
                    shape = RoundedCornerShape(Radius.button),
                    colors = ButtonDefaults.buttonColors(containerColor = block.text, contentColor = LightColors.onInk),
                ) { Text(stringResource(R.string.plant_watered), style = Type.label) }
            }
            is Action.CheckSoil -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                for ((label, moisture) in answers) OnBlockButton(stringResource(label), { onAnswer(moisture) }, Modifier.weight(1f))
            }
            else -> Unit
        }
    }
}

/** An "All fine" tile: always surface, half width. */
@Composable
internal fun RestingTile(s: PlantStatus, today: LocalDate, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val c = LocalContext.current
    val colors = Overgrown.colors
    val line = when {
        s.reminders.isNotEmpty() -> s.reminders.joinToString(" ") { Texts.reminder(c, it) }
        s.today is Action.Hold -> Texts.action(c, s.today)
        else -> s.nextPrompt?.let { stringResource(R.string.tile_fine_check, Texts.day(it.first, today)) } ?: stringResource(R.string.tile_fine)
    }
    Column(
        modifier.clip(RoundedCornerShape(Radius.card)).background(colors.surface).clickable(onClick = onOpen).padding(Spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // A moss chip (DESIGN.md section 9.8), the same in both themes.
            Box(Modifier.size(Spacing.grid * 12).background(StateBlock.Moss.fill, RoundedCornerShape(Radius.glyphChip)), contentAlignment = Alignment.Center) {
                Glyph(glyphFor(s.plant.archetypeId), StateBlock.Moss.text, contentDescription = null, size = Spacing.grid * 8)
            }
            PotGauge(s.waterLeft.toFloat(), PotGaugeSize.Small)
        }
        // No line limit: a long name wraps and the tile grows, so it is never cut.
        Text(s.plant.name, style = Type.cardTitle, color = colors.ink)
        Text(line, style = Type.secondary, color = colors.ink)
    }
}
