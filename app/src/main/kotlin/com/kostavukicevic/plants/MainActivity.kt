// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.kostavukicevic.plants.data.ThemeChoice
import com.kostavukicevic.plants.ui.AddPlantWizard
import com.kostavukicevic.plants.ui.EditPlaceScreen
import com.kostavukicevic.plants.ui.LocalScreenIsTop
import com.kostavukicevic.plants.ui.LocalScreenTransition
import com.kostavukicevic.plants.ui.LocalSharedTransition
import com.kostavukicevic.plants.ui.PlacesScreen
import com.kostavukicevic.plants.ui.PlantScreen
import com.kostavukicevic.plants.ui.PlantsScreen
import com.kostavukicevic.plants.ui.SettingsScreen
import com.kostavukicevic.plants.ui.TodayScreen
import com.kostavukicevic.plants.ui.UndoDelete
import com.kostavukicevic.plants.ui.theme.Motion
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.Type
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openPlant = intent.getLongExtra(EXTRA_PLANT, -1L).takeIf { it > 0 }
        setContent { ChosenTheme { App(openPlant) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The app is in the foreground: the only time MET Norway may be asked, if it is needed.
        lifecycleScope.launch {
            val g = graph
            g.scheduler.ensureDailyRefresh()
            g.refreshWeather(foreground = true)
            g.scheduler.reschedule()
            g.changed()
        }
    }

    companion object {
        const val EXTRA_PLANT = "plant"
    }
}

/**
 * The theme chosen in Settings, applied as soon as it changes. Nothing is drawn until the choice is read, so a
 * chosen theme does not flash the system's one first.
 */
@Composable
internal fun ChosenTheme(content: @Composable () -> Unit) {
    val choice by LocalContext.current.graph.settings.theme.collectAsState(null)
    val system = isSystemInDarkTheme()
    val dark = when (choice ?: return) {
        ThemeChoice.SYSTEM -> system
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    // The system bar icons follow the app's theme, not the system's, so they stay readable on a chosen theme.
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }
    OvergrownTheme(dark = dark, content = content)
}

// The scrims enableEdgeToEdge uses by default behind three-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

sealed interface Screen {
    data object Today : Screen
    data object Plants : Screen
    data object Places : Screen
    data class Plant(val id: Long) : Screen
    data class EditPlant(val id: Long?) : Screen
    data class EditPlace(val id: Long?) : Screen
    data object Settings : Screen

    fun key(): String = when (this) {
        Today -> "today"
        Plants -> "plants"
        Places -> "places"
        is Plant -> "plant:$id"
        is EditPlant -> "editplant:${id ?: 0}"
        is EditPlace -> "editplace:${id ?: 0}"
        Settings -> "settings"
    }

    companion object {
        fun parse(k: String): Screen {
            val (name, arg) = (k.split(':') + "0").let { it[0] to it[1].toLong() }
            return when (name) {
                "plants" -> Plants
                "places" -> Places
                "plant" -> Plant(arg)
                "editplant" -> EditPlant(arg.takeIf { it > 0 })
                "editplace" -> EditPlace(arg.takeIf { it > 0 })
                "settings" -> Settings
                else -> Today
            }
        }
    }
}

