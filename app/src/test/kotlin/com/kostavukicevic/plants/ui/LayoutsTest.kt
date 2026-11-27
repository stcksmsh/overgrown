// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's own layouts under a parent that measures their intrinsic width, which passes them an unbounded width. */
@RunWith(RobolectricTestRunner::class)
class LayoutsTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private fun topOf(text: String) = compose.onNodeWithText(text).getBoundsInRoot().top
    private fun leftOf(text: String) = compose.onNodeWithText(text).getBoundsInRoot().left

    @Test
    fun besideOrUnderPutsBothSideBySideUnderAnIntrinsicWidth() {
        compose.setContent {
            OvergrownTheme { Row(Modifier.width(IntrinsicSize.Max)) { BesideOrUnder(main = { Text("Main") }, trailing = { Text("End") }) } }
        }
        assertEquals(topOf("Main"), topOf("End"))
        assertTrue(leftOf("End") > leftOf("Main"))
    }

    @Test
    fun evenRowsPutsEveryItemInOneRowUnderAnIntrinsicWidth() {
        compose.setContent {
            OvergrownTheme { Row(Modifier.width(IntrinsicSize.Max)) { EvenRows(3, gap = 8.dp) { Text("One"); Text("Two"); Text("Six") } } }
        }
        assertEquals(topOf("One"), topOf("Six"))
        assertTrue(leftOf("Six") > leftOf("Two"))
    }
}
