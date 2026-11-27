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
    fun exactlyTheNinetyNineReviewedNameRowsShip() {
        assertEquals(
            setOf(
                "aglaonema",
                "allium-schoenoprasum",
                "allium-tuberosum",
                "alocasia",
                "aloe-vera",
                "anethum-graveolens",
                "anthurium-andraeanum",
                "argyranthemum-frutescens",
                "aspidistra-elatior",
                "asplenium-nidus",
                "beaucarnea-recurvata",
                "bougainvillea",
                "calibrachoa",
                "camellia-japonica",
                "capsicum",
                "capsicum-annuum",
                "cattleya",
                "chamaedorea-elegans",
                "chlorophytum-comosum",
                "chrysalidocarpus-lutescens",
                "citrus-reticulata",
                "codiaeum-variegatum",
                "coriandrum-sativum",
                "crassula-ovata",
                "ctenanthe",
                "cucumis-sativus",
                "cucurbita-pepo",
                "dendrobium",
                "dieffenbachia",
                "echeveria",
                "epipremnum-aureum",
                "euphorbia-milii",
                "ficus-benjamina",
                "ficus-elastica",
                "ficus-lyrata",
                "fittonia",
                "fuchsia",
                "haworthia",
                "hibiscus-rosa-sinensis",
                "howea-forsteriana",
                "hoya",
                "hydrangea",
                "ilex-aquifolium",
                "impatiens-hawkeri",
                "juniperus",
                "kalanchoe-blossfeldiana",
                "lantana-camara",
                "laurus-nobilis",
                "lavandula",
                "levisticum-officinale",
                "lithops",
                "lobelia-erinus",
                "mammillaria",
                "mandevilla",
                "maranta",
                "melissa-officinalis",
                "mentha",
                "monstera-deliciosa",
                "nephrolepis-exaltata",
                "nerium-oleander",
                "olea-europaea",
                "oncidium",
                "opuntia",
                "origanum-majorana",
                "origanum-vulgare",
                "osteospermum",
                "pelargonium",
                "peperomia",
                "petroselinum-crispum",
                "petunia",
                "phalaenopsis",
                "phaseolus-vulgaris",
                "philodendron",
                "physalis-peruviana",
                "picea-glauca",
                "pilea-peperomioides",
                "pinus-mugo",
                "pittosporum-tobira",
                "plumbago-auriculata",
                "rhododendron",
                "rosa",
                "salvia-officinalis",
                "salvia-rosmarinus",
                "satureja",
                "schlumbergera",
                "sedum-morganianum",
                "solanum-lycopersicum",
                "solanum-melongena",
                "spathiphyllum",
                "streptocarpus",
                "streptocarpus-ionanthus",
                "syngonium",
                "tagetes",
                "taxus-baccata",
                "thuja",
                "thymus-vulgaris",
                "vaccinium-corymbosum",
                "verbena",
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

    @Test
    fun searchingTheShippedAssetForAfricanVioletFindsTheStreptocarpusNameRow() {
        val hits = searchCare(db, "African violet")
        assertTrue(hits.any { it.slug == null && it.archetype == "indoor-moisture-loving" && it.detail == "Streptocarpus ionanthus" })
    }
}
