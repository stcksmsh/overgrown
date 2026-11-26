// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the pinned overgrown-data submodule. The app's care asset is compiled from these files
 * and the compiler keeps only entries with `status: reviewed`, so what is reviewed here is what ships.
 */
class CareDataPinTest {
    // Stop at this project's root: walking further could reach another checkout's submodule.
    private val dataRoot = File(
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile },
        "overgrown-data",
    ).also {
        check(File(it, "archetypes").isDirectory) {
            "overgrown-data submodule not initialised: run git submodule update --init"
        }
    }

    private fun status(file: File): String =
        file.readLines().first { it.startsWith("status:") }.removePrefix("status:").trim()

    private fun ids(dir: String, reviewed: Boolean): Set<String> =
        File(dataRoot, dir).listFiles { f -> f.extension == "yaml" }!!
            .filter { (status(it) == "reviewed") == reviewed }
            .map { it.nameWithoutExtension }
            .toSet()

    @Test
    fun exactlyTheEightReviewedArchetypesShip() {
        assertEquals(
            setOf(
                "hardy-potted-conifer",
                "indoor-epiphytic-orchid",
                "indoor-moisture-loving",
                "indoor-succulent",
                "indoor-tropical-foliage",
                "outdoor-fruiting-vegetable",
                "outdoor-mediterranean-shrub",
                "outdoor-tender-perennial",
            ),
            ids("archetypes", reviewed = true),
        )
        assertEquals(emptySet(), ids("archetypes", reviewed = false))
    }

    @Test
    fun draftSpeciesStayOutOfTheAsset() {
        assertTrue("dracaena-trifasciata" in ids("species", reviewed = false))
        assertTrue("dracaena-trifasciata" !in ids("species", reviewed = true))
    }
}
