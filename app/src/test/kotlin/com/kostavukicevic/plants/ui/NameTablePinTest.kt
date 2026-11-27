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
    fun exactlyTheHundredFortyFiveReviewedNameRowsShip() {
        assertEquals(
            setOf(
                "abelmoschus-esculentus",
                "agapanthus",
                "ageratum-houstonianum",
                "aglaonema",
                "allium-schoenoprasum",
                "allium-tuberosum",
                "alocasia",
                "aloe-vera",
                "anethum-graveolens",
                "anthriscus-cerefolium",
                "anthurium-andraeanum",
                "antirrhinum-majus",
                "argyranthemum-frutescens",
                "artemisia-dracunculus",
                "asparagus-setaceus",
                "aspidistra-elatior",
                "asplenium-nidus",
                "beaucarnea-recurvata",
                "bougainvillea",
                "brugmansia",
                "buddleja-davidii",
                "calathea",
                "calibrachoa",
                "camellia-japonica",
                "canna",
                "capsicum",
                "capsicum-annuum",
                "catharanthus-roseus",
                "cattleya",
                "celosia-argentea",
                "chamaecyparis-obtusa",
                "chamaedorea-elegans",
                "chlorophytum-comosum",
                "chrysalidocarpus-lutescens",
                "citrullus-lanatus",
                "citrus-reticulata",
                "codiaeum-variegatum",
                "coriandrum-sativum",
                "cosmos-bipinnatus",
                "crassula-ovata",
                "cryptomeria-japonica",
                "ctenanthe",
                "cucumis-melo",
                "cucumis-sativus",
                "cucurbita-pepo",
                "dahlia",
                "dendrobium",
                "dieffenbachia",
                "dracaena",
                "echeveria",
                "epipremnum-aureum",
                "eriobotrya-japonica",
                "euphorbia-milii",
                "ficus-benjamina",
                "ficus-carica",
                "ficus-elastica",
                "ficus-lyrata",
                "ficus-microcarpa",
                "fittonia",
                "foeniculum-vulgare",
                "fuchsia",
                "goeppertia",
                "haworthia",
                "helichrysum-italicum",
                "hibiscus-rosa-sinensis",
                "hibiscus-syriacus",
                "howea-forsteriana",
                "hoya",
                "hydrangea",
                "hyssopus-officinalis",
                "ilex-aquifolium",
                "impatiens-hawkeri",
                "juniperus",
                "kalanchoe-blossfeldiana",
                "kalanchoe-daigremontiana",
                "lantana-camara",
                "laurus-nobilis",
                "lavandula",
                "levisticum-officinale",
                "lithops",
                "lobelia-erinus",
                "lobularia-maritima",
                "mammillaria",
                "mandevilla",
                "maranta",
                "melissa-officinalis",
                "mentha",
                "monstera-deliciosa",
                "myrtus-communis",
                "nandina-domestica",
                "nepeta-cataria",
                "nephrolepis-exaltata",
                "nerium-oleander",
                "olea-europaea",
                "oncidium",
                "opuntia",
                "origanum-majorana",
                "origanum-vulgare",
                "osteospermum",
                "pachira-aquatica",
                "pelargonium",
                "peperomia",
                "petroselinum-crispum",
                "petunia",
                "phalaenopsis",
                "phaseolus-vulgaris",
                "philodendron",
                "physalis-peruviana",
                "picea-glauca",
                "picea-pungens",
                "pilea-cadierei",
                "pilea-peperomioides",
                "pinus-mugo",
                "pittosporum-tobira",
                "platycerium",
                "plumbago-auriculata",
                "prunus-laurocerasus",
                "rhapis-excelsa",
                "rhododendron",
                "rosa",
                "rumex-acetosa",
                "ruta-graveolens",
                "salvia-officinalis",
                "salvia-rosmarinus",
                "santolina-chamaecyparissus",
                "satureja",
                "scaevola-aemula",
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
                "tropaeolum-majus",
                "vaccinium-corymbosum",
                "vanda",
                "verbena",
                "zamioculcas-zamiifolia",
                "zinnia",
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

    @Test
    fun searchingTheShippedAssetForDragonTreeFindsTheDracaenaNameRow() {
        val hits = searchCare(db, "dragon tree")
        assertTrue(hits.any { it.slug == null && it.archetype == "indoor-tropical-foliage" && it.label == "dragon tree" && it.detail == "Dracaena" })
    }
}
