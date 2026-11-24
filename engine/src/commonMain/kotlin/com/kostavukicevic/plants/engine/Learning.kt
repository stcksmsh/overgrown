// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients as C
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Belief about one plant's correction factor K, the multiplier on predicted use, held as the
 * mean and variance of log K.
 *
 * [pendingIncrease] and [pendingDecrease] count answers asking for a correction in the slow
 * direction, which needs two consistent answers before it is applied.
 */
data class KBelief(
    val logKMean: Double = C.LOGK_INITIAL_MEAN,
    val logKVariance: Double = C.LOGK_INITIAL_SD * C.LOGK_INITIAL_SD,
    val pendingIncrease: Int = 0,
    val pendingDecrease: Int = 0,
) {
    val k: Double get() = exp(logKMean)
    val sd: Double get() = sqrt(logKVariance)
}

/** The result of one answer: the new belief about K, and what the soil's depletion now is believed to be. */
data class AnswerUpdate(val belief: KBelief, val depletion: Double)

/**
 * Bayesian update of log K from a soil-moisture answer. Shared by the engine and the baseline so
 * the comparison measures the model, not the learning.
 *
 * An answer says the true depletion lies in a band. If the model predicted depletion d, the
 * true depletion is about d x exp(delta), where delta is the error in log K. The answer therefore
 * says delta lies in [ln(low/d), ln(high/d)], blurred by the person's own error (observation
 * noise). The belief about delta, a normal with the current variance, is multiplied by that
 * likelihood and summarised again by its mean and variance (moment matching), by quadrature.
 *
 * Compared with moving to the nearest band edge, this keeps learning when the prediction sits
 * at a band edge, moves part-way into the band rather than stopping at its edge, and never
 * becomes more confident than the bands allow. See docs/DECISIONS.md D18.
 */
object Learning {

    private val maxVariance = C.LOGK_INITIAL_SD * C.LOGK_INITIAL_SD
    private val logKMin = ln(C.K_MIN)
    private val logKMax = ln(C.K_MAX)

    /**
     * Depletion band for an answer, as [low, high]. A band reaching 1.0 ("Bone dry", and the quick
     * "Dry" that covers Dry and Bone dry) is open above: an empty pot hides how long it has been empty.
     */
    fun band(moisture: Moisture): ClosedFloatingPointRange<Double> = when (moisture) {
        Moisture.TOO_WET -> 0.0..C.BAND_TOO_WET_MAX
        Moisture.OK -> C.BAND_TOO_WET_MAX..C.BAND_OK_MAX
        Moisture.DRY -> C.BAND_OK_MAX..C.BAND_DRY_MAX
        Moisture.BONE_DRY -> C.BAND_DRY_MAX..1.0
        Moisture.DRY_OR_BONE_DRY -> C.BAND_OK_MAX..1.0
    }

    fun addVariance(b: KBelief, amount: Double): KBelief =
        b.copy(logKVariance = (b.logKVariance + amount).coerceAtMost(maxVariance))

    /**
     * @param drySideFast act on "drier than predicted" at once and wait on "wetter" (drought kills
     *   faster). Otherwise the reverse: "too wet" acts at once.
     * @param fastObservation use the smaller observation noise (an undrained pot reported too wet).
     */
    fun update(
        b: KBelief,
        predictedDepletion: Double,
        moisture: Moisture,
        drySideFast: Boolean,
        fastObservation: Boolean = false,
    ): AnswerUpdate {
        val r = if (fastObservation) C.OBSERVATION_SD_FAST else C.OBSERVATION_SD
        val d = predictedDepletion.coerceAtLeast(C.MIN_PREDICTED_DEPLETION)
        val band = band(moisture)
        val lo = if (band.start <= 0.0) Double.NEGATIVE_INFINITY else ln(band.start / d)
        val hi = if (band.endInclusive >= 1.0) Double.POSITIVE_INFINITY else ln(band.endInclusive / d)
        val inBand = predictedDepletion in band

        var p = b.logKVariance
        var pendingIncrease = b.pendingIncrease
        var pendingDecrease = b.pendingDecrease
        if (!inBand) {
            // Distance from the prediction to the band, in log units.
            val y = if (0.0 < lo) lo else hi
            val surprising = sqrt(p) < C.CONFIDENT_SD || abs(y) > C.SURPRISE_SD * sqrt(p + r * r)
            if (surprising) p = (p + C.VARIANCE_BUMP_SURPRISE).coerceAtMost(maxVariance)

            val increase = y > 0
            val slow = if (drySideFast) !increase else increase
            if (increase) pendingDecrease = 0 else pendingIncrease = 0
            if (slow) {
                val count = (if (increase) pendingIncrease else pendingDecrease) + 1
                if (count < 2) {
                    val waiting = if (increase) b.copy(logKVariance = p, pendingIncrease = count, pendingDecrease = 0)
                    else b.copy(logKVariance = p, pendingDecrease = count, pendingIncrease = 0)
                    // K waits for a second answer, but the soil is as the person says.
                    return AnswerUpdate(waiting, predictedDepletion.coerceIn(band))
                }
            }
            pendingIncrease = 0
            pendingDecrease = 0
        }

        val post = posterior(p, lo, hi, r, d)
        if (inBand) {
            // The answer agrees: K stays (brief 5.7), and confidence grows only as far as the
            // band's width allows. Moving K towards the band's middle on every agreeing answer
            // makes it creep until the plant is over-watered (found by the property tests).
            return AnswerUpdate(b.copy(logKVariance = post.variance.coerceIn(1e-6, p)), predictedDepletion)
        }
        val belief = KBelief(
            logKMean = (b.logKMean + post.mean).coerceIn(logKMin, logKMax),
            logKVariance = post.variance.coerceIn(1e-6, maxVariance),
            pendingIncrease = pendingIncrease,
            pendingDecrease = pendingDecrease,
        )
        return AnswerUpdate(belief, post.depletion.coerceIn(band))
    }

    private class Posterior(val mean: Double, val variance: Double, val depletion: Double)

    /** Moments of N(0, p) x likelihood(delta in [lo, hi] with noise r), and the implied depletion. */
    private fun posterior(p: Double, lo: Double, hi: Double, r: Double, d: Double): Posterior {
        val sd = sqrt(p)
        val n = 801
        val span = 6 * sd
        var w = 0.0; var m1 = 0.0; var m2 = 0.0; var dep = 0.0
        for (i in 0 until n) {
            val x = -span + 2 * span * i / (n - 1)
            val prior = exp(-0.5 * x * x / p)
            val upper = if (hi == Double.POSITIVE_INFINITY) 1.0 else phi((hi - x) / r)
            val lower = if (lo == Double.NEGATIVE_INFINITY) 0.0 else phi((lo - x) / r)
            val weight = prior * (upper - lower)
            w += weight; m1 += weight * x; m2 += weight * x * x; dep += weight * d * exp(x)
        }
        if (w <= 0.0) return Posterior(0.0, p, d)
        val mean = m1 / w
        return Posterior(mean, (m2 / w - mean * mean).coerceAtLeast(0.0), dep / w)
    }

    /** Standard normal CDF. */
    private fun phi(z: Double): Double = 0.5 * (1 + erf(z / sqrt(2.0)))

    /** Abramowitz and Stegun 7.1.26; absolute error below 1.5e-7. */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }
}
