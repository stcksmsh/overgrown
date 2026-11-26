// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.caredb.Archetype
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.Species
import com.kostavukicevic.plants.caredb.Taxon
import com.kostavukicevic.plants.caredb.Toxicity
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Toxicity on the plant screen for every value the data can hold, its disclaimer, and no "safe" anywhere near it. */
@RunWith(RobolectricTestRunner::class)
class ToxicityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val graph: Pair<Graph, *> by lazy { fixedClockGraph(careDb = FIXTURE) }
    private val g get() = graph.first

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plant(speciesSlug: String? = null, archetypeId: String? = null): Long = runBlocking {
        val hall = g.garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
        g.garden.savePlant(
            PlantEntity(name = "Test", placeId = hall, speciesSlug = speciesSlug, archetypeId = archetypeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-01"),
        )
    }

    private fun start(plantId: Long) {
        compose.setContent { OvergrownTheme { PlantScreen(plantId, onBack = {}, onEdit = {}, careDb = FIXTURE) } }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(s(R.string.plant_report_wrong_data))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** The title, the four lines and the disclaimer, each shown and in reading order, which is TalkBack's order too. */
    private fun assertShown(cats: Int, dogs: Int, horses: Int, humans: Int) {
        val lines = listOf(
            s(R.string.toxicity_title),
            s(cats, s(R.string.toxicity_cats)),
            s(dogs, s(R.string.toxicity_dogs)),
            s(horses, s(R.string.toxicity_horses)),
            s(humans, s(R.string.toxicity_humans)),
            s(R.string.toxicity_disclaimer),
        )
        val tops = lines.map { top(it) }
        assertTrue("not in reading order: $lines at $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun unknownIsShownAsUnknownWithTheDisclaimer() {
        start(plant(speciesSlug = "unknownus"))
        val u = R.string.toxicity_unknown
        assertShown(u, u, u, u)
    }

    @Test
    fun noKnownToxicityIsShownAsSuchWithTheDisclaimer() {
        start(plant(speciesSlug = "nonus"))
        val n = R.string.toxicity_no_known
        assertShown(n, n, n, n)
    }

    @Test
    fun toxicLevelsAreShownAsCautionsWithTheDisclaimer() {
        start(plant(speciesSlug = "toxicus"))
        assertShown(R.string.toxicity_toxic, R.string.toxicity_mild_irritant, R.string.toxicity_no_known, R.string.toxicity_unknown)
    }

    @Test
    fun unknownAndNoKnownToxicityReadDifferently() {
        assertTrue(s(R.string.toxicity_unknown, "x") != s(R.string.toxicity_no_known, "x"))
    }

    /** BRIEF section 9's four points, and "unknown" claims nothing about why it is unknown. */
    @Test
    fun theWordingSaysWhatTheBriefRequiresAndNoMore() {
        val d = s(R.string.toxicity_disclaimer)
        for (part in listOf("not veterinary or medical advice", "incomplete", "The absence of a warning does not mean a plant is harmless", "contact a vet or a poison centre")) {
            assertTrue("disclaimer lacks \"$part\": $d", d.contains(part))
        }
        assertEquals("Cats: unknown", s(R.string.toxicity_unknown, s(R.string.toxicity_cats)))
    }

    @Test
    fun aPlantKnownOnlyByItsProfileShowsUnknown() {
        start(plant(archetypeId = "fixture-group"))
        val u = R.string.toxicity_unknown
        assertShown(u, u, u, u)
    }

    @Test
    fun aPlantWithNoCareDataShowsNoToxicityAndNoDisclaimer() {
        val id = plant()
        compose.setContent { OvergrownTheme { PlantScreen(id, onBack = {}, onEdit = {}, careDb = FIXTURE) } }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(s(R.string.plant_care_source_generic))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.toxicity_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.toxicity_disclaimer)).assertDoesNotExist()
    }

    /** Every string in strings_toxicity.xml, and any other that speaks of toxicity or poison, avoids "safe". */
    @Test
    fun noToxicityStringSaysSafe() {
        val files = File("src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty()
        assertTrue("strings_toxicity.xml not found from ${File(".").absolutePath}", files.any { it.name == "strings_toxicity.xml" })
        val safe = Regex("""\bsafe""", RegexOption.IGNORE_CASE)
        val context = Regex("toxic|poison", RegexOption.IGNORE_CASE)
        val offending = mutableListOf<String>()
        for (f in files) {
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as Element
                val name = e.getAttribute("name")
                val text = e.textContent
                val toxicity = f.name == "strings_toxicity.xml" || context.containsMatchIn(name) || context.containsMatchIn(text)
                if (toxicity && safe.containsMatchIn(text)) offending += "${f.name}/$name"
            }
        }
        assertTrue("toxicity strings saying safe: $offending", offending.isEmpty())
    }

    companion object {
        private fun species(name: String, toxicity: Toxicity) =
            Species(archetype = "fixture-group", taxon = Taxon(acceptedName = name, rank = "species"), toxicity = toxicity)

        /** One species per toxicity case; the toxic one holds every level, one per category. */
        internal val FIXTURE = CareDb(
            schemaVersion = CareDb.SUPPORTED_SCHEMA,
            dataVersion = 1,
            sourceCommit = "test",
            archetypes = mapOf("fixture-group" to Archetype("Fixture group")),
            species = mapOf(
                "unknownus" to species("Unknownus testus", Toxicity()),
                "nonus" to species(
                    "Nonus testus",
                    Toxicity(cats = "no_known_toxicity", dogs = "no_known_toxicity", horses = "no_known_toxicity", humans = "no_known_toxicity"),
                ),
                "toxicus" to species("Toxicus testus", Toxicity(cats = "toxic", dogs = "mild_irritant", horses = "no_known_toxicity", humans = "unknown")),
            ),
        )
    }
}
