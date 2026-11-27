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
    fun exactlyTheFiftyReviewedNameRowsShip() {
        assertEquals(
            setOf(
                "allium-schoenoprasum",
                "alocasia",
                "aloe-vera",
                "anethum-graveolens",
                "anthurium-andraeanum",
                "bougainvillea",
                "calibrachoa",
                "capsicum",
                "capsicum-annuum",
                "chlorophytum-comosum",
                "citrus-reticulata",
                "coriandrum-sativum",
                "crassula-ovata",
                "cucumis-sativus",
                "cucurbita-pepo",
                "dieffenbachia",
                "epipremnum-aureum",
                "ficus-elastica",
                "ficus-lyrata",
                "fuchsia",
                "hibiscus-rosa-sinensis",
                "hydrangea",
                "juniperus",
                "kalanchoe-blossfeldiana",
                "laurus-nobilis",
                "lavandula",
                "lobelia-erinus",
                "melissa-officinalis",
                "mentha",
                "monstera-deliciosa",
                "nephrolepis-exaltata",
                "nerium-oleander",
                "olea-europaea",
                "origanum-vulgare",
                "pelargonium",
                "petroselinum-crispum",
                "petunia",
                "phalaenopsis",
                "philodendron",
                "rosa",
                "salvia-officinalis",
                "salvia-rosmarinus",
                "solanum-lycopersicum",
                "solanum-melongena",
                "spathiphyllum",
                "tagetes",
                "taxus-baccata",
                "thuja",
                "thymus-vulgaris",
                "zamioculcas-zamiifolia",
            ),
            db.names.keys,
        )
    }

    @Test
    fun searchingTheShippedAssetForMintFindsTheLeafyHerbNameRow() {
        val hits = searchCare(db, "mint")
        assertTrue(hits.any { it.slug == null && it.archetype == "outdoor-leafy-herb" && it.detail == "Mentha" })
    }

    @Test
    fun searchingTheShippedAssetForPeaceLilyFindsTheSpathiphyllumNameRow() {
        val hits = searchCare(db, "peace lily")
        assertTrue(hits.any { it.slug == null && it.archetype == "indoor-moisture-loving" && it.label == "peace lily" && it.detail == "Spathiphyllum" })
    }
}
