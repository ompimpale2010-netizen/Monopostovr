package com.example.monopostovrmirror

object Constants {
    const val TARGET_PACKAGE = "com.gabama.monopostolite"

    const val PREFS_NAME = "vr_settings"
    const val KEY_EYE_SEP = "eye_sep"       // 0f..0.1f
    const val KEY_ZOOM = "zoom"             // 0.5f..2.0f
    const val KEY_H_POS = "h_pos"           // -0.3f..0.3f
    const val KEY_V_POS = "v_pos"           // -0.3f..0.3f
    const val KEY_BARREL = "barrel"         // boolean

    const val ACTION_START = "com.example.monopostovrmirror.action.START"
    const val ACTION_STOP = "com.example.monopostovrmirror.action.STOP"
    const val ACTION_UPDATE_SETTINGS = "com.example.monopostovrmirror.action.UPDATE_SETTINGS"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"

    const val NOTIF_CHANNEL_ID = "vr_mirror_channel"
    const val NOTIF_ID = 1001

    const val REQ_OVERLAY_PERMISSION = 5001
    const val REQ_MEDIA_PROJECTION = 5002
}

/**
 * Thin wrapper so MainActivity (writer) and CaptureService (reader) agree on
 * defaults and value ranges without duplicating logic.
 */
class VrSettings(context: android.content.Context) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    var eyeSeparation: Float
        get() = prefs.getFloat(Constants.KEY_EYE_SEP, 0.02f)
        set(v) = prefs.edit().putFloat(Constants.KEY_EYE_SEP, v).apply()

    var zoom: Float
        get() = prefs.getFloat(Constants.KEY_ZOOM, 1.0f)
        set(v) = prefs.edit().putFloat(Constants.KEY_ZOOM, v).apply()

    var horizontalPos: Float
        get() = prefs.getFloat(Constants.KEY_H_POS, 0.0f)
        set(v) = prefs.edit().putFloat(Constants.KEY_H_POS, v).apply()

    var verticalPos: Float
        get() = prefs.getFloat(Constants.KEY_V_POS, 0.0f)
        set(v) = prefs.edit().putFloat(Constants.KEY_V_POS, v).apply()

    var barrelDistortion: Boolean
        get() = prefs.getBoolean(Constants.KEY_BARREL, false)
        set(v) = prefs.edit().putBoolean(Constants.KEY_BARREL, v).apply()
}
