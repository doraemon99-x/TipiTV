package id.tipime.tv.util

import android.content.Context
import androidx.preference.PreferenceManager

class Prefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var playlistSource: String
        get() = prefs.getString(KEY_PLAYLIST_SOURCE, DEFAULT_PLAYLIST) ?: DEFAULT_PLAYLIST
        set(v) = prefs.edit().putString(KEY_PLAYLIST_SOURCE, v).apply()

    var lastCategoryIndex: Int
        get() = prefs.getInt(KEY_LAST_CATEGORY, 0)
        set(v) = prefs.edit().putInt(KEY_LAST_CATEGORY, v).apply()

    var lastChannelIndex: Int
        get() = prefs.getInt(KEY_LAST_CHANNEL, 0)
        set(v) = prefs.edit().putInt(KEY_LAST_CHANNEL, v).apply()

    var autoPlayOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BOOT, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_BOOT, v).apply()

    companion object {
        const val KEY_PLAYLIST_SOURCE = "playlist_source"
        const val KEY_LAST_CATEGORY = "last_category"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_AUTO_BOOT = "auto_boot"
        const val DEFAULT_PLAYLIST = "playlist.json"
    }
}
