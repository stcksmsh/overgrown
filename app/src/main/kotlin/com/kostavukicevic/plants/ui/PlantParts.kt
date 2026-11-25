// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.OutlookDay
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.PotGauge
import com.kostavukicevic.plants.ui.theme.PotGaugeSize
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Type
import com.kostavukicevic.plants.ui.theme.waterLeftPercent
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.cos
import kotlin.math.sin

/** The answers offered on the plant screen: the Today three plus Bone dry (owner decision P1). */
val PLANT_ANSWERS = listOf(Moisture.TOO_WET, Moisture.OK, Moisture.DRY, Moisture.BONE_DRY)

/** Learning shows this many dots; the card's text says "about", since the engine has no fixed count. */
private const val LEARNING_DOTS = 8

private const val STRIP_BAR_DP = 56
private const val STRIP_MARK_DP = 14

/** The words on the answer buttons, the same as Today's Wet / OK / Dry plus Bone dry. */
fun answerLabel(c: Context, m: Moisture): String = when (m) {
    Moisture.TOO_WET -> c.getString(R.string.plant_answer_wet)
    else -> Texts.moisture(c, m)
}

fun historyLine(c: Context, e: EventEntity): String {
    val d = Texts.date(LocalDate.parse(e.date))
    return when (e.type) {
        "WATERED" -> c.getString(if (e.ranOutOfBottom == true) R.string.history_watered_ran_out else R.string.history_watered, d)
        "CHECK_IN" -> c.getString(R.string.plant_history_check_in, d, e.moisture?.let { answerLabel(c, Moisture.valueOf(it)) } ?: "")
        "MOVED" -> c.getString(R.string.history_moved, d)
        "REPOTTED" -> c.getString(R.string.history_repotted, d)
        "FED" -> c.getString(R.string.history_fed, d)
        else -> c.getString(R.string.history_snoozed, d)
    }
}

/** The Monday of [d]'s week. */
fun weekStart(d: LocalDate): LocalDate = d.minus(DatePeriod(days = d.dayOfWeek.isoDayNumber - 1))

/** Groups items, already newest first, by the Monday of their week, keeping that order. */
fun <T> byWeek(items: List<T>, date: (T) -> LocalDate): List<Pair<LocalDate, List<T>>> =
    items.groupBy { weekStart(date(it)) }.toList()

fun weekHeading(c: Context, monday: LocalDate, today: LocalDate): String {
    val thisWeek = weekStart(today)
    return when (monday) {
        thisWeek -> c.getString(R.string.plant_week_this)
        thisWeek.minus(DatePeriod(days = 7)) -> c.getString(R.string.plant_week_last)
        else -> c.getString(R.string.plant_week_of, Texts.date(monday))
    }
}

private fun dayName(d: LocalDate) = DayOfWeekNames.ENGLISH_ABBREVIATED.names[d.dayOfWeek.isoDayNumber - 1]

@Composable
fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Overgrown.colors.surface).padding(Spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Overgrown.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Spacing.buttonHeight),
        shape = RoundedCornerShape(Radius.button),
        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk),
    ) { Text(text, style = Type.label) }
}

/** Surface fill on ground; no outline. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Overgrown.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Spacing.buttonHeight),
        shape = RoundedCornerShape(Radius.button),
        colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.ink),
        contentPadding = PaddingValues(horizontal = Spacing.grid * 2, vertical = Spacing.grid * 2),
    ) { Text(text, style = Type.label) }
}

/** The full moss card at the top: back and edit, name, type and place, glyph, and the 84 dp gauge. */
@Composable
fun NameBlock(plantId: Long, name: String, typeAndPlace: String, glyph: GlyphKind, waterLeft: Float, onBack: () -> Unit, onEdit: () -> Unit) {
    val block = StateBlock.Moss
    val key = "plant:$plantId"
    Column(
        Modifier.fillMaxWidth().sharedPlant("card", key).clip(RoundedCornerShape(Radius.card)).background(block.fill)
            .padding(start = Spacing.grid * 2, end = Spacing.grid * 2, top = Spacing.grid, bottom = Spacing.cardPaddingLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.back), style = Type.label, color = block.text)
            }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.edit), style = Type.label, color = block.text)
            }
        }
        Row(Modifier.padding(horizontal = Spacing.grid * 3), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
                Text(name, style = Type.display, color = block.text, modifier = Modifier.sharedPlant("name", key).semantics { heading() })
                Text(typeAndPlace, style = Type.label, color = block.text)
            }
            Glyph(glyph, block.text, contentDescription = null, Modifier.sharedPlant("glyph", key), size = 72.dp)
        }
        Row(
            Modifier.padding(horizontal = Spacing.grid * 3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardPadding),
        ) {
            PotGauge(waterLeft, PotGaugeSize.Large, block = block)
            // The gauge already says "Water left: N percent" to TalkBack; the numeral would repeat it.
            Column(Modifier.clearAndSetSemantics {}) {
                Text(stringResource(R.string.plant_water_left_percent, waterLeftPercent(waterLeft)), style = Type.title, color = block.text)
                Text(stringResource(R.string.plant_water_left), style = Type.secondary, color = block.text)
            }
        }
    }
}

