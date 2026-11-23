// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

/**
 * Every model constant, in one place (rule 7).
 *
 * Status labels:
 * - FACT: a linked source states it.
 * - PRIOR: a reasoned starting value; tunable, and corrected per plant by the learning loop.
 *
 * Enumerations from the care data are mapped to numbers here, not in the data, so that
 * tuning the engine never rewrites species files.
 */
object Coefficients {

    // ---- Solar geometry and reference evapotranspiration -------------------------------
    // FACT: FAO-56 chapter 3, equations 21-25 and 52. https://www.fao.org/4/x0490e/x0490e07.htm

    /** FACT (FAO-56): solar constant, MJ m-2 min-1. */
    const val SOLAR_CONSTANT = 0.0820

    /** FACT (FAO-56 eq. 52): Hargreaves-Samani coefficient. */
    const val HARGREAVES_COEFFICIENT = 0.0023

    /** FACT (FAO-56 eq. 52): temperature offset, degC. */
    const val HARGREAVES_TEMP_OFFSET_C = 17.8

    /** FACT (FAO-56): converts MJ m-2 day-1 to mm day-1 of evaporation. */
    const val MJ_TO_MM = 0.408

    // ---- Bucket -------------------------------------------------------------------------

    /**
     * FACT range 25-35 % of substrate volume (Bilderback et al. 2005,
     * https://www.ars.usda.gov/ARSUserFiles/11847/bilderback_etal_2005_Hort%20Tech%2015(4)%20pp%20747-751.pdf).
     * PRIOR: 0.30 for a pot 15 cm tall or taller.
     */
    const val AVAILABLE_WATER_FRACTION = 0.30
    const val AVAILABLE_WATER_FRACTION_MIN = 0.20
    const val AVAILABLE_WATER_FRACTION_MAX = 0.35

    /**
     * FACT: taller pots hold less water per litre, about 1.5 to 2.5 percentage points of
     * container capacity per cm between 4 and 15 cm (Owen & Altland 2008,
     * https://journals.ashs.org/view/journals/hortsci/43/2/article-p505.xml).
     * PRIOR: applied to available water at half that rate (available water is roughly half of
     * container capacity), as +0.01 per cm below the reference height, inside 4-15 cm only.
     */
    const val AWF_PER_CM_BELOW_REFERENCE = 0.01
    const val POT_REFERENCE_HEIGHT_CM = 15.0
    const val POT_HEIGHT_EFFECT_MIN_CM = 4.0

    /** PRIOR: heights used when the person gives a shape instead of a measurement. */
    const val SHALLOW_POT_HEIGHT_CM = 8.0
    const val STANDARD_POT_HEIGHT_CM = 15.0
    const val TALL_POT_HEIGHT_CM = 25.0

    /** PRIOR: dose is the deficit plus this share when the pot drains, so some runs through. */
    const val DOSE_DRAINED_EXTRA = 0.15

    /** PRIOR: dose is the deficit less this share when the pot has no drainage holes. */
    const val DOSE_UNDRAINED_REDUCTION = 0.20

    /** PRIOR: depletion after a watering where the person did not see water run out. */
    const val PARTIAL_WATERING_DEPLETION = 0.2

    // ---- Pot material -------------------------------------------------------------------

    /** PRIOR: unglazed terracotta loses water through its walls. No measured ratio found. */
    const val MATERIAL_FACTOR_TERRACOTTA = 1.25
    const val MATERIAL_FACTOR_PLASTIC = 1.0
    const val MATERIAL_FACTOR_GLAZED = 1.0

    // ---- Outdoor demand -----------------------------------------------------------------

    /**
     * PRIOR, from FACT: published coefficients for container ornamentals on canopy area are
     * about 0.50-0.63 (Beeson 2010,
     * https://journals.ashs.org/downloadpdf/view/journals/hortsci/45/8/article-p1260.pdf).
     * 0.75 starts on the generous side; the learned K corrects it. Bounds 0.5-1.0 before K.
     */
    const val OUTDOOR_CANOPY_COEFFICIENT = 0.75

    /** PRIOR: no study of rain capture on balconies was found. */
    const val RAIN_EXPOSURE_OPEN = 1.0
    const val RAIN_EXPOSURE_PARTIAL = 0.5
    const val RAIN_EXPOSURE_COVERED = 0.0

    /** PRIOR: days with less rain than this do not refill the pot. */
    const val RAIN_MIN_MM = 2.0

    // ---- Indoor demand ------------------------------------------------------------------