/**
 * One back stack. Today is always at the bottom and the Plants tab is Plants right above it, so system back
 * from Plants lands on Today and back from Today leaves the app.
 *
 * Screens change with the transition chosen in Settings (D44). The shown screen follows the top of the stack
 * through a seekable transition, so the predictive back gesture can drag the pop with the finger.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun App(openPlant: Long?) {
    val stack = rememberSaveable(
        saver = listSaver(save = { s -> s.map { it.key() } }, restore = { keys -> mutableStateListOf(*keys.map(Screen::parse).toTypedArray()) }),
    ) { mutableStateListOf<Screen>(Screen.Today).also { if (openPlant != null) it += Screen.Plant(openPlant) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = remember { UndoDelete(snackbar, scope) }
    val context = LocalContext.current
    // With "Remove animations" on, the animator duration scale is 0 and screens change at once. It can change while
    // the app is away, so it is read again on each return.
    fun animationsOff() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var instant by remember { mutableStateOf(animationsOff()) }
    LifecycleResumeEffect(Unit) {
        instant = animationsOff()
        onPauseOrDispose {}
    }
    val top = stack.last()
    val shown = remember { SeekableTransitionState(top) }

    // With animations removed the new screen shows in the frame the stack changes: snapping in the effect below would
    // leave the old one drawn for a frame.
    fun snapIfInstant() { if (instant) scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.snapTo(stack.last()) } }

    // A screen's own buttons act only while it is the top: a second tap on a card that is already leaving does nothing.
    fun go(from: Screen, to: Screen) { if (stack.last() == from) stack += to; snapIfInstant() }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex); snapIfInstant() }
    fun back(from: Screen) { if (stack.last() == from) back() }

    LaunchedEffect(top) { if (instant) shown.snapTo(top) else shown.animateTo(top) }
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val to = stack[stack.lastIndex - 1]
        try {
            progress.collect { if (!instant) shown.seekTo(it.progress, to) }
            back()
        } catch (e: CancellationException) {
            // The gesture was cancelled: play the pop back to where the finger started, then rest on the top screen.
            scope.launch {
                val from0 = shown.fraction
                animate(from0, 0f, animationSpec = tween((from0 * Motion.MEDIUM2).toInt(), easing = LinearEasing)) { f, _ ->
                    scope.launch { shown.seekTo(f) }
                }
                shown.snapTo(stack.last())
            }
            throw e
        }
    }

    var tabsHeight by remember { mutableIntStateOf(0) }
    // The tabs belong to the two tab screens and move with them; a pushed screen covers them.
    val screenContent: @Composable (Screen) -> Unit = { screen ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).then(if (screen.isTab) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                when (screen) {
                    Screen.Today -> TodayScreen(
                        onPlant = { go(screen, Screen.Plant(it)) },
                        onAdd = { go(screen, Screen.EditPlant(null)) },
                        onPlaces = { go(screen, Screen.Places) },
                        onSettings = { go(screen, Screen.Settings) },
                    )
                    Screen.Plants -> PlantsScreen(
                        undo,
                        onPlant = { go(screen, Screen.Plant(it)) },
                        onEdit = { go(screen, Screen.EditPlant(it)) },
                        onAdd = { go(screen, Screen.EditPlant(null)) },
                        onPlaces = { go(screen, Screen.Places) },
                        onSettings = { go(screen, Screen.Settings) },
                    )
                    Screen.Places -> PlacesScreen(
                        undo,
                        onBack = { back(screen) },
                        onEdit = { go(screen, Screen.EditPlace(it)) },
                        onAdd = { go(screen, Screen.EditPlace(null)) },
                    )
                    is Screen.Plant -> PlantScreen(screen.id, onBack = { back(screen) }, onEdit = { go(screen, Screen.EditPlant(screen.id)) })
                    is Screen.EditPlant -> AddPlantWizard(screen.id, onDone = { back(screen) })
                    is Screen.EditPlace -> EditPlaceScreen(screen.id, onDone = { back(screen) })
                    Screen.Settings -> SettingsScreen(onBack = { back(screen) })
                }
            }
            if (screen.isTab) {
                Tabs(
                    plants = screen == Screen.Plants,
                    onToday = { if (stack.last() == screen) stack.removeRange(1, stack.size); snapIfInstant() },
                    onPlants = { if (screen != Screen.Plants) go(screen, Screen.Plants) },
                    modifier = Modifier.onSizeChanged { tabsHeight = it.height },
                )
            }
        }
    }

    Scaffold(
        // The screens inside draw their own top bar and status-bar inset.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            // Above the tabs, where the Scaffold put it when the tabs were its bottom bar, and as then its shadow
            // stops at their edge.
            val aboveTabs = if (top.isTab) Modifier.offset { IntOffset(0, -tabsHeight) }.drawWithContent {
                clipRect(left = -size.width, top = -size.height, right = size.width * 2, bottom = size.height) { this@drawWithContent.drawContent() }
            } else Modifier
            SnackbarHost(snackbar, aboveTabs) {
                Snackbar(it, containerColor = Overgrown.colors.ink, contentColor = Overgrown.colors.onInk, actionColor = Overgrown.colors.onInk)
            }
        },
    ) { padding ->
        // One tree whether or not animations are removed, so turning them off while the app is away keeps each
        // screen's state; with them off the specs are empty and the transition snaps.
        SharedTransitionLayout(Modifier.padding(padding).consumeWindowInsets(padding)) {
            val screens = rememberTransition(shown, label = "screens")
            // Today and Plants show the same plant under the same keys; switching tabs is a plain crossfade.
            val tabSwitch = screens.currentState != screens.targetState && screens.currentState.isTab && screens.targetState.isTab
            screens.AnimatedContent(
                transitionSpec = {
                    val i = stack.indexOf(initialState)
                    val t = stack.indexOf(targetState)
                    if (instant) EnterTransition.None togetherWith ExitTransition.None
                    else screenTransition(initialState, targetState, pop = i == -1 || (t != -1 && t < i))
                },
                contentKey = { it.key() },
            ) { screen ->
                CompositionLocalProvider(
                    LocalSharedTransition provides this@SharedTransitionLayout.takeIf { !instant && !tabSwitch },
                    LocalScreenTransition provides this,
                    LocalScreenIsTop provides (screen == top),
                ) { screenContent(screen) }
            }
        }
    }
}

private val Screen.isTab get() = this == Screen.Today || this == Screen.Plants

/**
 * The motion from [from] to [to] (D44, D45). Switching tabs is a short crossfade. Otherwise a push, or a [pop] with
 * the screens' roles reversed: into or out of the plant screen from a list the two screens fade while the plant's
 * card, name and glyph grow into the plant screen; any other screen slides in from the right over the old one,
 * which shifts a little left.
 */