/** Ten days of predicted water left with frost, rain and heat marks; the next day with something to do is ink and bold. */
@Composable
fun DayStrip(days: List<OutlookDay>, next: LocalDate?) {
    val res = LocalResources.current
    val colors = Overgrown.colors
    val frost = days.filter { it.frost }
    val description = buildString {
        append(res.getString(R.string.plant_next_days)).append(". ")
        for (d in days) {
            val parts = buildList {
                add(res.getString(R.string.plant_strip_day, dayName(d.date), waterLeftPercent(d.waterLeft.toFloat())))
                if (d.frost) add(res.getString(R.string.plant_strip_frost))
                if (d.rain) add(res.getString(R.string.plant_strip_rain))
                if (d.heat) add(res.getString(R.string.plant_strip_heat))
                if (d.date == next) add(res.getString(R.string.plant_strip_next))
            }
            append(parts.joinToString(", ")).append(". ")
        }
    }.trim()
    SurfaceCard {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plant_next_days), style = Type.label, color = colors.ink, modifier = Modifier.weight(1f))
                if (frost.isNotEmpty()) {
                    // Every frost day by name: a range would cover the gap days between frosts ("Fri, Mon", not "Fri–Mon").
                    Text(
                        stringResource(R.string.plant_frost_days, frost.joinToString(", ") { dayName(it.date) }),
                        style = Type.label,
                        color = colors.errorText,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                for (d in days) StripColumn(d, isNext = d.date == next)
            }
        }
    }
}

@Composable
private fun RowScope.StripColumn(d: OutlookDay, isNext: Boolean) {
    val colors = Overgrown.colors
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WeatherMark(d, Modifier.size(STRIP_MARK_DP.dp))
        Box(Modifier.fillMaxWidth().height(STRIP_BAR_DP.dp).clip(RoundedCornerShape(Radius.bar)).background(colors.ground), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(d.waterLeft.toFloat().coerceIn(0f, 1f)).background(if (isNext) colors.ink else colors.gaugeFill))
        }
        Text(
            dayName(d.date).take(1),
            style = Type.secondary.copy(fontWeight = if (isNext) FontWeight.ExtraBold else FontWeight.Normal),
            color = if (isNext) colors.ink else colors.muted,
        )
    }
}

/** One mark per day: frost first, then heat, then rain. Frost is brick (the error-text role, brick in light). */
@Composable
private fun WeatherMark(d: OutlookDay, modifier: Modifier) {
    val colors = Overgrown.colors
    val frostColor = colors.errorText
    val ink = colors.ink
    Canvas(modifier) {
        val s = size.width
        val stroke = s * 0.14f
        val mid = Offset(s / 2, s / 2)
        when {
            d.frost -> for (i in 0 until 3) {
                val a = Math.toRadians(90.0 + i * 60.0)
                val dx = (cos(a) * s * 0.42).toFloat()
                val dy = (sin(a) * s * 0.42).toFloat()
                drawLine(frostColor, mid - Offset(dx, dy), mid + Offset(dx, dy), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            d.heat -> {
                drawCircle(ink, radius = s * 0.22f, center = mid)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(ink, mid + dir * (s * 0.34f), mid + dir * (s * 0.48f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
                }
            }
            d.rain -> {
                val drop = Path().apply {
                    moveTo(s / 2, s * 0.05f)
                    lineTo(s * 0.8f, s * 0.6f)
                    lineTo(s * 0.2f, s * 0.6f)
                    close()
                }
                drawPath(drop, ink)
                drawCircle(ink, radius = s * 0.3f, center = Offset(s / 2, s * 0.66f))
            }
        }
    }
}

/** "Still learning <name>: N of about 8 answers" with eight dots, or one line once the plant is learned. */
@Composable
fun LearningDots(name: String, answers: Int, learned: Boolean) {
    val colors = Overgrown.colors
    SurfaceCard {
        if (learned) {
            Text(stringResource(R.string.plant_knows_well, name), style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.ink)
            return@SurfaceCard
        }
        // "about 8" is a guide, not a limit; past it the app is still learning, and "11 of about 8" reads as a mistake.
        Text(stringResource(R.string.plant_still_learning, name, answers.coerceAtMost(LEARNING_DOTS)), style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.ink)
        // The text above carries the count for TalkBack.
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until LEARNING_DOTS) {
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(Radius.barNarrow))
                        // Ground nearly matches the surface in dark; outline is the token for marks on surface in both themes (D43).
                        .background(if (i < answers) StateBlock.Marigold.fill else colors.outline),
                )
            }
        }
    }
}

