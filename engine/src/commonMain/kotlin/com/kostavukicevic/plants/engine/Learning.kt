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
 * [pendingIncrease] and [pendingDecrease] count consecutive answers asking for a correction in
 * the slow direction, which needs two consistent answers before it is applied.
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

/** Scalar Kalman-style filter on log K. Shared by the engine and the baseline so the comparison measures the model, not the learning. */
object Learning {

    private val maxVariance = C.LOGK_INITIAL_SD * C.LOGK_INITIAL_SD
    private val logKMin = ln(C.K_MIN)
    private val logKMax = ln(C.K_MAX)

    /** Depletion band for an answer, as [low, high]. */
    fun band(moisture: Moisture): ClosedFloatingPointRange<Double> = when (moisture) {
        Moisture.TOO_WET -> 0.0..C.BAND_TOO_WET_MAX
        Moisture.OK -> C.BAND_TOO_WET_MAX..C.BAND_OK_MAX
        Moisture.DRY -> C.BAND_OK_MAX..C.BAND_DRY_MAX
        Moisture.BONE_DRY -> C.BAND_DRY_MAX..1.0
    }

    fun addVariance(b: KBelief, amount: Double): KBelief =
        b.copy(logKVariance = (b.logKVariance + amount).coerceAtMost(maxVariance))

    /** The answer's depletion as the engine should now believe it: the prediction if inside the band, else the nearest edge. */
    fun observedDepletion(predicted: Double, moisture: Moisture): Double = predicted.coerceIn(band(moisture))

    /**
     * Update on a check-in answer, given the depletion the model predicted at that moment.
     *
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
    ): KBelief {
        val r = if (fastObservation) C.OBSERVATION_SD_FAST else C.OBSERVATION_SD
        val r2 = r * r

        val edge = observedDepletion(predictedDepletion, moisture)
        if (edge == predictedDepletion) {
            // The answer agrees with the prediction: K stays where it is, but the agreement is
            // evidence, so confidence grows (an innovation of zero in the same filter).
            // A neutral answer does not cancel a pending correction; only an opposite one does.
            return b.copy(logKVariance = b.logKVariance * r2 / (b.logKVariance + r2))
        }

        val y = ln(edge / predictedDepletion.coerceAtLeast(C.MIN_PREDICTED_DEPLETION))

        // Surprising: any disagreement while the engine was confident, or a large one at any time.
        // Without this, agreeing answers would shrink the variance until real errors could no
        // longer move K. The nearest-edge innovation understates the true error, so the first
        // test matters more than the second.
        var p = b.logKVariance
        val surprising = sqrt(p) < C.CONFIDENT_SD || abs(y) > C.SURPRISE_SD * sqrt(p + r2)
        if (surprising) p = (p + C.VARIANCE_BUMP_SURPRISE).coerceAtMost(maxVariance)

        val increase = y > 0
        val slow = if (drySideFast) !increase else increase
        if (slow) {
            val count = (if (increase) b.pendingIncrease else b.pendingDecrease) + 1
            if (count < 2) {
                return if (increase) b.copy(logKVariance = p, pendingIncrease = count, pendingDecrease = 0)
                else b.copy(logKVariance = p, pendingDecrease = count, pendingIncrease = 0)
            }
        }

        val gain = p / (p + r2)
        return KBelief(
            logKMean = (b.logKMean + gain * y).coerceIn(logKMin, logKMax),
            logKVariance = (1 - gain) * p,
        )
    }
}
