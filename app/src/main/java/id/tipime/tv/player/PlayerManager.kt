package id.tipime.tv.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import id.tipime.tv.data.model.Channel
import id.tipime.tv.data.model.DrmLicense
import id.tipime.tv.data.model.Playlist
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "PlayerManager"

@UnstableApi
class PlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun attachView(playerView: PlayerView) {
        playerView.player = exoPlayer
    }

    fun play(channel: Channel, playlist: Playlist) {
        release()

        val drm = playlist.findDrmLicense(channel.drmId)
        val mediaItem = buildMediaItem(channel, drm)

        val dataSourceFactory = buildDataSourceFactory(channel)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("id")
            )
        }

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player ->
                player.addListener(playerListener)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
            }
    }

    private fun buildMediaItem(channel: Channel, drm: DrmLicense?): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(channel.streamUrl)

        if (drm != null && drm.drmKey.isNotBlank()) {
            when (drm.drmType.trim().lowercase()) {
                "clearkey", "clear_key", "clear-key" -> {
                    builder.setDrmConfiguration(buildClearKeyDrm(drm))
                }

                "widevine", "com.widevine.alpha" -> {
                    builder.setDrmConfiguration(buildWidevineDrm(channel, drm))
                }

                else -> {
                    Log.w(TAG, "Tipe DRM tidak dikenali: ${drm.drmType}")
                }
            }
        }

        return builder.build()
    }

    private fun buildClearKeyDrm(drm: DrmLicense): DrmConfiguration {
        val pairs = drm.drmKey
            .split(",")
            .map { it.trim() }
            .filter { it.contains(":") }

        val clearKeyUri = buildClearKeyUri(pairs)

        return DrmConfiguration.Builder(C.CLEARKEY_UUID)
            .setLicenseUri(clearKeyUri)
            .setMultiSession(false)
            .build()
    }

    private fun buildClearKeyUri(pairs: List<String>): Uri {
        val keysJson = pairs.mapNotNull { pair ->
            val parts = pair.split(":", limit = 2)

            if (parts.size != 2) {
                null
            } else {
                val kidHex = parts[0].trim()
                val keyHex = parts[1].trim()

                if (kidHex.isBlank() || keyHex.isBlank()) {
                    null
                } else {
                    val kid = hexToBase64Url(kidHex)
                    val key = hexToBase64Url(keyHex)

                    """{"kty":"oct","k":"$key","kid":"$kid"}"""
                }
            }
        }

        val json = """{"keys":[${keysJson.joinToString(",")}],"type":"temporary"}"""

        return Uri.parse(
            "data:application/json;charset=UTF-8," +
                Uri.encode(json)
        )
    }

    private fun hexToBase64Url(hex: String): String {
        val cleanHex = hex
            .replace("0x", "", ignoreCase = true)
            .replace(Regex("[^0-9A-Fa-f]"), "")

        require(cleanHex.length % 2 == 0) {
            "Format hex ClearKey tidak valid"
        }

        val bytes = cleanHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        return Base64.encodeToString(
            bytes,
            Base64.NO_PADDING or
                Base64.URL_SAFE or
                Base64.NO_WRAP
        )
    }

    private fun buildWidevineDrm(
        channel: Channel,
        drm: DrmLicense
    ): DrmConfiguration {
        val headers = mutableMapOf<String, String>()

        drm.drmHeaders?.forEach { (key, value) ->
            headers[key] = value
        }

        channel.referer?.let { headers["Referer"] = it }
        channel.origin?.let { headers["Origin"] = it }
        channel.userAgent?.let { headers["User-Agent"] = it }

        return DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(Uri.parse(drm.drmKey))
            .setLicenseRequestHeaders(headers)
            .setMultiSession(false)
            .build()
    }

    private fun buildDataSourceFactory(
        channel: Channel
    ): OkHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()

        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { headers["Referer"] = it }
        channel.origin?.let { headers["Origin"] = it }

        return OkHttpDataSource.Factory(httpClient).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    onBuffering?.invoke(false)
                    onReady?.invoke()
                }

                Player.STATE_BUFFERING -> {
                    onBuffering?.invoke(true)
                }

                Player.STATE_ENDED -> {
                    Log.d(TAG, "Playback ended")
                }

                Player.STATE_IDLE -> {
                    onBuffering?.invoke(false)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)

            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> {
                    "DRM error: gagal mendapatkan lisensi"
                }

                PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED -> {
                    "DRM error: perangkat tidak didukung"
                }

                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                    "Koneksi gagal, cek internet"
                }

                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    "Stream tidak tersedia (HTTP error)"
                }

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    "Format stream tidak didukung"
                }

                else -> {
                    "Error: ${error.errorCodeName}"
                }
            }

            onBuffering?.invoke(false)
            onError?.invoke(message)
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }

    fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
    }
}