/** Wet / OK / Dry / Bone dry, four equal surface buttons. */
@Composable
fun AnswerButtons(onAnswer: (Moisture) -> Unit) {
    val c = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
        for (m in PLANT_ANSWERS) SecondaryButton(answerLabel(c, m), onClick = { onAnswer(m) }, modifier = Modifier.weight(1f))
    }
}

/** A week heading in the history. */
@Composable
fun WeekHeading(text: String) {
    Text(text, style = Type.label, color = Overgrown.colors.ink, modifier = Modifier.padding(top = Spacing.grid * 2).semantics { heading() })
}

/** One logged event with Edit and Delete, both naming the entry for TalkBack. */
@Composable
fun HistoryRow(line: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = Overgrown.colors
    val editLabel = stringResource(R.string.edit_named, line)
    val deleteLabel = stringResource(R.string.delete_named, line)
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = Type.secondary, color = colors.ink, modifier = Modifier.weight(1f))
        TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) {
            Text(stringResource(R.string.edit), style = Type.label, color = colors.ink)
        }
        DeleteButton(onDelete, Modifier.semantics { contentDescription = deleteLabel })
    }
}

/**
 * Changes an entry's date with Day before / Day after, and its answer (check-in) or drained flag
 * (watering). [error] is shown under the fields; the caller refuses a future date.
 */
@Composable
fun EditEntryDialog(event: EventEntity, error: String?, onDismiss: () -> Unit, onSave: (EventEntity) -> Unit) {
    val c = LocalContext.current
    val colors = Overgrown.colors
    var date by remember(event.id) { mutableStateOf(LocalDate.parse(event.date)) }
    var moisture by remember(event.id) { mutableStateOf(event.moisture) }
    var drained by remember(event.id) { mutableStateOf(event.ranOutOfBottom ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.plant_edit_entry), style = Type.cardTitle, color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3)) {
                Text(Texts.date(date), style = Type.cardTitle, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                    OutlineButton(stringResource(R.string.plant_day_before)) { date = date.minus(DatePeriod(days = 1)) }
                    OutlineButton(stringResource(R.string.plant_day_after)) { date = date.plus(DatePeriod(days = 1)) }
                }
                when (event.type) {
                    "CHECK_IN" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2), verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2)) {
                        for (m in PLANT_ANSWERS) {
                            val label = answerLabel(c, m)
                            if (moisture == m.name) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.heightIn(min = Spacing.buttonHeight).semantics { selected = true },
                                    shape = RoundedCornerShape(Radius.button),
                                    colors = ButtonDefaults.buttonColors(containerColor = StateBlock.Moss.fill, contentColor = StateBlock.Moss.text),
                                ) { Text(label, style = Type.label) }
                            } else {
                                OutlineButton(label) { moisture = m.name }
                            }
                        }
                    }
                    "WATERED" -> SwitchRow(stringResource(R.string.plant_entry_drained), drained) { drained = it }
                }
                error?.let { Text(it, style = Type.secondary, color = colors.errorText) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(event.copy(date = date.toString(), moisture = moisture, ranOutOfBottom = if (event.type == "WATERED") drained else event.ranOutOfBottom))
            }) { Text(stringResource(R.string.save), style = Type.label, color = colors.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), style = Type.label, color = colors.ink) } },
    )
}

/** Secondary button on surface: surface fill with an outline. */
@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    val colors = Overgrown.colors
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = Spacing.buttonHeight),
        shape = RoundedCornerShape(Radius.button),
        border = BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface, contentColor = colors.ink),
    ) { Text(text, style = Type.label) }
}

