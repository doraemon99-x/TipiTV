package id.tipime.tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
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
            setParameters(buildUponParameters().setPreferredAudioLanguage("id"))
        }
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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

    // ── MediaItem builder ─────────────────────────────────────────────────────

    private fun buildMediaItem(channel: Channel, drm: DrmLicense?): MediaItem {
        val builder = MediaItem.Builder().setUri(channel.streamUrl)

        if (drm != null) {
            when {
                drm.isClearKey -> builder.setDrmConfiguration(buildClearKeyDrm(drm))
                drm.isWidevine -> builder.setDrmConfiguration(buildWidevineDrm(channel, drm))
            }
        }

        return builder.build()
    }

    // ClearKey: "KID:KEY" atau "KID1:KEY1,KID2:KEY2"
    private fun buildClearKeyDrm(drm: DrmLicense): DrmConfiguration {
        val pairs = drm.drmKey.split(",").map { it.trim() }
        val keySetId = pairs.joinToString(":") // Media3 handles KID:KEY format

        // Build clearkey JSON license server URI
        // Media3 accepts clearkey:// scheme untuk inline keys
        val clearKeyUri = buildClearKeyUri(pairs)

        return DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(clearKeyUri)
            .setMultiSession(false)
            .build()
    }

    // Build clearkey:// URI dari KID:KEY pairs
    private fun buildClearKeyUri(pairs: List<String>): android.net.Uri {
        // Format: clearkey://keys?kid=HEX&key=HEX
        // Untuk multiple keys gunakan JSON license server inline
        val sb = StringBuilder("data:application/json;charset=UTF-8,")
        sb.append("{\"keys\":[")
        pairs.forEachIndexed { index, pair ->
            val parts = pair.split(":")
            if (parts.size == 2) {
                val kid = hexToBase64Url(parts[0].trim())
                val key = hexToBase64Url(parts[1].trim())
                if (index > 0) sb.append(",")
                sb.append("{\"kty\":\"oct\",\"k\":\"$key\",\"kid\":\"$kid\"}")
            }
        }
        sb.append("],\"type\":\"temporary\"}")
        return android.net.Uri.parse(sb.toString())
    }

    private fun hexToBase64Url(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return android.util.Base64.encodeToString(bytes,
            android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    // Widevine: license server URL
    private fun buildWidevineDrm(channel: Channel, drm: DrmLicense): DrmConfiguration {
        val licenseUri = android.net.Uri.parse(drm.drmKey)
        val headersBuilder = mutableMapOf<String, String>()

        // Tambah headers dari DRM license
        drm.drmHeaders?.forEach { (k, v) -> headersBuilder[k] = v }

        // Tambah headers dari channel
        channel.referer?.let { headersBuilder["Referer"] = it }
        channel.origin?.let { headersBuilder["Origin"] = it }
        channel.userAgent?.let { headersBuilder["User-Agent"] = it }

        return DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(licenseUri)
            .setLicenseRequestHeaders(headersBuilder)
            .setMultiSession(false)
            .build()
    }

    // ── DataSource builder (inject headers per channel) ───────────────────────

    private fun buildDataSourceFactory(channel: Channel): OkHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { headers["Referer"] = it }
        channel.origin?.let { headers["Origin"] = it }

        return OkHttpDataSource.Factory(httpClient).apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
    }

    // ── Player listener ───────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    onBuffering?.invoke(false)
                    onReady?.invoke()
                }
                Player.STATE_BUFFERING -> onBuffering?.invoke(true)
                Player.STATE_ENDED -> Log.d(TAG, "Playback ended")
                Player.STATE_IDLE -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            val msg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                    "DRM error: gagal mendapatkan lisensi"
                PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
                    "DRM error: perangkat tidak didukung (perlu Widevine L1)"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Koneksi gagal, cek internet"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Stream tidak tersedia (HTTP error)"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                    "Format stream tidak didukung"
                else -> "Error: ${error.errorCodeName}"
            }
            onError?.invoke(msg)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun pause() { exoPlayer?.pause() }
    fun resume() { exoPlayer?.play() }
    fun isPlaying() = exoPlayer?.isPlaying == true

    fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
    }
}
