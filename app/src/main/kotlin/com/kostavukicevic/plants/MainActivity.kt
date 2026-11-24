// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.lifecycleScope
import com.kostavukicevic.plants.ui.EditPlaceScreen
import com.kostavukicevic.plants.ui.EditPlantScreen
import com.kostavukicevic.plants.ui.PlantScreen
import com.kostavukicevic.plants.ui.SettingsScreen
import com.kostavukicevic.plants.ui.TodayScreen
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openPlant = intent.getLongExtra(EXTRA_PLANT, -1L).takeIf { it > 0 }
        setContent { OvergrownTheme { App(openPlant) } }
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
            g.garden.refreshWeather(foreground = true)
            g.scheduler.reschedule()
            g.changed()
        }
    }

    companion object {
        const val EXTRA_PLANT = "plant"
    }
}

sealed interface Screen {
    data object Today : Screen
    data class Plant(val id: Long) : Screen
    data class EditPlant(val id: Long?) : Screen
    data class EditPlace(val id: Long?) : Screen
    data object Settings : Screen

    fun key(): String = when (this) {
        Today -> "today"
        is Plant -> "plant:$id"
        is EditPlant -> "editplant:${id ?: 0}"
        is EditPlace -> "editplace:${id ?: 0}"
        Settings -> "settings"
    }

    companion object {
        fun parse(k: String): Screen {
            val (name, arg) = (k.split(':') + "0").let { it[0] to it[1].toLong() }
            return when (name) {
                "plant" -> Plant(arg)
                "editplant" -> EditPlant(arg.takeIf { it > 0 })
                "editplace" -> EditPlace(arg.takeIf { it > 0 })
                "settings" -> Settings
                else -> Today
            }
        }
    }
}

@Composable
private fun App(openPlant: Long?) {
    val stack = rememberSaveable(
        saver = listSaver(save = { s -> s.map { it.key() } }, restore = { keys -> mutableStateListOf(*keys.map(Screen::parse).toTypedArray()) }),
    ) { mutableStateListOf<Screen>(Screen.Today).also { if (openPlant != null) it += Screen.Plant(openPlant) } }

    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    when (val s = stack.last()) {
        Screen.Today -> TodayScreen(onPlant = { go(Screen.Plant(it)) }, onAdd = { go(Screen.EditPlant(null)) }, onSettings = { go(Screen.Settings) })
        is Screen.Plant -> PlantScreen(s.id, onBack = ::back, onEdit = { go(Screen.EditPlant(s.id)) })
        is Screen.EditPlant -> EditPlantScreen(s.id, onDone = ::back, onNewPlace = { go(Screen.EditPlace(null)) })
        is Screen.EditPlace -> EditPlaceScreen(s.id, onDone = ::back)
        Screen.Settings -> SettingsScreen(onBack = ::back)
    }
}
