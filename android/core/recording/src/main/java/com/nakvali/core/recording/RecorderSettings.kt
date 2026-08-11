package com.nakvali.core.recording

import android.content.Context
import android.content.SharedPreferences

/** Shared preference contract for recorder behavior and opt-in field tools. */
object RecorderSettings {
    const val PREFERENCES_NAME = "recorder_settings"
    const val OFFLINE_MODE = "offline_mode"
    const val SENSOR_DIAGNOSTICS = "sensor_diagnostics"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val DEVELOPER_MODE = "developer_mode"

    /** Gate vibration for live segment runs. On unless the rider turns it off. */
    const val SEGMENT_HAPTICS = "segment_haptics"

    /** Lower GPS/IMU rates while live fusion reports a vehicle. On by default. */
    const val TRANSPORT_POWER_SAVE = "transport_power_save"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun developerModeEnabled(context: Context): Boolean =
        preferences(context).getBoolean(DEVELOPER_MODE, false)
}
