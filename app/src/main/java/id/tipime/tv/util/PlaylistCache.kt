package id.tipime.tv.util

import id.tipime.tv.data.model.Playlist

/**
 * In-memory cache supaya PlayerActivity tidak perlu load ulang playlist
 * dari disk/network setiap kali channel diganti.
 */
object PlaylistCache {
    @Volatile
    private var cache: Playlist? = null

    fun set(playlist: Playlist) { cache = playlist }
    fun get(): Playlist? = cache
    fun clear() { cache = null }
}
