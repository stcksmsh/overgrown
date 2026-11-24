// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.caredb.Archetype
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.CommonName
import com.kostavukicevic.plants.caredb.Species
import com.kostavukicevic.plants.caredb.Taxon
import com.kostavukicevic.plants.caredb.Toxicity
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI

/** The add-plant wizard driven through its real screens, saving to an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class WizardTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph
    private var done = false

    @Before
    fun setUp() {
        g = useTestDatabase()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(plantId: Long? = null) = compose.setContent {
        OvergrownTheme { AddPlantWizard(plantId, onDone = { done = true }, careDb = FIXTURE_DB) }
    }

    private fun field(label: Int): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(s(label))).performScrollTo()

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun tap(id: Int) = tap(s(id))

    /** The bottom buttons wait for the places to load before they are enabled. */
    private fun press(id: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(id)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(s(id))[0].performClick()
    }

    private fun assertStep(n: Int) = compose.onNodeWithContentDescription(s(R.string.wizard_progress_description, n, 4)).assertExists()

    private fun saveAndWait() {
        press(R.string.save)
        compose.waitUntil(5_000) { done }
    }

    private fun plants() = runBlocking { g.garden.plants.first() }
    private fun places() = runBlocking { g.garden.places.first() }

    @Test
    fun addingIndoorsWithATypedNameAndANewPlaceSavesEveryAnswer() {
        start()
        field(R.string.wizard_name).performTextInput("Mystery fern")
        compose.onNodeWithText(s(R.string.wizard_no_match)).assertExists()
        tap(R.string.look_fern)
        press(R.string.wizard_next_where)

        assertStep(2)
        tap(R.string.place_indoor)
        // No indoor place yet: the new-place form is there without asking.
        field(R.string.wizard_place_name).performTextInput("Kitchen")
        tap(R.string.wizard_advanced)
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)

        assertStep(3)
        field(R.string.wizard_pot_width).performTextInput("20")
        tap(R.string.shape_tall)
        field(R.string.wizard_plant_width).performTextInput("30")
        press(R.string.wizard_next_exposure)

        assertStep(4)
        tap(R.string.answer_little)
        saveAndWait()

        val p = plants().single()
        val place = places().single()
        assertEquals("Mystery fern", p.name)
        assertNull(p.speciesSlug)
        assertEquals("indoor-moisture-loving", p.archetypeId)
        assertEquals(place.id, p.placeId)
        assertEquals("TALL", p.potShape)
        assertEquals(potVolumeL(20.0, "TALL"), p.potVolumeL, 1e-9)
        assertEquals(30.0, p.potHeightCm!!, 1e-9)
        assertEquals(30.0, p.canopyWidthCm, 1e-9)
        assertEquals(g.garden.today().toString(), p.acquiredOn)
        assertEquals("Kitchen", place.name)
        assertFalse(place.outdoor)
        assertEquals(44.8, place.latitude, 1e-9)
        assertEquals("HIGH", place.lightClass)
    }

    @Test
    fun pickingASearchResultSavesTheSpeciesAndTheExistingPlaceTakesTheRainAndWind() {
        val balcony = runBlocking { g.garden.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5)) }
        plant(g, "Boris", balcony)
        start()
        field(R.string.wizard_name).performTextInput("Test")
        tap("Test cactus")
        compose.onNodeWithText(s(R.string.wizard_no_match)).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Test cactus")).assertExists()
        // The picked result stays the choice while the name is edited.
        field(R.string.wizard_name).performTextReplacement("Spiky")
        compose.onNodeWithText(s(R.string.wizard_no_match)).assertDoesNotExist()
        press(R.string.wizard_next_where)

        tap(R.string.place_outdoor)
        tap("Balcony")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_pot_width).performTextInput("30")
        field(R.string.wizard_plant_width).performTextInput("25")
        press(R.string.wizard_next_exposure)

        // The place is shared with Boris, and the screen says so.
        compose.onNodeWithText(s(R.string.wizard_shared_place, "Balcony", "Boris")).assertExists()
        // Rain is asked first: No, then wind: Yes.
        compose.onAllNodesWithText(s(R.string.answer_no))[0].performScrollTo().performClick()
        compose.onAllNodesWithText(s(R.string.answer_yes))[1].performScrollTo().performClick()
        saveAndWait()

        val p = plants().single { it.name == "Spiky" }
        assertEquals("testus-plantus", p.speciesSlug)
        assertEquals("indoor-succulent", p.archetypeId)
        assertEquals(balcony, p.placeId)
        assertEquals("STANDARD", p.potShape)
        assertEquals(potVolumeL(30.0, "STANDARD"), p.potVolumeL, 1e-9)
        val place = places().single()
        assertEquals("COVERED", place.rainExposure)
        assertEquals("EXPOSED", place.windExposure)
    }

    @Test
    fun notSureStoresPartlyAndNormal() {
        val balcony = runBlocking { g.garden.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5, windExposure = "EXPOSED")) }
        start()
        field(R.string.wizard_name).performTextInput("Zzplant")
        press(R.string.wizard_next_where)
        tap(R.string.place_outdoor)
        tap("Balcony")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_pot_width).performTextInput("20")
        field(R.string.wizard_plant_width).performTextInput("20")
        press(R.string.wizard_next_exposure)
        compose.onAllNodesWithText(s(R.string.answer_unsure))[0].performScrollTo().performClick()
        compose.onAllNodesWithText(s(R.string.answer_unsure))[1].performScrollTo().performClick()
        saveAndWait()

        val p = plants().single()
        assertEquals("Zzplant", p.name)
        assertNull(p.archetypeId)
        val place = places().single { it.id == balcony }
        assertEquals("PARTIAL", place.rainExposure)
        assertEquals("NORMAL", place.windExposure)
    }

    @Test
    fun volumeIsComputedForEachShape() {
        // A frustum with base 0.75 of the top: V = pi h (D^2 + Dd + d^2) / 12, in cm^3.
        fun frustumL(d: Double, h: Double) = PI * h * (d * d + d * 0.75 * d + 0.75 * d * 0.75 * d) / 12 / 1000
        assertEquals(frustumL(20.0, 10.0), potVolumeL(20.0, "SHALLOW"), 1e-9)
        assertEquals(frustumL(20.0, 18.0), potVolumeL(20.0, "STANDARD"), 1e-9)
        assertEquals(frustumL(20.0, 30.0), potVolumeL(20.0, "TALL"), 1e-9)
        for (shape in listOf("SHALLOW", "STANDARD", "TALL")) assertEquals(20.0, potWidthCm(potVolumeL(20.0, shape), shape), 1e-9)
    }

    @Test
    fun litresUnderAdvancedOverrideTheComputedVolume() {
        val room = runBlocking { g.garden.savePlace(PlaceEntity(name = "Room", outdoor = false, latitude = 44.8, longitude = 20.5, lightClass = "LOW")) }
        start()
        field(R.string.wizard_name).performTextInput("Zzplant")
        press(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        tap("Room")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_pot_width).performTextInput("20")
        tap(R.string.shape_shallow)
        field(R.string.wizard_plant_width).performTextInput("20")
        // Litres are only under Advanced.
        compose.onNode(hasSetTextAction() and hasText(s(R.string.field_pot_volume))).assertDoesNotExist()
        tap(R.string.wizard_advanced)
        compose.onNodeWithText(s(R.string.wizard_volume_computed, oneDecimal(potVolumeL(20.0, "SHALLOW")))).assertExists()
        field(R.string.field_pot_volume).performTextInput("3.5")
        press(R.string.wizard_next_exposure)
        // The place's light is already known, so it is the answer.
        compose.onNodeWithText(s(R.string.light_low)).assertIsSelected()
        saveAndWait()

        val p = plants().single()
        assertEquals(room, p.placeId)
        assertEquals("SHALLOW", p.potShape)
        assertEquals(3.5, p.potVolumeL, 1e-9)
        assertEquals(10.0, p.potHeightCm!!, 1e-9)
    }

    @Test
    fun editingOpensTheStepsFilledInAndSavesFromAnyStep() {
        val room = runBlocking { g.garden.savePlace(PlaceEntity(name = "Room", outdoor = false, latitude = 44.8, longitude = 20.5, lightClass = "MEDIUM")) }
        val id = runBlocking {
            g.garden.savePlant(
                PlantEntity(
                    name = "Fixture plant", placeId = room, archetypeId = "indoor-tropical-foliage", canopyWidthCm = 25.0,
                    potVolumeL = potVolumeL(20.0, "STANDARD"), potHeightCm = 18.0, potMaterial = "UNGLAZED_TERRACOTTA",
                    rootBound = true, acquiredOn = "2026-01-01",
                ),
            )
        }
        start(id)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText("Fixture plant")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.look_leafy)).assertIsSelected()
        field(R.string.wizard_name).performTextReplacement("Renamed")
        press(R.string.wizard_next_where)

        compose.onNodeWithText(s(R.string.place_indoor)).assertIsSelected()
        compose.onNodeWithText("Room").assertIsSelected()
        press(R.string.wizard_next_pot)

        compose.onNode(hasSetTextAction() and hasText(s(R.string.wizard_pot_width))).assert(hasText("20"))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.wizard_plant_width))).assert(hasText("25"))
        compose.onNodeWithText(s(R.string.shape_standard)).assertIsSelected()
        // A volume that matches the width and shape is not shown as an override.
        compose.onNode(hasSetTextAction() and hasText(s(R.string.field_pot_volume))).assertDoesNotExist()
        field(R.string.wizard_pot_width).performTextReplacement("24")
        // Save is on every step while editing.
        compose.onAllNodesWithText(s(R.string.save))[0].performClick()
        compose.waitUntil(5_000) { done }

        val p = plants().single()
        assertEquals("Renamed", p.name)
        assertEquals("indoor-tropical-foliage", p.archetypeId)
        assertEquals(potVolumeL(24.0, "STANDARD"), p.potVolumeL, 1e-9)
        assertEquals("UNGLAZED_TERRACOTTA", p.potMaterial)
        assertEquals("2026-01-01", p.acquiredOn)
        assertTrue(p.rootBound)
        assertEquals("MEDIUM", places().single().lightClass)
    }

    @Test
    fun aWidthOfZeroOrAbove300IsRefused() {
        start()
        field(R.string.wizard_name).performTextInput("Zzplant")
        press(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        field(R.string.wizard_place_name).performTextInput("Kitchen")
        tap(R.string.wizard_advanced)
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_plant_width).performTextInput("20")
        for (bad in listOf("301", "0", "")) {
            field(R.string.wizard_pot_width).performTextReplacement(bad)
            press(R.string.wizard_next_exposure)
            assertStep(3)
            compose.onAllNodesWithText(s(R.string.wizard_width_invalid)).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        }
        field(R.string.wizard_pot_width).performTextReplacement("300")
        press(R.string.wizard_next_exposure)
        assertStep(4)
    }

    @Test
    fun rotationKeepsTheStepAndTheAnswers() {
        val tester = StateRestorationTester(compose)
        tester.setContent { OvergrownTheme { AddPlantWizard(null, onDone = { done = true }, careDb = FIXTURE_DB) } }
        field(R.string.wizard_name).performTextInput("Test")
        tap("Test cactus")
        press(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        field(R.string.wizard_place_name).performTextInput("Kitchen")

        tester.emulateSavedInstanceStateRestore()

        assertStep(2)
        compose.onNodeWithText(s(R.string.place_indoor)).assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText(s(R.string.wizard_place_name))).assert(hasText("Kitchen"))
        compose.onNodeWithText(s(R.string.back)).performClick()
        assertStep(1)
        compose.onNodeWithContentDescription(s(R.string.wizard_change_named, "Test cactus")).assertExists()
    }

    @Test
    fun systemBackGoesToTheStepBeforeAndBackOnTheFirstStepLeavesWithoutSaving() {
        start()
        field(R.string.wizard_name).performTextInput("Zzplant")
        press(R.string.wizard_next_where)
        assertStep(2)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertStep(1)
        assertFalse(done)
        compose.onNode(hasContentDescription(s(R.string.wizard_progress_description, 1, 4))).assertExists()
        compose.onNodeWithText(s(R.string.back)).performClick()
        compose.waitForIdle()
        assertTrue(done)
        assertTrue(plants().isEmpty())
    }

    private fun stored(plant: PlantEntity): Long = runBlocking {
        val room = g.garden.savePlace(PlaceEntity(name = "Room", outdoor = false, latitude = 44.8, longitude = 20.5, lightClass = "MEDIUM"))
        g.garden.savePlant(plant.copy(placeId = room))
    }

    private fun startEditing(id: Long, name: String) {
        start(id)
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(name)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Made with the single form before the wizard: no height, and litres typed in. */
    private val oldFormPlant = PlantEntity(
        name = "Old form plant", placeId = 0, archetypeId = "indoor-tropical-foliage", canopyWidthCm = 12.35,
        potVolumeL = 0.25, potHeightCm = null, potShape = "STANDARD", acquiredOn = "2026-01-01",
    )

    @Test
    fun savingAnUntouchedOldFormPlantLeavesItsPotAndWidthAsStored() {
        val id = stored(oldFormPlant)
        val before = runBlocking { g.garden.plant(id) }
        startEditing(id, "Old form plant")
        saveAndWait()
        assertEquals(before, runBlocking { g.garden.plant(id) })
    }

    @Test
    fun savingAnUntouchedWizardPlantWithATwoDecimalWidthLeavesItAsStored() {
        val id = stored(
            PlantEntity(
                name = "Wizard plant", placeId = 0, archetypeId = "indoor-tropical-foliage", canopyWidthCm = 25.0,
                potVolumeL = potVolumeL(12.34, "STANDARD"), potHeightCm = 12.34 * STANDARD_HEIGHT_TO_WIDTH, acquiredOn = "2026-01-01",
            ),
        )
        val before = runBlocking { g.garden.plant(id) }
        startEditing(id, "Wizard plant")
        press(R.string.wizard_next_where)
        press(R.string.wizard_next_pot)
        compose.onNode(hasSetTextAction() and hasText(s(R.string.wizard_pot_width))).assert(hasText("12.34"))
        saveAndWait()
        assertEquals(before, runBlocking { g.garden.plant(id) })
    }

    @Test
    fun changingOnlyTheShapeRecomputesThePot() {
        val id = stored(oldFormPlant)
        startEditing(id, "Old form plant")
        press(R.string.wizard_next_where)
        press(R.string.wizard_next_pot)
        tap(R.string.shape_tall)
        saveAndWait()

        val p = plants().single()
        val w = plain(potWidthCm(0.25, "STANDARD")).toDouble()
        assertEquals("TALL", p.potShape)
        assertEquals(potVolumeL(w, "TALL"), p.potVolumeL, 1e-9)
        assertEquals(w * TALL_HEIGHT_TO_WIDTH, p.potHeightCm!!, 1e-9)
        assertEquals(12.35, p.canopyWidthCm, 0.0)
    }

    @Test
    fun savingAgainAfterRotationUpdatesInsteadOfAddingAnotherPlantAndPlace() {
        val tester = StateRestorationTester(compose)
        tester.setContent { OvergrownTheme { AddPlantWizard(null, onDone = { done = true }, careDb = FIXTURE_DB) } }
        field(R.string.wizard_name).performTextInput("Zzplant")
        press(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        field(R.string.wizard_place_name).performTextInput("Kitchen")
        tap(R.string.wizard_advanced)
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_pot_width).performTextInput("20")
        field(R.string.wizard_plant_width).performTextInput("30")
        press(R.string.wizard_next_exposure)
        tap(R.string.answer_little)
        saveAndWait()

        tester.emulateSavedInstanceStateRestore()
        done = false
        assertStep(4)
        saveAndWait()

        assertEquals(1, plants().size)
        assertEquals(1, places().size)
        assertEquals(places().single().id, plants().single().placeId)
    }

    @Test
    fun aPlantThatIsGoneSaysSoAndOnlyLeaves() {
        start(9_999)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(s(R.string.wizard_plant_missing)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.save)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.back)).performClick()
        compose.waitForIdle()
        assertTrue(done)
        assertTrue(plants().isEmpty())
    }

    @Test
    fun aSpeciesMissingFromTheDatabaseShowsTheStoredNameWithoutSearching() {
        val id = stored(oldFormPlant.copy(name = "Testus gone", speciesSlug = "testus-gone", archetypeId = "indoor-succulent"))
        startEditing(id, "Testus gone")
        compose.onNodeWithContentDescription(s(R.string.wizard_change_named, "Testus gone")).assertExists()
        compose.onNodeWithText(s(R.string.wizard_no_match)).assertDoesNotExist()
        saveAndWait()
        assertEquals("testus-gone", plants().single().speciesSlug)
    }

    companion object {
        /** Clearly made-up entries: tests never use real care data. */
        internal val FIXTURE_DB = CareDb(
            schemaVersion = CareDb.SUPPORTED_SCHEMA,
            dataVersion = 1,
            sourceCommit = "test",
            archetypes = mapOf("indoor-succulent" to Archetype("Fixture spiny group")),
            species = mapOf(
                "testus-plantus" to Species(
                    archetype = "indoor-succulent",
                    taxon = Taxon(acceptedName = "Testus plantus", rank = "species"),
                    commonNames = mapOf("en" to listOf(CommonName("Test cactus", "test"))),
                    toxicity = Toxicity(),
                ),
            ),
        )
    }
}
