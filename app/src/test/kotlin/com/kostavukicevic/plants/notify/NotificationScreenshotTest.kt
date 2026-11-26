// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.PromptReason
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A watering notification as the system lays it out, expanded, in each voice: the title is the same in both. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class NotificationScreenshotTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.createChannels(app)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    /**
     * The posted notification's expanded layout on a white panel in an activity, where Roborazzi can capture it.
     * The post time is hidden: it is shown relative to the test machine's clock, which would change the image.
     */
    private fun rendered(boris: Boolean): View {
        assertTrue(Notifier.postPrompt(app, 1, "Pothos", Action.WaterNow(700, PromptReason.DRY_BY_MODEL), emptyList(), boris))
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(Notifier.promptId(1))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val panel = FrameLayout(activity)
        val shown = Notification.Builder.recoverBuilder(activity, n).setShowWhen(false).createBigContentView().apply(activity, panel)
        shown.setBackgroundColor(Color.WHITE)
        panel.addView(shown)
        activity.setContentView(panel)
        return shown
    }

    @Test
    fun wateringPromptPlain() = rendered(boris = false).captureRoboImage("src/test/snapshots/notification_water_plain.png", roborazziOptions = exact)

    @Test
    fun wateringPromptBoris() = rendered(boris = true).captureRoboImage("src/test/snapshots/notification_water_boris.png", roborazziOptions = exact)
}
