package id.tipime.tv.data.repository

import android.content.Context
import com.google.gson.Gson
import id.tipime.tv.data.model.Playlist
import id.tipime.tv.util.M3uParser
import id.tipime.tv.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PlaylistRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val prefs = Prefs(context)

    suspend fun loadPlaylist(): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val source = prefs.playlistSource
            val content = when {
                source.startsWith("http://") || source.startsWith("https://") -> fetchUrl(source)
                source.startsWith("/") || source.startsWith("file://") -> readFile(source)
                else -> readAsset("playlist.json")
            }
            val playlist = parseContent(content, source)
            Result.success(playlist)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }

    private fun readFile(path: String): String {
        val file = File(path.removePrefix("file://"))
        return file.readText()
    }

    private fun readAsset(name: String): String {
        return context.assets.open(name).bufferedReader().readText()
    }

    private fun parseContent(content: String, source: String): Playlist {
        val trimmed = content.trim()
        return when {
            trimmed.startsWith("#EXTM3U") -> M3uParser.parse(trimmed)
            trimmed.startsWith("{") -> gson.fromJson(trimmed, Playlist::class.java)
            else -> throw Exception("Unknown playlist format")
        }
    }
}
