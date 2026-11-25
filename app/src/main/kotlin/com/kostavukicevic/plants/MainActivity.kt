// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.lifecycleScope
import com.kostavukicevic.plants.data.ThemeChoice
import com.kostavukicevic.plants.ui.EditPlaceScreen
import com.kostavukicevic.plants.ui.AddPlantWizard
import com.kostavukicevic.plants.ui.PlacesScreen
import com.kostavukicevic.plants.ui.PlantScreen
import com.kostavukicevic.plants.ui.PlantsScreen
import com.kostavukicevic.plants.ui.SettingsScreen
import com.kostavukicevic.plants.ui.TodayScreen
import com.kostavukicevic.plants.ui.UndoDelete
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.Type
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
 */
@Composable
internal fun App(openPlant: Long?) {
    val stack = rememberSaveable(
        saver = listSaver(save = { s -> s.map { it.key() } }, restore = { keys -> mutableStateListOf(*keys.map(Screen::parse).toTypedArray()) }),
    ) { mutableStateListOf<Screen>(Screen.Today).also { if (openPlant != null) it += Screen.Plant(openPlant) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = remember { UndoDelete(snackbar, scope) }

    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    val top = stack.last()
    Scaffold(
        // The screens inside draw their own top bar and status-bar inset.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbar) {
                Snackbar(it, containerColor = Overgrown.colors.ink, contentColor = Overgrown.colors.onInk, actionColor = Overgrown.colors.onInk)
            }
        },
        bottomBar = {
            if (top == Screen.Today || top == Screen.Plants) {
                Tabs(
                    plants = top == Screen.Plants,
                    onToday = { stack.removeRange(1, stack.size) },
                    onPlants = { if (top != Screen.Plants) go(Screen.Plants) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (top) {
                Screen.Today -> TodayScreen(
                    onPlant = { go(Screen.Plant(it)) },
                    onAdd = { go(Screen.EditPlant(null)) },
                    onPlaces = { go(Screen.Places) },
                    onSettings = { go(Screen.Settings) },
                )
                Screen.Plants -> PlantsScreen(
                    undo,
                    onPlant = { go(Screen.Plant(it)) },
                    onEdit = { go(Screen.EditPlant(it)) },
                    onAdd = { go(Screen.EditPlant(null)) },
                    onPlaces = { go(Screen.Places) },
                    onSettings = { go(Screen.Settings) },
                )
                Screen.Places -> PlacesScreen(undo, onBack = ::back, onEdit = { go(Screen.EditPlace(it)) }, onAdd = { go(Screen.EditPlace(null)) })
                is Screen.Plant -> PlantScreen(top.id, onBack = ::back, onEdit = { go(Screen.EditPlant(top.id)) })
                is Screen.EditPlant -> AddPlantWizard(top.id, onDone = ::back)
                is Screen.EditPlace -> EditPlaceScreen(top.id, onDone = ::back)
                Screen.Settings -> SettingsScreen(onBack = ::back)
            }
        }
    }
}

@Composable
private fun Tabs(plants: Boolean, onToday: () -> Unit, onPlants: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Overgrown.colors.surface).navigationBarsPadding()
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
