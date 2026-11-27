// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import com.kostavukicevic.plants.caredb.CareDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The compiled asset of the pinned overgrown-data: the reviewed name rows are what add-plant search can find. */
class NameTablePinTest {

    // The asset `:app:compileCareAsset` generates from the submodule; Gradle runs unit tests from the module directory.
    private val db = CareDb.parse(File("build/generated/caredb/caredb.json").readText())

    @Test
    fun exactlyTheTenReviewedNameRowsShip() {
        assertEquals(
            setOf(
                "capsicum",
                "capsicum-annuum",
                "lavandula",
                "mentha",
                "monstera-deliciosa",
                "pelargonium",
                "petroselinum-crispum",
                "petunia",
                "salvia-rosmarinus",
                "solanum-lycopersicum",
            ),
            db.names.keys,
        )
    }

    @Test
    fun searchingTheShippedAssetForMintFindsTheLeafyHerbNameRow() {
        val hits = searchCare(db, "mint")
        assertTrue(hits.any { it.slug == null && it.archetype == "outdoor-leafy-herb" && it.detail == "Mentha" })
    }
}
