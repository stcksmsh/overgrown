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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.Garden
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
import com.kostavukicevic.plants.ui.theme.Tints
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
 * alerts, "Do now" cards and "All fine" tiles, then [weather], which the caller leaves empty where no weather applies.
 */
@Composable
internal fun TodayContent(
    list: List<PlantStatus>,
    today: LocalDate,
    onPlant: (Long) -> Unit,
    onAdd: () -> Unit,
    onWatered: (PlantStatus) -> Unit,
    onAnswer: (PlantStatus, Moisture) -> Unit,
    weather: @Composable () -> Unit,
) {
    if (list.isEmpty()) {
        EmptyToday(onAdd)
        return
    }
    val c = LocalContext.current
    val groups = TodayGroups(list)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        for (s in groups.alerts) AlertBlock(s, onOpen = { onPlant(s.plant.id) })
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

/** One brick block per plant with its weather alerts, until alerts are grouped by event. */
@Composable
internal fun AlertBlock(s: PlantStatus, onOpen: () -> Unit) {
    val c = LocalContext.current
    val block = StateBlock.Brick
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(block.fill).clickable(onClick = onOpen).padding(Spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        Text(s.plant.name, style = Type.label, color = block.text)
        for (a in s.alerts) Text(Texts.alert(c, a), style = Type.secondary, color = block.text)
    }
}

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
                for ((label, moisture) in answers) Button(
                    onClick = { onAnswer(moisture) },
                    modifier = Modifier.weight(1f).heightIn(min = Spacing.buttonHeight),
                    shape = RoundedCornerShape(Radius.button),
                    colors = ButtonDefaults.buttonColors(containerColor = Overgrown.colors.surface, contentColor = Overgrown.colors.ink),
                ) { Text(stringResource(label), style = Type.label) }
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
            Box(Modifier.size(Spacing.grid * 12).background(Tints.moss, RoundedCornerShape(Radius.glyphChip)), contentAlignment = Alignment.Center) {
                Glyph(glyphFor(s.plant.archetypeId), StateBlock.Moss.fill, contentDescription = null, size = Spacing.grid * 8)
            }
            PotGauge(s.waterLeft.toFloat(), PotGaugeSize.Small)
        }
        // No line limit: a long name wraps and the tile grows, so it is never cut.
        Text(s.plant.name, style = Type.cardTitle, color = colors.ink)
        Text(line, style = Type.secondary, color = colors.ink)
    }
}
