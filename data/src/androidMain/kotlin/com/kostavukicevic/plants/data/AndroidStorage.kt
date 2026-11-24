// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.Path.Companion.toOkioPath
import java.io.File

/** Storage on the device. The database and settings are included in Auto Backup; see data_extraction_rules.xml. */
object AndroidStorage {
    fun database(context: Context): GardenDb =
        Room.databaseBuilder<GardenDb>(context.applicationContext, context.getDatabasePath(GardenDb.FILE_NAME).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    /** App-private, and excluded from Auto Backup: photos leave the phone only in an export the person starts. */
    fun photos(context: Context): File = File(context.filesDir, "photos")

    fun settings(context: Context): Settings =
        Settings(PreferenceDataStoreFactory.createWithPath { context.filesDir.resolve("settings.preferences_pb").toOkioPath() })
}