    /**
     * FACT: a pothos in a 15 cm pot used about 73 ml a day at about 2150 lux, and days between
     * waterings rose from 6-8 at about 2150 lux to 16-22 at about 540 lux (UF/IFAS,
     * https://mrec.ifas.ufl.edu/foliage/resrpts/rh_93_15.htm). One study.
     */
    const val INDOOR_REFERENCE_USE_ML = 73.0
    const val INDOOR_REFERENCE_LUX = 2150.0
    const val INDOOR_REFERENCE_PHOTOPERIOD_H = 12.0

    /** PRIOR: the study gives no canopy size; about 30 cm wide. */
    const val INDOOR_REFERENCE_CANOPY_M2 = 0.07

    /** PRIOR, one study: range 0.75-0.95. */
    const val INDOOR_LIGHT_EXPONENT = 0.8

    /** PRIOR: a plant in near darkness still transpires a little. */
    const val INDOOR_LIGHT_TERM_FLOOR = 0.15

    /** FACT as prior art: lux to photosynthetic photon flux for sunlight, umol m-2 s-1 per lux. Range 0.013-0.020. */
    const val LUX_TO_PPFD_SUNLIGHT = 0.0185

    /**
     * FACT, extension rule of thumb (Maryland Extension,
     * https://extension.umd.edu/resource/natural-lighting-indoor-plants):
     * low 270-1080, medium 1080-5400, high 5400-10 800, direct above 10 800 lux.
     */
    const val LIGHT_LOW_MIN_LUX = 270.0
    const val LIGHT_MEDIUM_MIN_LUX = 1080.0
    const val LIGHT_HIGH_MIN_LUX = 5400.0
    const val LIGHT_DIRECT_MIN_LUX = 10_800.0

    /** PRIOR: lux used for a class when there is no reading: geometric mean of its bounds, twice the floor for direct. */
    const val LIGHT_LOW_TYPICAL_LUX = 540.0
    const val LIGHT_MEDIUM_TYPICAL_LUX = 2415.0
    const val LIGHT_HIGH_TYPICAL_LUX = 7637.0
    const val LIGHT_DIRECT_TYPICAL_LUX = 21_600.0

    /** PRIOR: indoor temperature effect, per degC from the reference, bounded at +/-20 %. */
    const val INDOOR_TEMP_REFERENCE_C = 21.0
    const val INDOOR_TEMP_EFFECT_PER_C = 0.03
    const val INDOOR_TERM_BOUND = 0.20

    /** PRIOR: dry heated air in the heating season. Within the +/-20 % bound. */
    const val INDOOR_DRY_AIR_FACTOR = 1.15

    // ---- Dry fraction by water strategy ---------------------------------------------------

    /** PRIOR. */
    const val DRY_FRACTION_DRY_OUT_FULLY = 0.95
    const val DRY_FRACTION_DRY_TOP_PART = 0.6
    const val DRY_FRACTION_EVENLY_MOIST = 0.4
    const val DRY_FRACTION_CONIFER_IN_POT = 0.5

    /**
     * PRIOR: no value given in the brief; wetter than evenly moist. Not below the top of the
     * "too wet" answer band (0.3), or check-in answers could never show the plant had dried.
     */
    const val DRY_FRACTION_WET_FEET = 0.3

    /** PRIOR: epiphytes are soaked and left to dry, like dry-top-part plants. */
    const val DRY_FRACTION_EPIPHYTE_SOAK = 0.6

    // ---- Winter and alert rules ---------------------------------------------------------

    /** PRIOR: never "water now" when the coming night's minimum is at or below this. */
    const val FROST_HOLD_MIN_C = 1.0

    /**
     * PRIOR, from in-ground guidance (Colorado State Extension,
     * https://extension.colostate.edu/topic-areas/yard-garden/fall-and-winter-watering-7-211):
     * winter soil checks only when the air is above about 4-5 degC.
     */
    const val WINTER_WINDOW_MIN_AIR_C = 4.5

    /** PRIOR: winter rules apply while the mean of the last 7 days is below this. */
    const val WINTER_MODE_MEAN_C = 5.0
    const val WINTER_MODE_WINDOW_DAYS = 7

    /**
     * PRIOR (UMass Extension,
     * https://www.umass.edu/agriculture-food-environment/greenhouse-floriculture/fact-sheets/overwintering-container-grown-ornamentals):
     * in a dry spell, check soil about every two weeks regardless of the balance.
     */
    const val WINTER_FLOOR_DAYS = 14

