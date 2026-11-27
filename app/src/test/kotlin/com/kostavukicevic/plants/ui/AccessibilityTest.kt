// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Spacing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessibility pass over every screen and dialog, at 1.0x and 2.0x font scale: everything tappable takes at
 * least 48 x 48 dp, no text is cut off or lies on other text, and screen titles and section headers are headings.
 * Real font metrics (native graphics), and a window tall enough that no screen scrolls, so text cut by a parent is
 * a fault and not a page scrolled part way.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h4000dp-xhdpi")
class AccessibilityTest(private val fontScale: Float) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun show(content: @Composable () -> Unit) = compose.setContent { FontScaled(fontScale) { OvergrownTheme { content() } } }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitFor(hasText(text))

    private fun waitGone(text: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(s(label))).performScrollTo()

    private fun press(id: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(id)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(s(id))[0].performClick()
    }

    private fun cancel() {
        waitForText(s(R.string.cancel))
        compose.onNodeWithText(s(R.string.cancel)).performClick()
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** Touch targets and text on whatever is shown, which [screen] names in a failure, and [headings] as headings. */
    private fun audit(screen: String, vararg headings: String, materialExceptions: Set<String> = emptySet()) {
        val name = "$screen at ${fontScale}x"
        compose.assertTouchTargets(name, materialExceptions)
        compose.assertNoClippedText(name)
        compose.assertHeadings(name, *headings)
    }

    @Test
    fun todayCardsAlertsAndTheEmptyState() {
        useTestDatabase()
        // Padded as Page pads Today, so the cards get the width they get on a phone.
        show {
            Column(Modifier.padding(horizontal = Spacing.screen)) {
                TodayContent(fullToday(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
                RemindersOffLine(onDismiss = {})
                EmptyToday(onAdd = {})
            }
        }
        waitForText("Pothos")
        audit("Today cards")
    }

    @Test
    fun theTabsMenuListsSnackbarAndEditPlace() {
        val g = useTestDatabase()
        plant(g, "Boris", place(g, "Hall"))
        show { App(null) }
        waitForText("Boris")
        audit("Today", s(R.string.today_title))

        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        waitForText(s(R.string.places_title))
        audit("Top menu", s(R.string.today_title))
        compose.onNodeWithText(s(R.string.places_title)).performClick()
        waitFor(hasContentDescription(s(R.string.edit_named, "Hall")))
        audit("Places", s(R.string.places_title))

        compose.onNodeWithContentDescription(s(R.string.edit_named, "Hall")).performClick()
        waitFor(hasText("Hall") and hasSetTextAction())
        audit("Edit place", s(R.string.edit_place_title))
        back()
        waitFor(hasContentDescription(s(R.string.edit_named, "Hall")))
        back()

        compose.onNode(isTab and hasText(s(R.string.plants_title))).performClick()
        waitFor(hasContentDescription(s(R.string.delete_named, "Boris")))
        audit("Plants", s(R.string.plants_title))
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Boris")).performClick()
        waitForText(s(R.string.undo))
        audit("Plants with the Undo snackbar", s(R.string.plants_title))
    }

    @Test
    fun emptyPlantsAndPlaces() {
        useTestDatabase()
        show { App(null) }
        waitForText(s(R.string.today_empty))
        audit("Empty Today", s(R.string.today_title))
        compose.onNode(isTab and hasText(s(R.string.plants_title))).performClick()
        waitForText(s(R.string.plants_empty))
        audit("Empty Plants", s(R.string.plants_title))
        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        compose.onNodeWithText(s(R.string.places_title)).performClick()
        waitForText(s(R.string.places_empty))
        audit("Empty Places", s(R.string.places_title))
    }

    @Test
    fun settingsAndItsDialogs() {
        useTestDatabase()
        show { SettingsScreen(onBack = {}, onRemindersHelp = {}) }
        waitForText(s(R.string.import_json))
        val sections = arrayOf(s(R.string.settings), s(R.string.settings_reminders), s(R.string.settings_data), s(R.string.about_title))
        audit("Settings", *sections)
        tap(s(R.string.import_json))
        waitForText(s(R.string.import_confirm_title))
        audit("Import dialog", s(R.string.import_confirm_title))
        compose.onAllNodesWithText(s(R.string.cancel))[0].performClick()
        waitGone(s(R.string.import_confirm_title))
        tap(s(R.string.about_font_licence))
        waitForText(s(R.string.close))
        audit("Font licence dialog")
        compose.onNodeWithText(s(R.string.close)).performClick()
        waitGone(s(R.string.close))

        val (head, tail) = s(R.string.settings_reminder_time_change, "\u0000").split("\u0000")
        val reminderTime = SemanticsMatcher("the reminder time button") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(head) && it.endsWith(tail) }
        } and hasClickAction()
        waitFor(reminderTime and isEnabled())
        compose.onNode(reminderTime).performScrollTo().performClick()
        waitForText(s(R.string.cancel))
        // Material's TimePicker draws AM and PM as 52 x 38 dp halves of one toggle and has no size to set (D62).
        audit("Reminder time dialog", materialExceptions = setOf("AM", "PM"))
    }

    @Test
    fun remindersHelp() {
        useTestDatabase()
        show { RemindersHelpScreen(onBack = {}) }
        waitForText(s(R.string.notifications_help))
        audit("Reminders help", s(R.string.notifications_help))
    }

    @Test
    fun thePlantScreenAndItsDialogs() {
        val (g, db) = fixedClockGraph()
        runBlocking { g.settings.setWeighing(true) }
        val checkIn = EventEntity(plantId = 0, date = "2026-11-23", type = "CHECK_IN", moisture = "DRY")
        val plantId = runBlocking {
            val placeId = g.garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
            val id = g.garden.savePlant(PlantEntity(name = "Monstera deliciosa", placeId = placeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-01"))
            db.dao().insertEvent(checkIn.copy(plantId = id))
            id
        }
        show { PlantScreen(plantId, onBack = {}, onEdit = {}) }
        waitFor(hasContentDescription(s(R.string.plant_next_days), substring = true))
        val editEntry = s(R.string.edit_named, historyLine(compose.activity, checkIn))
        waitFor(hasContentDescription(editEntry))
        waitForText(s(R.string.calibration_title))
        audit("Plant", "Monstera deliciosa", s(R.string.plant_history), s(R.string.calibration_title))

        compose.onNodeWithContentDescription(editEntry).performScrollTo().performClick()
        waitForText(s(R.string.plant_edit_entry))
        audit("Edit entry dialog", s(R.string.plant_edit_entry))
        compose.assertSelectionsMarked("Edit entry dialog")
        cancel()
        waitGone(s(R.string.plant_edit_entry))

        tap(s(R.string.plant_watered))
        waitForText(s(R.string.no))
        audit("Ran out of the bottom dialog", s(R.string.plant_watered_question))
        compose.onNodeWithText(s(R.string.no)).performClick()
        waitGone(s(R.string.plant_watered_question))
        // The watering is saved off the main thread; its history row moves the buttons below, so wait for it.
        waitFor(hasText(s(R.string.history_watered, ""), substring = true))

        tap(s(R.string.photo_add))
        waitForText(s(R.string.photo_add_title))
        audit("Photo source dialog", s(R.string.photo_add_title))
        cancel()
        waitGone(s(R.string.photo_add_title))

        compose.onNode(hasText(s(R.string.delete)) and hasClickAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .performScrollTo().performClick()
        waitForText(s(R.string.plant_delete_question, "Monstera deliciosa"))
        audit("Delete plant dialog", s(R.string.plant_delete_question, "Monstera deliciosa"))
    }

    @Test
    fun everyWizardStepAndTheDateDialog() {
        useTestDatabase()
        show { AddPlantWizard(null, onDone = {}, careDb = WizardTest.FIXTURE_DB) }
        field(R.string.wizard_name).performTextInput("Test")
        waitForText("Test cactus")
        audit("Wizard step 1 with matches", s(R.string.wizard_plant_title))
        tap("Test cactus")
        audit("Wizard step 1 with a picked plant", s(R.string.wizard_plant_title))
        press(R.string.wizard_next_where)

        tap(s(R.string.place_outdoor))
        tap(s(R.string.wizard_advanced))
        audit("Wizard step 2", s(R.string.wizard_where_title))
        compose.assertSelectionsMarked("Wizard step 2")
        field(R.string.wizard_place_name).performTextInput("Balcony")
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)

        waitFor(hasSetTextAction() and hasText(s(R.string.wizard_pot_width)))
        tap(s(R.string.wizard_advanced))
        audit("Wizard step 3", s(R.string.wizard_pot_title))
        compose.onNode(hasContentDescription(s(R.string.wizard_acquired_button, "X").substringBefore(" X"), substring = true)).performScrollTo().performClick()
        waitForText(s(R.string.wizard_ok))
        audit("Acquired date dialog")
        cancel()
        field(R.string.wizard_pot_width).performTextInput("20")
        field(R.string.wizard_plant_width).performTextInput("30")
        press(R.string.wizard_next_exposure)

        waitForText(s(R.string.wizard_rain))
        audit("Wizard step 4", s(R.string.wizard_exposure_outdoor_title))
    }

    @Test
    fun editPlant() {
        val g = useTestDatabase()
        val id = plant(g, "Boris", place(g, "Hall"))
        show { AddPlantWizard(id, onDone = {}, careDb = WizardTest.FIXTURE_DB) }
        waitFor(hasText("Boris") and hasSetTextAction())
        audit("Edit plant", s(R.string.wizard_plant_title))
    }

    /** A 12-letter, one-word name: wherever a plant name is shown it must keep all 12 on one line, uncut. */
    @Test
    fun aTwelveLetterNameIsNeverCut() {
        val name = "Philodendron"
        val g = useTestDatabase()
        plant(g, name, place(g, "Hall"))
        show { App(null) }
        waitForText(name)
        audit("Today with a 12-letter name", s(R.string.today_title))
        compose.onNode(isTab and hasText(s(R.string.plants_title))).performClick()
        waitFor(hasContentDescription(s(R.string.delete_named, name)))
        audit("Plants with a 12-letter name", s(R.string.plants_title))
        compose.onAllNodesWithText(name)[0].performClick()
        waitFor(hasText(name) and isHeading())
        audit("Plant screen with a 12-letter name", name)
    }

    // ---- The guards themselves -----------------------------------------------------------------

    @Test
    fun theTouchTargetGuardCatchesASmallButton() {
        show { Box(Modifier.size(48.dp, 40.dp).clickable {}) { Text("Small") } }
        val failure = assertThrows(AssertionError::class.java) { compose.assertTouchTargets("Small") }
        assertTrue(failure.message, failure.message!!.contains("1 touch target(s) under 48 dp: \"Small\" 48 x 40 dp"))
    }

    @Test
    fun theTouchTargetGuardIgnoresPaddingOutsideTheClick() {
        show { Box(Modifier.size(48.dp).padding(8.dp).clickable {}) { Text("Padded") } }
        val failure = assertThrows(AssertionError::class.java) { compose.assertTouchTargets("Padded") }
        assertTrue(failure.message, failure.message!!.contains("1 touch target(s) under 48 dp: \"Padded\" 32 x 32 dp"))
    }

    @Test
    fun theTextGuardCatchesCutText() {
        show { Text("A name far too long for its box", Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val failure = assertThrows(AssertionError::class.java) { compose.assertNoClippedText("Cut") }
        assertTrue(failure.message, failure.message!!.contains("is ellipsized"))
    }

    @Test
    fun theTextGuardCatchesAWordBrokenAcrossLines() {
        show { Text("Getting", Modifier.width(40.dp)) }
        val failure = assertThrows(AssertionError::class.java) { compose.assertNoClippedText("Broken") }
        assertTrue(failure.message, failure.message!!.contains("\"Getting\" breaks a word across lines"))
    }

    @Test
    fun theTextGuardCatchesTextClippedByItsParent() {
        show { Box(Modifier.size(200.dp, 10.dp).clipToBounds()) { Text("Tall text", Modifier.wrapContentHeight(unbounded = true)) } }
        val failure = assertThrows(AssertionError::class.java) { compose.assertNoClippedText("Clipped") }
        assertTrue(failure.message, failure.message!!.contains("\"Tall text\" shows"))
    }

    @Test
    fun theTextGuardCatchesOverlappingText() {
        show { Box { Text("One"); Text("Two") } }
        val failure = assertThrows(AssertionError::class.java) { compose.assertNoClippedText("Overlap") }
        assertTrue(failure.message, failure.message!!.contains("\"One\" overlaps \"Two\""))
    }

    @Test
    fun theSelectionGuardCatchesAChoiceShownByColourAlone() {
        show { Box(Modifier.selectable(selected = true, role = Role.RadioButton) {}) { Text("Chosen") } }
        val failure = assertThrows(AssertionError::class.java) { compose.assertSelectionsMarked("Bare") }
        assertTrue(failure.message, failure.message!!.contains("1 choice(s) shown by colour alone: \"Chosen\""))
    }

    @Test
    fun theHeadingGuardCatchesAPlainTitle() {
        show { Text("Title") }
        val failure = assertThrows(AssertionError::class.java) { compose.assertHeadings("Plain", "Title") }
        assertTrue(failure.message, failure.message!!.contains("not a heading: Title"))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun params() = listOf(arrayOf<Any>(1.0f), arrayOf<Any>(2.0f))
    }
}
