// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PotGaugeTest {

    val compose = createComposeRule()

    // The app's manifest does not declare the empty activity the compose rule launches; register it first.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(compose)

    @Test
    fun percentIsClampedAndRounded() {
        assertEquals(0, waterLeftPercent(-0.2f))
        assertEquals(0, waterLeftPercent(Float.NaN))
        assertEquals(0, waterLeftPercent(0f))
        assertEquals(50, waterLeftPercent(0.5f))
        assertEquals(67, waterLeftPercent(0.666f))
        assertEquals(100, waterLeftPercent(1f))
        assertEquals(100, waterLeftPercent(1.7f))
    }

    @Test
    fun contentDescriptionSaysWaterLeft() {
        compose.setContent { OvergrownTheme { PotGauge(0.7f, PotGaugeSize.Medium, block = StateBlock.Sky) } }
        compose.onNodeWithContentDescription("Water left: 70 percent").assertExists()
    }

    @Test
    fun contentDescriptionIsClamped() {
        compose.setContent { OvergrownTheme { PotGauge(1.4f, PotGaugeSize.Small) } }
        compose.onNodeWithContentDescription("Water left: 100 percent").assertExists()
    }
}