    /**
     * FACT: -5 degC is the level a UK nursery body treats as serious for container roots (AHDB,
     * https://archive.ahdb.org.uk/knowledge-library/causes-of-winter-injury-in-container-grown-nursery-stock).
     * PRIOR: -10 degC for a strong alert. Nothing specific to Chamaecyparis was found.
     */
    const val ROOT_FREEZE_CAUTION_C = -5.0
    const val ROOT_FREEZE_STRONG_C = -10.0

    /** PRIOR: small pots and exposed places get alerts this many degrees earlier. */
    const val ROOT_FREEZE_STRICTER_BY_C = 2.0
    const val SMALL_POT_LITRES = 10.0

    /** PRIOR: "prolonged rain" for the waterlogging caution. */
    const val WATERLOGGING_WET_DAYS = 3

    /** PRIOR: hold "water now" when rain expected tomorrow would refill at least this share of the deficit. */
    const val RAIN_HOLD_SHARE = 0.5

    /** PRIOR. */
    const val HEAT_ALERT_MAX_C = 32.0
    const val HEAVY_RAIN_ALERT_MM = 20.0
    const val WIND_ALERT_MEAN_MS = 10.0

    // ---- Learning -----------------------------------------------------------------------

    /** PRIOR: start at K = 1 with standard deviation 0.5 in log units; clamp K to 0.3-3. */
    const val LOGK_INITIAL_MEAN = 0.0
    const val LOGK_INITIAL_SD = 0.5
    const val K_MIN = 0.3
    const val K_MAX = 3.0

    /** PRIOR: observation noise, 0.4-0.5 log units. The lower value is used where "too wet" must act faster. */
    const val OBSERVATION_SD = 0.5
    const val OBSERVATION_SD_FAST = 0.4

    /**
     * PRIOR: daily process noise variance on logK. 0.0001 a day lets a year without answers raise
     * the standard deviation by about 0.19 log units; plants change slowly unless moved or repotted,
     * which add their own variance.
     */
    const val PROCESS_VARIANCE_PER_DAY = 0.0001

    /** PRIOR: variance added after a repot or a move. */
    const val VARIANCE_BUMP_CHANGE = 0.10

    /** PRIOR: variance added after this many consecutive gap-filled weather days. */
    const val VARIANCE_BUMP_WEATHER_GAP = 0.05
    const val WEATHER_GAP_DAYS = 3

    /** PRIOR: variance added for an answer further than this many standard deviations from the prediction. */
    const val VARIANCE_BUMP_SURPRISE = 0.05
    const val SURPRISE_SD = 2.0

    /** PRIOR: smallest predicted depletion used when comparing with an answer, to keep the log finite. */
    const val MIN_PREDICTED_DEPLETION = 0.05

    /** PRIOR: check-in answer bands, as depletion. */
    const val BAND_TOO_WET_MAX = 0.3
    const val BAND_OK_MAX = 0.7
    const val BAND_DRY_MAX = 0.95

    /** PRIOR: below this standard deviation of logK, the engine says "water now" instead of "check the soil". */
    const val CONFIDENT_SD = 0.2

    // ---- Baseline model ---------------------------------------------------------------
    // From ahaverty/plants (MIT licence), https://github.com/ahaverty/plants, plants/schedule.py.

    /** Monthly weight between the winter (0) and summer (1) interval, January first. */
    val BASELINE_SEASON_WEIGHT = doubleArrayOf(0.0, 0.0, 0.25, 0.5, 0.8, 1.0, 1.0, 1.0, 0.6, 0.35, 0.1, 0.0)
    const val BASELINE_POT_SMALL = 0.85
    const val BASELINE_POT_MEDIUM = 1.0
    const val BASELINE_POT_LARGE = 1.2
    const val BASELINE_LIGHT_LOW = 1.15
    const val BASELINE_LIGHT_MEDIUM = 1.0
    const val BASELINE_LIGHT_BRIGHT = 0.9
    const val BASELINE_WINTER_MULTIPLE = 3.0
    const val BASELINE_RAIN_MM = 4.0
    const val BASELINE_FROST_C = 1.0
    const val BASELINE_SKIP_FRACTION = 0.35
    const val BASELINE_SKIP_MIN_DAYS = 2

    /** PRIOR: pot-size classes for the baseline multipliers, litres. */
    const val BASELINE_SMALL_POT_MAX_L = 2.0
    const val BASELINE_LARGE_POT_MIN_L = 10.0
}
