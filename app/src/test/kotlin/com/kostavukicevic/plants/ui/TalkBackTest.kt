// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.OutlookDay
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.PotGauge
import com.kostavukicevic.plants.ui.theme.PotGaugeSize
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DESIGN section 8 under TalkBack: everything that can be tapped says what it is, choices and switches say their
 * role and state, and the pot gauge and the day strip say their data in words.
 */
@RunWith(RobolectricTestRunner::class)
class TalkBackTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitFor(hasText(text))

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun field(label: Int): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(s(label))).performScrollTo()

    /** The bottom buttons wait for the places to load before they are enabled. */
    private fun press(id: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(id)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(s(id))[0].performClick()
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** The words TalkBack has for a node: its text, typed text and description, merged as TalkBack reads them. */
    private fun words(n: SemanticsNode): String = buildList {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        n.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
    }.joinToString(" ").trim()

    /** Fails, naming [screen], if any node with a click action, in any window, has no words. */
    private fun assertEveryClickableNamed(screen: String) {
        compose.waitForIdle()
        val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("$screen: nothing to tap was found", clickables.isNotEmpty())
        val silent = clickables.filter { words(it).isEmpty() }
        assertTrue(
            "$screen: ${silent.size} tappable node(s) without a name: " +
                silent.joinToString { "${it.config.getOrNull(SemanticsProperties.Role)} at ${it.boundsInRoot}" },
            silent.isEmpty(),
        )
    }

    private fun node(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode()
    private fun role(matcher: SemanticsMatcher) = node(matcher).config.getOrNull(SemanticsProperties.Role)
    private fun selected(matcher: SemanticsMatcher) = node(matcher).config[SemanticsProperties.Selected]
    private fun toggled(matcher: SemanticsMatcher) = node(matcher).config[SemanticsProperties.ToggleableState]

    /** A node that says whether it is chosen, so TalkBack can say "selected" or "not selected". */
    private fun choice(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    // ---- Every tappable node has words ---------------------------------------------------------

    @Test
    fun todayCardsAlertsAndTheEmptyStateNameEveryButton() {
        useTestDatabase()
        compose.setContent {
            OvergrownTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // An alert, a water card, a soil card and resting tiles, with the weather lines under them.
                    TodayContent(fullToday(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
                    EmptyToday(onAdd = {})
                }
            }
        }
        waitForText("Pothos")
        assertEveryClickableNamed("Today cards")
    }

    @Test
    fun theAppsTabsMenuListsSnackbarEditPlaceAndSettingsNameEveryButton() {
        val g = useTestDatabase()
        plant(g, "Boris", place(g, "Hall"))
        compose.setContent { OvergrownTheme { App(null) } }
        waitForText("Boris")
        assertEveryClickableNamed("Today")

        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        waitForText(s(R.string.places_title))
        assertEveryClickableNamed("Top menu")
        compose.onNodeWithText(s(R.string.places_title)).performClick()
        waitFor(hasContentDescription(s(R.string.edit_named, "Hall")))
        assertEveryClickableNamed("Places")

        compose.onNodeWithContentDescription(s(R.string.edit_named, "Hall")).performClick()
        waitFor(hasText("Hall") and hasSetTextAction())
        assertEveryClickableNamed("Edit place")
        back()
        waitFor(hasContentDescription(s(R.string.edit_named, "Hall")))
        back()

        compose.onNode(isTab and hasText(s(R.string.plants_title))).performClick()
        waitFor(hasContentDescription(s(R.string.delete_named, "Boris")))
        assertEveryClickableNamed("Plants")
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Boris")).performClick()
        waitForText(s(R.string.undo))
        assertEveryClickableNamed("Plants with the Undo snackbar")
        compose.onNodeWithText(s(R.string.undo)).performClick()

        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        compose.onNodeWithText(s(R.string.settings)).performClick()
        waitForText(s(R.string.settings_theme))
        assertEveryClickableNamed("Settings")
    }

    @Test
    fun settingsDialogsNameEveryButton() {
        useTestDatabase()
        compose.setContent { OvergrownTheme { SettingsScreen(onBack = {}) } }
        waitForText(s(R.string.import_json))
        tap(s(R.string.import_json))
        assertEveryClickableNamed("Import dialog")
        compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
        tap(s(R.string.about_font_licence))
        assertEveryClickableNamed("Font licence dialog")
        compose.onNodeWithText(s(R.string.close)).performClick()

        // "Reminder time 08:00. Change", whatever the stored time is.
        val (head, tail) = s(R.string.settings_reminder_time_change, "\u0000").split("\u0000")
        val reminderTime = SemanticsMatcher("the reminder time button") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(head) && it.endsWith(tail) }
        } and hasClickAction()
        waitFor(reminderTime and isEnabled())
        compose.onNode(reminderTime).performScrollTo().performClick()
        assertEveryClickableNamed("Reminder time dialog")
    }

    @Test
    fun thePlantScreenAndItsDialogsNameEveryButton() {
        val (g, db) = fixedClockGraph()
        val checkIn = EventEntity(plantId = 0, date = "2026-11-23", type = "CHECK_IN", moisture = "DRY")
        val plantId = runBlocking {
            val placeId = g.garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
            val id = g.garden.savePlant(PlantEntity(name = "Fern", placeId = placeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-01"))
            db.dao().insertEvent(checkIn.copy(plantId = id))
            id
        }
        compose.setContent { OvergrownTheme { PlantScreen(plantId, onBack = {}, onEdit = {}) } }
        waitFor(hasContentDescription(s(R.string.plant_next_days), substring = true))
        val editEntry = s(R.string.edit_named, historyLine(compose.activity, checkIn))
        waitFor(hasContentDescription(editEntry))
        assertEveryClickableNamed("Plant")

        compose.onNodeWithContentDescription(editEntry).performScrollTo().performClick()
        waitForText(s(R.string.plant_edit_entry))
        assertEveryClickableNamed("Edit entry dialog")
        compose.onNodeWithText(s(R.string.cancel)).performClick()

        tap(s(R.string.plant_watered))
        assertEveryClickableNamed("Ran out of the bottom dialog")
        compose.onNodeWithText(s(R.string.no)).performClick()

        tap(s(R.string.photo_add))
        assertEveryClickableNamed("Photo source dialog")
        compose.onNodeWithText(s(R.string.cancel)).performClick()

        // The plant's own Delete, not a history row's, which is named after its entry.
        compose.onNode(hasText(s(R.string.delete)) and hasClickAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .performScrollTo().performClick()
        assertEveryClickableNamed("Delete plant dialog")
    }

    @Test
    fun everyWizardStepNamesEveryButton() {
        useTestDatabase()
        compose.setContent { OvergrownTheme { AddPlantWizard(null, onDone = {}, careDb = WizardTest.FIXTURE_DB) } }
        field(R.string.wizard_name).performTextInput("Test")
        waitForText("Test cactus")
        assertEveryClickableNamed("Wizard step 1 with matches")
        tap("Test cactus")
        assertEveryClickableNamed("Wizard step 1 with a picked plant")
        press(R.string.wizard_next_where)

        tap(s(R.string.place_outdoor))
        tap(s(R.string.wizard_advanced))
        assertEveryClickableNamed("Wizard step 2")
        field(R.string.wizard_place_name).performTextInput("Balcony")
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)

        waitFor(hasSetTextAction() and hasText(s(R.string.wizard_pot_width)))
        tap(s(R.string.wizard_advanced))
        assertEveryClickableNamed("Wizard step 3")
        compose.onNode(hasContentDescription(s(R.string.wizard_acquired_button, "X").substringBefore(" X"), substring = true)).performScrollTo().performClick()
        waitForText(s(R.string.wizard_ok))
        assertEveryClickableNamed("Acquired date dialog")
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        field(R.string.wizard_pot_width).performTextInput("20")
        field(R.string.wizard_plant_width).performTextInput("30")
        press(R.string.wizard_next_exposure)

        waitForText(s(R.string.wizard_rain))
        assertEveryClickableNamed("Wizard step 4")
    }

    /** The guard itself: an icon button with no description is caught. */
    @Test
    fun theGuardCatchesAButtonWithoutWords() {
        compose.setContent { OvergrownTheme { IconButton(onClick = {}) { Box(Modifier.size(24.dp)) } } }
        val failure = assertThrows(AssertionError::class.java) { assertEveryClickableNamed("Unnamed icon") }
        assertTrue(failure.message, failure.message!!.contains("1 tappable node(s) without a name"))
    }

    // ---- Roles and states ----------------------------------------------------------------------

    @Test
    fun tabsSayTheyAreTabsAndWhichIsSelected() {
        val g = useTestDatabase()
        plant(g, "Boris", place(g, "Hall"))
        compose.setContent { OvergrownTheme { App(null) } }
        waitForText("Boris")
        val today = isTab and hasText(s(R.string.today_title))
        val plants = isTab and hasText(s(R.string.plants_title))
        assertEquals(listOf(true, false), listOf(selected(today), selected(plants)))
        compose.onNode(plants).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), listOf(selected(today), selected(plants)))
    }

    @Test
    fun wizardTilesChipsAndSwitchesSayTheirRoleAndState() {
        useTestDatabase()
        compose.setContent { OvergrownTheme { AddPlantWizard(null, onDone = {}, careDb = WizardTest.FIXTURE_DB) } }
        field(R.string.wizard_name).performTextInput("Zzplant")

        // Look tiles: one choice of several, and the chosen one says so.
        val fern = choice(s(R.string.look_fern))
        assertEquals(Role.RadioButton, role(fern))
        assertFalse(selected(fern))
        tap(s(R.string.look_fern))
        assertTrue(selected(fern))
        press(R.string.wizard_next_where)

        val outdoor = choice(s(R.string.place_outdoor))
        val indoor = choice(s(R.string.place_indoor))
        assertEquals(Role.RadioButton, role(outdoor))
        tap(s(R.string.place_outdoor))
        assertTrue(selected(outdoor))
        assertFalse(selected(indoor))

        // The Advanced row opens and closes like a switch.
        val advanced = hasText(s(R.string.wizard_advanced)) and hasClickAction()
        assertEquals(Role.Switch, role(advanced))
        assertEquals(ToggleableState.Off, toggled(advanced))
        tap(s(R.string.wizard_advanced))
        assertEquals(ToggleableState.On, toggled(advanced))
        field(R.string.wizard_place_name).performTextInput("Balcony")
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)

        // Pot material chips: one choice of three.
        waitForText(s(R.string.material_plastic))
        val plastic = choice(s(R.string.material_plastic))
        val terracotta = choice(s(R.string.material_terracotta))
        assertEquals(Role.RadioButton, role(plastic))
        assertTrue(selected(plastic))
        assertFalse(selected(terracotta))
        tap(s(R.string.material_terracotta))
        assertTrue(selected(terracotta))
        assertFalse(selected(plastic))

        // Switches: named, with their state.
        val drainage = hasContentDescription(s(R.string.field_drainage))
        assertEquals(Role.Switch, role(drainage))
        val before = toggled(drainage)
        compose.onNode(drainage).performScrollTo().performClick()
        assertNotEquals(before, toggled(drainage))
    }

    @Test
    fun theThemeChoiceSaysItIsOneOfThreeAndWhichIsChosen() {
        useTestDatabase()
        compose.setContent { OvergrownTheme { SettingsScreen(onBack = {}) } }
        waitForText(s(R.string.settings_theme_dark))
        val options = listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark).map { choice(s(it)) }
        for (o in options) assertEquals(Role.RadioButton, role(o))
        assertEquals(1, options.count { selected(it) })
        tap(s(R.string.settings_theme_dark))
        compose.waitUntil(5_000) { selected(options[2]) }
        assertEquals(listOf(false, false, true), options.map { selected(it) })
    }

    @Test
    fun theEntryDialogSaysWhichAnswerIsChosen() {
        val event = EventEntity(id = 1, plantId = 1, date = "2026-11-23", type = "CHECK_IN", moisture = "DRY")
        compose.setContent { OvergrownTheme { EditEntryDialog(event, error = null, onDismiss = {}, onSave = {}) } }
        val dry = choice(answerLabel(compose.activity, Moisture.DRY))
        val ok = choice(answerLabel(compose.activity, Moisture.OK))
        assertEquals(Role.RadioButton, role(dry))
        assertEquals(Role.RadioButton, role(ok))
        assertTrue(selected(dry))
        assertFalse(selected(ok))
        compose.onNode(ok).performClick()
        assertTrue(selected(ok))
        assertFalse(selected(dry))
    }

    // ---- The gauge and the strip say their data ------------------------------------------------

    @Test
    fun thePotGaugeSaysHowMuchWaterIsLeft() {
        var left by mutableStateOf(0.62f)
        compose.setContent { OvergrownTheme { PotGauge(left, PotGaugeSize.Medium) } }
        compose.onNodeWithContentDescription(s(R.string.pot_gauge_description, 62)).assertExists()
        left = 0.1f
        compose.onNodeWithContentDescription(s(R.string.pot_gauge_description, 10)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.pot_gauge_description, 62)).assertDoesNotExist()
    }

    @Test
    fun theDayStripSaysEachDaysWaterAndWeather() {
        fun days(left: Double, frostOn: Int?) = (0 until 10).map { i ->
            OutlookDay(TODAY.plus(DatePeriod(days = i)), left, frost = i == frostOn, rain = false, heat = false)
        }
        var shown by mutableStateOf(days(0.5, frostOn = null))
        compose.setContent { OvergrownTheme { DayStrip(shown, next = null) } }
        val strip = hasContentDescription(s(R.string.plant_next_days), substring = true)
        val first = node(strip).config[SemanticsProperties.ContentDescription].single()
        assertTrue(first, first.contains(s(R.string.plant_strip_day, "Tue", 50)))
        assertFalse(first, first.contains(s(R.string.plant_strip_frost)))

        shown = days(0.2, frostOn = 1)
        compose.waitForIdle()
        val second = node(strip).config[SemanticsProperties.ContentDescription].single()
        assertTrue(second, second.contains(s(R.string.plant_strip_day, "Tue", 20)))
        assertTrue(second, second.contains("${s(R.string.plant_strip_day, "Wed", 20)}, ${s(R.string.plant_strip_frost)}"))
    }
}
