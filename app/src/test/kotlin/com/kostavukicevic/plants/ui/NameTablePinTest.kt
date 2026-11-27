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
    fun exactlyTheHundredNinetyThreeReviewedNameRowsShip() {
        assertEquals(
            setOf(
                "abelmoschus-esculentus",
                "abies-concolor",
                "abies-koreana",
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
                "berberis",
                "bougainvillea",
                "brugmansia",
                "buddleja-davidii",
                "calathea",
                "calibrachoa",
                "callisia-repens",
                "camellia-japonica",
                "canna",
                "capsicum",
                "capsicum-annuum",
                "capsicum-chinense",
                "catharanthus-roseus",
                "cattleya",
                "celosia-argentea",
                "chaenostoma-cordatum",
                "chamaecyparis-obtusa",
                "chamaecyparis-pisifera",
                "chamaedorea-elegans",
                "chlorophytum-comosum",
                "chrysalidocarpus-lutescens",
                "citrullus-lanatus",
                "citrus-reticulata",
                "codiaeum-variegatum",
                "coriandrum-sativum",
                "cosmos-bipinnatus",
                "cotoneaster",
                "crassula-ovata",
                "cryptomeria-japonica",
                "ctenanthe",
                "cucumis-melo",
                "cucumis-sativus",
                "cucurbita-pepo",
                "dahlia",
                "dasiphora-fruticosa",
                "dendrobium",
                "deutzia",
                "diascia",
                "dieffenbachia",
                "dracaena",
                "echeveria",
                "epipremnum-aureum",
                "eriobotrya-japonica",
                "euonymus-fortunei",
                "euphorbia-milii",
                "euphorbia-tirucalli",
                "euphorbia-trigona",
                "felicia-amelloides",
                "ficus-benjamina",
                "ficus-carica",
                "ficus-elastica",
                "ficus-lyrata",
                "ficus-microcarpa",
                "fittonia",
                "foeniculum-vulgare",
                "forsythia",
                "fuchsia",
                "gasteria",
                "gazania",
                "goeppertia",
                "graptopetalum",
                "gymnocalycium",
                "hamamelis",
                "haworthia",
                "helichrysum-italicum",
                "helichrysum-petiolare",
                "heliotropium",
                "hibiscus-rosa-sinensis",
                "hibiscus-syriacus",
                "howea-forsteriana",
                "hoya",
                "hydrangea",
                "hypoestes",
                "hyssopus-officinalis",
                "ilex-aquifolium",
                "impatiens-hawkeri",
                "ipomoea-tricolor",
                "juniperus",
                "kalanchoe-blossfeldiana",
                "kalanchoe-daigremontiana",
                "kerria-japonica",
                "lantana-camara",
                "laurus-nobilis",
                "lavandula",
                "levisticum-officinale",
                "lithops",
                "lobelia-erinus",
                "lobularia-maritima",
                "magnolia-stellata",
                "mammillaria",
                "mandevilla",
                "maranta",
                "melissa-officinalis",
                "mentha",
                "monstera-deliciosa",
                "myrtus-communis",
                "nandina-domestica",
                "nemesia",
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
                "pachyphytum",
                "pelargonium",
                "peperomia",
                "petroselinum-crispum",
                "petunia",
                "phalaenopsis",
                "phaseolus-coccineus",
                "phaseolus-vulgaris",
                "philadelphus",
                "philodendron",
                "photinia",
                "physalis-peruviana",
                "picea-abies",
                "picea-glauca",
                "picea-omorika",
                "picea-pungens",
                "pieris",
                "pilea-cadierei",
                "pilea-peperomioides",
                "pinus-mugo",
                "pittosporum-tobira",
                "platycerium",
                "plumbago-auriculata",
                "prunus-laurocerasus",
                "radermachera-sinica",
                "rebutia",
                "rhapis-excelsa",
                "rhododendron",
                "rosa",
                "rumex-acetosa",
                "ruta-graveolens",
                "salvia-farinacea",
                "salvia-officinalis",
                "salvia-rosmarinus",
                "santolina-chamaecyparissus",
                "sanvitalia-procumbens",
                "satureja",
                "scaevola-aemula",
                "schlumbergera",
                "sedum-morganianum",
                "skimmia",
                "solanum-lycopersicum",
                "solanum-melongena",
                "soleirolia-soleirolii",
                "spathiphyllum",
                "spiraea",
                "streptocarpus",
                "streptocarpus-ionanthus",
                "stromanthe",
                "syngonium",
                "syringa",
                "tagetes",
                "taxus-baccata",
                "thuja",
                "thunbergia",
                "thymus-vulgaris",
                "torenia",
                "tropaeolum-majus",
                "tsuga-canadensis",
                "vaccinium-corymbosum",
                "vanda",
                "verbena",
                "weigela",
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

    @Test
    fun searchingTheShippedAssetForLilacFindsTheSyringaNameRow() {
        val hits = searchCare(db, "lilac")
        assertTrue(hits.any { it.slug == null && it.archetype == "outdoor-hardy-flowering-shrub" && it.detail == "Syringa" })
    }
}
