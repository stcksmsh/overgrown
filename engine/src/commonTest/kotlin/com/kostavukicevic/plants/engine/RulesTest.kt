// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RulesTest {

    private val conifer = CareProfile(
        waterStrategy = WaterStrategy.DRY_TOP_PART,
        dryFraction = Coefficients.DRY_FRACTION_CONIFER_IN_POT,
        droughtAsymmetry = DroughtAsymmetry.IN_HEAT,
        waterloggingSensitive = true,
        winterRules = true,
        rootFreezeAlerts = true,
    )

    private fun balconyPlant(pot: Pot = Pot(8.0), exposure: RainExposure = RainExposure.PARTIAL) =
        Plant(conifer, canopyWidthCm = 35.0, pot = pot, place = Place.Outdoor(44.82, exposure))

    private fun day(date: LocalDate, tMin: Double, tMax: Double, rain: Double = 0.0, wind: Double? = null) =
        WeatherDay(date, tMin, tMax, rain, meanWindMs = wind)

    private val june1 = LocalDate(2026, Month.JUNE, 1)

    @Test
    fun confidentDryPlantGetsWaterNowWithDose() {
        val plant = balconyPlant()
        val state = EngineState(depletion = 0.6, belief = KBelief(logKVariance = 0.01))
        val action = Engine.recommend(plant, state, listOf(day(june1, 15.0, 27.0), day(june1.plus(DatePeriod(days = 1)), 15.0, 27.0))).action
        assertIs<Action.WaterNow>(action)
        val expected = 0.6 * WaterBalance.capacityMl(plant.pot) * 1.15
        assertEquals(expected, action.doseMl.toDouble(), 1.0)
    }

    @Test
    fun undrainedPotGetsSmallerDose() {
        val drained = WaterBalance.doseMl(0.5, Pot(2.0))
        val undrained = WaterBalance.doseMl(0.5, Pot(2.0, drainageHoles = false))
        assertEquals(300.0 * 1.15, drained, 1e-9)
        assertEquals(300.0 * 0.8, undrained, 1e-9)
    }

    @Test
    fun uncertainPlantGetsSoilCheckBeforeItIsDue() {
        val plant = balconyPlant()
        val state = EngineState(depletion = 0.35) // sd 0.5: 0.35 * e^0.5 = 0.58 >= 0.5
        val action = Engine.recommend(plant, state, listOf(day(june1, 15.0, 27.0))).action
        assertEquals(Action.CheckSoil(PromptReason.UNCERTAIN), action)
    }

    @Test
    fun frostTonightHoldsWaterNow() {
        val plant = balconyPlant()
        val state = EngineState(depletion = 0.8, belief = KBelief(logKVariance = 0.01))
        val march = LocalDate(2026, Month.MARCH, 20)
        val upcoming = listOf(day(march, 3.0, 14.0), day(march.plus(DatePeriod(days = 1)), 0.5, 12.0))
        assertEquals(Action.Hold(HoldReason.FROST), Engine.recommend(plant, state, upcoming).action)
    }

    @Test
    fun winterModeOnlyChecksSoilAboveTheWindow() {
        val plant = balconyPlant()
        val jan = LocalDate(2026, Month.JANUARY, 10)
        val cold = (0 until 7).map { day(LocalDate(2026, Month.JANUARY, 3 + it), -2.0, 5.0) }
        val state = EngineState(depletion = 0.7, belief = KBelief(logKVariance = 0.01), recent = cold)

        val mild = listOf(day(jan, 2.0, 8.0), day(jan.plus(DatePeriod(days = 1)), 2.0, 8.0))
        assertEquals(Action.CheckSoil(PromptReason.DRY_BY_MODEL), Engine.recommend(plant, state, mild).action)

        val chilly = listOf(day(jan, 2.0, 4.0), day(jan.plus(DatePeriod(days = 1)), 2.0, 4.0))
        assertEquals(Action.Hold(HoldReason.TOO_COLD), Engine.recommend(plant, state, chilly).action)
    }

    @Test
    fun winterFloorChecksSoilAfterTwoDryWeeks() {
        val plant = balconyPlant()
        val jan = LocalDate(2026, Month.JANUARY, 20)
        val cold = (0 until 7).map { day(LocalDate(2026, Month.JANUARY, 13 + it), -1.0, 6.0) }
        val upcoming = listOf(day(jan, 1.5, 7.0), day(jan.plus(DatePeriod(days = 1)), 1.5, 7.0))
        val dry = EngineState(depletion = 0.1, recent = cold, daysSinceRain = 14)
        assertEquals(Action.CheckSoil(PromptReason.WINTER_FLOOR), Engine.recommend(plant, dry, upcoming).action)

        val recentlyChecked = dry.copy(lastSoilCheck = LocalDate(2026, Month.JANUARY, 15))
        assertEquals(Action.None, Engine.recommend(plant, recentlyChecked, upcoming).action)
    }

    @Test
    fun rootFreezeAlertsAreStricterForSmallOrExposedPots() {
        val nov = LocalDate(2026, Month.NOVEMBER, 25)
        val upcoming = listOf(day(nov, -4.0, 2.0))
        val big = balconyPlant(pot = Pot(15.0))
        val small = balconyPlant(pot = Pot(5.0))
        assertTrue(Alerts.forecastAlerts(big, upcoming).isEmpty())
        assertEquals(listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, nov)), Alerts.forecastAlerts(small, upcoming))

        val record = listOf(day(nov, -18.2, -8.0))
        assertEquals(listOf(Alert(AlertType.ROOT_FREEZE_STRONG, nov)), Alerts.forecastAlerts(big, record))
    }

    @Test
    fun heatRainAndWindAlerts() {
        val plant = balconyPlant()
        val jul = LocalDate(2026, Month.JULY, 24)
        val alerts = Alerts.forecastAlerts(plant, listOf(day(jul, 25.0, 43.6, rain = 20.0, wind = 10.0))).map { it.type }
        assertEquals(listOf(AlertType.HEAT, AlertType.HEAVY_RAIN, AlertType.WIND), alerts)
        assertTrue(Alerts.forecastAlerts(plant, listOf(day(jul, 20.0, 31.9, rain = 19.9, wind = 9.9))).isEmpty())
    }

    @Test
    fun windSensitivePlantsAreWarnedEarlier() {
        val jul = LocalDate(2026, Month.JULY, 24)
        val breezy = listOf(day(jul, 18.0, 26.0, wind = 7.5))
        assertTrue(Alerts.forecastAlerts(balconyPlant(), breezy).isEmpty())
        val sensitive = balconyPlant().copy(profile = conifer.copy(windSensitive = true))
        assertEquals(listOf(Alert(AlertType.WIND, jul)), Alerts.forecastAlerts(sensitive, breezy))
    }

    @Test
    fun indoorPlantsGetNoWeatherAlerts() {
        val plant = Plant(conifer, 30.0, Pot(5.0), Place.Indoor(44.8, LightClass.HIGH))
        assertTrue(Alerts.forecastAlerts(plant, listOf(day(june1, -20.0, 40.0, rain = 50.0, wind = 20.0))).isEmpty())
    }

    // Basil's care data: frost class tender, minimum 10 degC, root-freeze alerts unknown.
    private val basil = CareProfile(WaterStrategy.EVENLY_MOIST, rootFreezeAlerts = null, minTempC = 10.0, frostClass = FrostClass.TENDER)

    private fun coldAlerts(profile: CareProfile, tMin: Double, place: Place = Place.Outdoor(44.82, RainExposure.PARTIAL)) =
        Alerts.forecastAlerts(Plant(profile, 25.0, Pot(5.0), place), listOf(day(june1, tMin, tMin + 8))).map { it.type }

    @Test
    fun tenderPlantsGetColdBelowTheirMinimumAndFrostNearZero() {
        assertEquals(emptyList(), coldAlerts(basil, 10.0))
        assertEquals(listOf(AlertType.COLD), coldAlerts(basil, 8.0))
        assertEquals(listOf(AlertType.FROST), coldAlerts(basil, Coefficients.FROST_ALERT_C))
        assertEquals(listOf(AlertType.FROST), coldAlerts(basil, -1.0))
        // A frost hard enough to freeze the pot as well is still one alert, the frost one.
        assertEquals(listOf(AlertType.FROST), coldAlerts(basil, -12.0))
        // Lemon: frost free, minimum unknown.
        val lemon = CareProfile(WaterStrategy.DRY_TOP_PART, minTempC = null, frostClass = FrostClass.FROST_FREE)
        assertEquals(listOf(AlertType.FROST), coldAlerts(lemon, 2.0))
        assertEquals(emptyList(), coldAlerts(lemon, 2.1))
    }

    @Test
    fun hardyPottedConiferGetsNoColdOrFrostAlert() {
        // hardy-potted-conifer: frost class hardy, no minimum, root-freeze alerts on.
        val hardy = conifer.copy(frostClass = FrostClass.HARDY)
        assertEquals(emptyList(), coldAlerts(hardy, -1.0))
        assertEquals(listOf(AlertType.ROOT_FREEZE_CAUTION), coldAlerts(hardy, -4.0))
    }

    @Test
    fun noColdAlertIndoorsOrWithoutAKnownMinimum() {
        assertEquals(emptyList(), coldAlerts(basil, -1.0, Place.Indoor(44.8, LightClass.HIGH)))
        assertEquals(emptyList(), coldAlerts(basil.copy(minTempC = null, frostClass = null), 8.0))
        // Cold and root freeze caution on the same day: the plant's own limit wins.
        assertEquals(listOf(AlertType.COLD), coldAlerts(conifer.copy(minTempC = -3.0), -4.0))
    }

    @Test
    fun waterloggingCautionAfterProlongedRainOnOpenUndrainedPot() {
        val plant = balconyPlant(pot = Pot(8.0, drainageHoles = false), exposure = RainExposure.OPEN)
        val wet = (0 until 3).map { day(LocalDate(2026, Month.OCTOBER, 1 + it), 8.0, 14.0, rain = 6.0) }
        val today = LocalDate(2026, Month.OCTOBER, 4)
        assertEquals(Alert(AlertType.WATERLOGGING, today), Alerts.waterlogging(plant, wet, today))
        assertEquals(null, Alerts.waterlogging(balconyPlant(exposure = RainExposure.OPEN), wet, today))
    }

    @Test
    fun rainRefillsOpenPotsAndIgnoresDrizzle() {
        val open = balconyPlant(exposure = RainExposure.OPEN)
        val covered = balconyPlant(exposure = RainExposure.COVERED)
        assertEquals(0.0, WaterBalance.rainRefillMl(open, day(june1, 15.0, 25.0, rain = 1.9)))
        assertEquals(0.0, WaterBalance.rainRefillMl(covered, day(june1, 15.0, 25.0, rain = 30.0)))
        val top = WaterBalance.potTopAreaM2(open.pot)
        assertEquals(10.0 * top * 1000, WaterBalance.rainRefillMl(open, day(june1, 15.0, 25.0, rain = 10.0)), 1e-9)
    }

    @Test
    fun wateringWithoutRunOffLeavesPartialDepletion() {
        val plant = balconyPlant()
        val state = EngineState(depletion = 0.7)
        assertEquals(0.0, Engine.apply(plant, state, Event.Watered(june1, ranOutOfBottom = true)).depletion)
        assertEquals(0.2, Engine.apply(plant, state, Event.Watered(june1, ranOutOfBottom = false)).depletion)
    }

    @Test
    fun repotAdviceIsGivenOnceUntilThePotChanges() {
        // A 40 cm canopy in a 2 l pot on a hot day uses more than the pot holds.
        val plant = Plant(CareProfile(WaterStrategy.DRY_TOP_PART), 40.0, Pot(2.0), Place.Outdoor(44.8, RainExposure.COVERED))
        val hot = { d: LocalDate -> WeatherDay(d, 20.0, 34.0, 0.0, et0Mm = 8.0) }
        fun date(i: Int) = june1.plus(DatePeriod(days = i))
        fun action(p: Plant, s: EngineState, i: Int) = Engine.recommend(p, s, listOf(hot(date(i)))).action
        assertTrue(WaterBalance.dailyUseMl(plant, hot(june1), 1.0) > WaterBalance.capacityMl(plant.pot))

        var state = Engine.initial(plant)
        assertEquals(Action.RepotOrWaterDaily, action(plant, state, 0))
        for (i in 0 until 5) {
            state = Engine.advance(plant, state, hot(date(i)))
            assertNotEquals(Action.RepotOrWaterDaily, action(plant, state, i + 1), "day ${i + 1}")
        }

        val moved = Engine.apply(plant, state, Event.Moved(date(5)))
        assertNotEquals(Action.RepotOrWaterDaily, action(plant, moved, 5))

        val repotted = Engine.apply(plant, state, Event.Repotted(date(5)))
        assertEquals(Action.RepotOrWaterDaily, action(plant, repotted, 5))

        val newPot = plant.copy(pot = Pot(2.5))
        assertTrue(WaterBalance.dailyUseMl(newPot, hot(june1), 1.0) > WaterBalance.capacityMl(newPot.pot))
        assertEquals(Action.RepotOrWaterDaily, action(newPot, state, 5))
    }

    @Test
    fun repotAdviceIsNotMarkedGivenByAnAnswerThatRaisedKAfterTheDaysPrompt() {
        val plant = Plant(CareProfile(WaterStrategy.DRY_TOP_PART), 40.0, Pot(2.0), Place.Outdoor(44.8, RainExposure.COVERED))
        val hot = { d: LocalDate -> WeatherDay(d, 20.0, 34.0, 0.0, et0Mm = 8.0) }
        val day1 = june1.plus(DatePeriod(days = 1))
        val day2 = june1.plus(DatePeriod(days = 2))
        var state = Engine.advance(plant, EngineState(belief = KBelief(logKMean = -1.0)), hot(june1))
        assertNotEquals(Action.RepotOrWaterDaily, Engine.recommend(plant, state, listOf(hot(day1))).action)

        // An answer later that day raises K enough that the pot is now too small.
        state = state.copy(belief = KBelief(logKMean = 0.0))
        state = Engine.advance(plant, state, hot(day1))
        assertEquals(Action.RepotOrWaterDaily, Engine.recommend(plant, state, listOf(hot(day2))).action)
    }

    @Test
    fun tooWetActsAtOnceButDryNeedsTwoAnswers() {
        val plant = Plant(CareProfile(WaterStrategy.DRY_TOP_PART), 30.0, Pot(2.0), Place.Indoor(44.8, LightClass.MEDIUM))
        val predicted = EngineState(depletion = 0.5)

        val wet = Engine.apply(plant, predicted, Event.CheckIn(june1, Moisture.TOO_WET))
        assertTrue(wet.belief.k < 1.0)

        val dryOnce = Engine.apply(plant, predicted, Event.CheckIn(june1, Moisture.DRY))
        assertEquals(1.0, dryOnce.belief.k)
        val dryTwice = Engine.apply(plant, dryOnce.copy(depletion = 0.5), Event.CheckIn(june1, Moisture.DRY))
        assertTrue(dryTwice.belief.k > 1.0)
    }

    @Test
    fun fernsActOnDryAtOnce() {
        val fern = Plant(CareProfile(WaterStrategy.EVENLY_MOIST, droughtAsymmetry = DroughtAsymmetry.ALWAYS), 30.0, Pot(2.0), Place.Indoor(44.8, LightClass.MEDIUM))
        val state = EngineState(depletion = 0.5)
        assertTrue(Engine.apply(fern, state, Event.CheckIn(june1, Moisture.DRY)).belief.k > 1.0)
        assertEquals(1.0, Engine.apply(fern, state, Event.CheckIn(june1, Moisture.TOO_WET)).belief.k)
    }

    @Test
    fun answerInsidePredictedBandBarelyMovesKAndGainsConfidence() {
        val plant = balconyPlant()
        val state = EngineState(depletion = 0.5)
        val after = Engine.apply(plant, state, Event.CheckIn(june1, Moisture.OK))
        assertTrue(kotlin.math.abs(after.belief.logKMean) < 0.1, "moved to ${after.belief.logKMean}")
        assertTrue(after.belief.logKVariance < state.belief.logKVariance)
        assertTrue(after.depletion in 0.3..0.7)
    }

    @Test
    fun quickDryCoversTheDryAndBoneDryBands() {
        val band = Learning.band(Moisture.DRY_OR_BONE_DRY)
        assertEquals(Coefficients.BAND_OK_MAX..1.0, band)
        assertNotEquals(Learning.band(Moisture.DRY), band)
        assertNotEquals(Learning.band(Moisture.BONE_DRY), band)
    }

    @Test
    fun quickDryIsOpenAboveFromTheDryBandsLowerEdge() {
        // An open-above band's update depends only on its lower edge relative to the prediction,
        // so a quick Dry at prediction d must equal a Bone dry at d scaled by the ratio of the edges.
        val d = 0.5
        val quick = Learning.update(KBelief(), d, Moisture.DRY_OR_BONE_DRY, drySideFast = true).belief
        val boneDry = Learning.update(KBelief(), d * Coefficients.BAND_DRY_MAX / Coefficients.BAND_OK_MAX, Moisture.BONE_DRY, drySideFast = true).belief
        assertEquals(boneDry.logKMean, quick.logKMean, 1e-9)
        assertEquals(boneDry.logKVariance, quick.logKVariance, 1e-9)

        val dry = Learning.update(KBelief(), d, Moisture.DRY, drySideFast = true).belief
        assertTrue(quick.logKMean > dry.logKMean + 0.01, "quick ${quick.logKMean}, dry ${dry.logKMean}")
    }

    @Test
    fun quickDryWhenAlreadyBoneDryKeepsK() {
        val b = KBelief()
        val quick = Learning.update(b, 0.98, Moisture.DRY_OR_BONE_DRY, drySideFast = false)
        // Agrees with the prediction, like Bone dry would: K stays and confidence is not lowered.
        assertEquals(b.logKMean, quick.belief.logKMean)
        assertTrue(quick.belief.logKVariance <= b.logKVariance)
        assertEquals(0.98, quick.depletion)
        // A plain Dry here would call the prediction too dry.
        assertTrue(Learning.update(b, 0.98, Moisture.DRY, drySideFast = false).belief.logKMean < b.logKMean)
    }
}
