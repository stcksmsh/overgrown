// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.engine.Engine
import com.kostavukicevic.plants.engine.EngineState
import com.kostavukicevic.plants.engine.Event
import com.kostavukicevic.plants.engine.KBelief
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Learning
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.Pot
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WaterBalance
import com.kostavukicevic.plants.engine.WaterStrategy
import com.kostavukicevic.plants.engine.WeatherDay
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTest {

    private companion object {
        /**
         * Measured over 1000 random plants: harmful answers stopped by answer 9 at worst (90th
         * percentile 0). Final error in K: median 7 %, 90th percentile a factor of 1.6.
         */
        const val HARMLESS_AFTER = 15
    }

    private fun dbl(lo: Double, hi: Double) = Arb.double(lo, hi, includeNaNs = false)

    private val start = LocalDate(2026, Month.JANUARY, 1)

    private val arbPlant: Arb<Plant> = Arb.bind(
        Arb.enum<WaterStrategy>(),
        dbl(10.0, 80.0),
        dbl(0.5, 30.0),
        Arb.enum<PotMaterial>(),
        Arb.boolean(),
        Arb.boolean(),
        Arb.enum<RainExposure>(),
        Arb.enum<LightClass>(),
    ) { strategy, canopy, litres, material, holes, outdoor, exposure, light ->
        Plant(
            CareProfile(strategy, winterRules = outdoor, rootFreezeAlerts = outdoor),
            canopyWidthCm = canopy,
            pot = Pot(litres, material = material, drainageHoles = holes),
            place = if (outdoor) Place.Outdoor(44.82, exposure) else Place.Indoor(44.82, light),
        )
    }

    private val arbDay: Arb<WeatherDay> = Arb.bind(
        Arb.int(0, 364), dbl(-15.0, 25.0), dbl(0.0, 18.0), dbl(0.0, 40.0), dbl(0.0, 15.0),
    ) { offset, tMin, range, rain, wind ->
        WeatherDay(start.plus(DatePeriod(days = offset)), tMin, tMin + range, rain, meanWindMs = wind)
    }

    @Test
    fun waterIsConservedInTheBucket() { runBlocking {
        checkAll(dbl(0.0, 1.0), dbl(0.0, 2000.0), dbl(0.0, 2000.0), dbl(100.0, 6000.0)) { d, use, refill, capacity ->
            val after = WaterBalance.stepDepletion(d, use, refill, capacity)
            // Water in the pot after = before - use (limited to what was there) + refill (limited to the room left).
            val usedMl = min(use, (1 - d) * capacity)
            val afterUse = d * capacity + usedMl
            val refilledMl = min(refill, afterUse)
            assertEquals(afterUse - refilledMl, after * capacity, 1e-6)
            assertTrue(after in 0.0..1.0)
        }
    } }

    @Test
    fun engineDayMatchesTheBucketEquation() { runBlocking {
        checkAll(arbPlant, arbDay, dbl(0.0, 1.0)) { plant, day, d ->
            val state = EngineState(depletion = d)
            val next = Engine.advance(plant, state, day)
            val expected = WaterBalance.stepDepletion(
                d, WaterBalance.dailyUseMl(plant, day, 1.0), WaterBalance.rainRefillMl(plant, day), WaterBalance.capacityMl(plant.pot),
            )
            assertEquals(expected, next.depletion, 1e-12)
        }
    } }

    /** Days from full until the engine, confident and with K = 1, asks for water under constant weather. */
    private fun interval(plant: Plant, day: WeatherDay): Int {
        var s = EngineState(belief = KBelief(logKVariance = 0.0))
        for (i in 0 until 400) {
            val d = day.copy(date = day.date.plus(DatePeriod(days = i)))
            if (Engine.recommend(plant, s, listOf(d, d)).action !is Action.None) return i
            s = Engine.advance(plant, s, d)
        }
        return 400
    }

    @Test
    fun moreLightNeverLengthensTheInterval() { runBlocking {
        checkAll(arbPlant, dbl(50.0, 30_000.0), dbl(1.0, 3.0)) { base, lux, factor ->
            val place = Place.Indoor(44.82, LightClass.MEDIUM, lux = lux)
            val plant = base.copy(place = place)
            val day = WeatherDay(LocalDate(2026, Month.APRIL, 1), 10.0, 20.0, 0.0)
            val brighter = plant.copy(place = place.copy(lux = lux * factor))
            assertTrue(interval(brighter, day) <= interval(plant, day))
        }
    } }

    @Test
    fun moreHeatNeverLengthensTheInterval() { runBlocking {
        checkAll(arbPlant, dbl(5.0, 20.0), dbl(2.0, 15.0), dbl(0.0, 10.0)) { base, tMin, range, warmer ->
            // Summer dates and no rain, so neither winter rules nor rain interfere.
            val plant = base.copy(place = Place.Outdoor(44.82, RainExposure.COVERED))
            val day = WeatherDay(LocalDate(2026, Month.JUNE, 1), tMin, tMin + range, 0.0)
            val hotter = day.copy(tMinC = tMin + warmer, tMaxC = tMin + range + warmer)
            val hotterDays = day.copy(tMaxC = tMin + range + warmer)
            assertTrue(interval(plant, hotter) <= interval(plant, day))
            assertTrue(interval(plant, hotterDays) <= interval(plant, day))
        }
    } }

    @Test
    fun noWaterNowOnAFrostHoldDay() { runBlocking {
        checkAll(arbPlant, arbDay, arbDay, dbl(0.0, 1.0), dbl(0.0, 0.25), dbl(-20.0, 1.0)) { base, today, tomorrow, d, variance, frost ->
            val plant = base.copy(place = Place.Outdoor(44.82, RainExposure.OPEN))
            val state = EngineState(depletion = d, belief = KBelief(logKVariance = variance))
            val action = Engine.recommend(plant, state, listOf(today, tomorrow.copy(tMinC = frost, tMaxC = max(frost, tomorrow.tMaxC)))).action
            assertTrue(action !is Action.WaterNow, "got $action")
        }
    } }

    @Test
    fun kStaysInsideItsClampsUnderHostileFeedback() { runBlocking {
        val answers = Arb.list(Arb.bind(Arb.element(Moisture.entries), dbl(0.0, 1.0)) { m, p -> m to p }, 1..200)
        checkAll(answers, Arb.boolean()) { sequence, drySideFast ->
            var b = KBelief()
            for ((m, predicted) in sequence) {
                b = Learning.update(b, predicted, m, drySideFast).belief
                assertTrue(b.k in Coefficients.K_MIN - 1e-9..Coefficients.K_MAX + 1e-9, "k = ${b.k}")
                assertTrue(b.logKVariance in 0.0..Coefficients.LOGK_INITIAL_SD * Coefficients.LOGK_INITIAL_SD + 1e-12)
            }
        }
    } }

    @Test
    fun wateredWithoutAnAnswerNeverChangesK() { runBlocking {
        checkAll(arbPlant, dbl(-1.0, 1.0), dbl(0.0, 0.25), dbl(0.0, 1.0), Arb.boolean()) { plant, mean, variance, d, ranOut ->
            val belief = KBelief(logKMean = mean, logKVariance = variance)
            val after = Engine.apply(plant, EngineState(depletion = d, belief = belief), Event.Watered(start, ranOut))
            assertEquals(belief, after.belief)
        }
    } }

    /**
     * A plant that really uses twice (or half) what the engine predicts. The person answers the
     * true band at every prompt.
     *
     * What the brief's learning rule guarantees, and this checks:
     * - harmless: after [HARMLESS_AFTER] answers the person never reports soil two bands drier
     *   than the band the plant's dry fraction lies in (one band drier is expected: a daily
     *   prompt overshoots the dry fraction by up to a day's use), nor "too wet" when told to
     *   water now (except wet-feet plants, which are prompted at the edge of "too wet");
     * - K moves toward the truth, never past it by more than it started off, and never the
     *   wrong way.
     *
     * What it does not guarantee: K reaching the truth. An answer that agrees with the prediction
     * does not move K, so where the truth and the prediction share a band at the prompt, K can
     * stay up to about a factor of two off. See docs/DECISIONS.md D18.
     */
    @Test
    fun misSpecifiedByFactorTwoConverges() { runBlocking {
        val day = WeatherDay(LocalDate(2026, Month.APRIL, 1), 10.0, 20.0, 0.0)
        val indoorPlants = arbPlant
            .map { it.copy(place = Place.Indoor(44.82, LightClass.MEDIUM), profile = it.profile.copy(winterRules = false)) }
            // A daily prompt cannot protect a pot that loses more than 15 % of its water a day.
            .filter { WaterBalance.dailyUseMl(it, day, 2.0) / WaterBalance.capacityMl(it.pot) <= 0.15 }
        checkAll(indoorPlants, Arb.element(2.0, 0.5)) { plant, trueK ->
            val capacity = WaterBalance.capacityMl(plant.pot)
            val intended = bandOf(WaterBalance.dryFraction(plant.profile))
            var s = Engine.initial(plant)
            var truth = 0.0
            var answers = 0
            var lastHarm = 0
            for (i in 0 until 20_000) {
                if (answers >= HARMLESS_AFTER + 30) break
                val action = Engine.recommend(plant, s, listOf(day, day)).action
                if (action is Action.CheckSoil || action is Action.WaterNow) {
                    val answer = bandOf(truth)
                    answers++
                    val tooDry = answer.ordinal >= intended.ordinal + 2
                    // Told to water while clearly wet. A plant prompted at the "too wet" edge (wet feet) is meant to be wet.
                    val wateredWet = answer == Moisture.TOO_WET && action is Action.WaterNow &&
                        WaterBalance.dryFraction(plant.profile) > Coefficients.BAND_TOO_WET_MAX + 0.05
                    if (tooDry || wateredWet) lastHarm = answers
                    if (answer == Moisture.DRY || answer == Moisture.BONE_DRY || action is Action.WaterNow) {
                        s = Engine.apply(plant, s, Event.Watered(day.date, ranOutOfBottom = true, moisture = answer))
                        truth = 0.0
                    } else {
                        s = Engine.apply(plant, s, Event.CheckIn(day.date, answer))
                    }
                    val k = s.belief.k
                    assertTrue(abs(ln(k / trueK)) <= ln(2.0) + 1e-9, "true K $trueK: K $k is further off than at the start")
                }
                truth = min(1.0, truth + WaterBalance.dailyUseMl(plant, day, trueK) / capacity)
                s = Engine.advance(plant, s, day)
            }
            val k = s.belief.k
            assertTrue(lastHarm <= HARMLESS_AFTER, "true K $trueK: harmful answer at $lastHarm, K $k")
            assertTrue(if (trueK > 1) k >= 1 else k <= 1, "true K $trueK: K moved the wrong way to $k")
        }
    } }

    private fun bandOf(depletion: Double) = when {
        depletion < Coefficients.BAND_TOO_WET_MAX -> Moisture.TOO_WET
        depletion < Coefficients.BAND_OK_MAX -> Moisture.OK
        depletion < Coefficients.BAND_DRY_MAX -> Moisture.DRY
        else -> Moisture.BONE_DRY
    }

    @Test
    fun theEngineIsDeterministic() { runBlocking {
        checkAll(arbPlant, Arb.list(arbDay, 1..60), Arb.list(Arb.element(Moisture.entries), 0..10)) { plant, days, answers ->
            fun run(): Pair<EngineState, List<Action>> {
                var s = Engine.initial(plant)
                val actions = ArrayList<Action>()
                days.forEachIndexed { i, day ->
                    actions += Engine.recommend(plant, s, days.drop(i)).action
                    answers.getOrNull(i)?.let { s = Engine.apply(plant, s, Event.CheckIn(day.date, it)) }
                    s = Engine.advance(plant, s, day)
                }
                return s to actions
            }
            assertEquals(run(), run())
        }
    } }
}