private fun screenTransition(from: Screen, to: Screen, pop: Boolean): ContentTransform {
    if (from.isTab && to.isTab) {
        // The new tab fades in over the old one, which stays opaque until it goes: two half-faded screens would let
        // the background through and dim the tabs in the middle.
        return fadeIn(tween(Motion.SHORT4, easing = Motion.Emphasized)) togetherWith fadeOut(tween(0, delayMillis = Motion.SHORT4))
    }
    val plantFromList = (from is Screen.Plant && to.isTab) || (to is Screen.Plant && from.isTab)
    val t = if (plantFromList) {
        fadeIn(tween(Motion.MEDIUM2, easing = Motion.Emphasized)) togetherWith fadeOut(tween(Motion.MEDIUM2, easing = Motion.Emphasized))
    } else {
        val spec = tween<IntOffset>(Motion.MEDIUM2, easing = Motion.Emphasized)
        if (pop) {
            slideInHorizontally(spec) { -(it * SLIDE_UNDER).toInt() } togetherWith slideOutHorizontally(spec) { it }
        } else {
            slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -(it * SLIDE_UNDER).toInt() }
        }
    }
    // The leaving screen of a pop stays on top as it goes.
    return if (pop) t.apply { targetContentZIndex = -1f } else t
}

/** How far the screen underneath a slide shifts left, as a share of its width. */
private const val SLIDE_UNDER = 0.2f

@Composable
private fun Tabs(plants: Boolean, onToday: () -> Unit, onPlants: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Overgrown.colors.ground).navigationBarsPadding()
            .padding(Spacing.grid * 2).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
    ) {
        Tab(stringResource(R.string.today_title), selected = !plants, onToday)
        Tab(stringResource(R.string.plants_title), selected = plants, onPlants)
    }
}

@Composable
private fun RowScope.Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Overgrown.colors
    Box(
        Modifier.weight(1f).heightIn(min = Spacing.minTouchTarget).clip(RoundedCornerShape(Radius.button))
            .background(if (selected) c.ink else c.surface)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.label, color = if (selected) c.onInk else c.ink) }
}
