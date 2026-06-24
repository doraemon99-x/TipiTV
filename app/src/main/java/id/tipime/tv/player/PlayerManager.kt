package id.tipime.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import id.tipime.tv.data.model.Channel
import id.tipime.tv.data.model.DrmLicense
import id.tipime.tv.data.model.Playlist
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "PlayerManager"

@UnstableApi
class PlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentPlaylist: Playlist? = null
    private var retryCount = 0
    private val maxRetries = 5
    private val handler = Handler(Looper.getMainLooper())

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun attachView(playerView: PlayerView) {
        playerView.player = exoPlayer
        playerView.keepScreenOn = true
    }

    fun play(channel: Channel, playlist: Playlist) {
        currentChannel = channel
        currentPlaylist = playlist
        retryCount = 0
        startPlayback(channel, playlist)
    }

    private fun startPlayback(channel: Channel, playlist: Playlist) {
        releasePlayerOnly()

        val drm = playlist.findDrmLicense(channel.drmId)
        val dataSourceFactory = buildDataSourceFactory(channel)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("id")
                    .setPreferredTextLanguage("id")
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Buffer besar untuk IPTV live - mirip OTT Navigator
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // min buffer sebelum start playback
                60_000,   // max buffer yang disimpan
                2_000,    // min buffer setelah rebuffer untuk mulai lagi
                4_000     // min buffer setelah rebuffer untuk playback ready
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSource = buildMediaSource(channel, drm, dataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player ->
                player.addListener(playerListener)
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
            }
    }

    private fun buildMediaSource(
        channel: Channel,
        drm: DrmLicense?,
        dataSourceFactory: OkHttpDataSource.Factory
    ): MediaSource {
        val url = channel.streamUrl
        val mediaItem = buildMediaItem(channel, drm)

        return when {
            url.contains(".mpd", true) ||
            url.contains("/dash/", true) ||
            url.contains("manifest_type=mpd", true) ||
            url.contains("manifest_type=dash", true) -> {
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            url.contains(".m3u8", true) ||
            url.contains("/hls/", true) ||
            url.contains("manifest_type=hls", true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            }
            url.startsWith("rtsp://", true) -> {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
            }
        }
    }

    private fun buildMediaItem(channel: Channel, drm: DrmLicense?): MediaItem {
        val builder = MediaItem.Builder().setUri(channel.streamUrl)

        if (drm != null && drm.drmKey.isNotBlank()) {
            when (drm.drmType.trim().lowercase()) {
                "widevine", "com.widevine.alpha" -> {
                    val headers = mutableMapOf<String, String>()
                    drm.drmHeaders?.let { headers.putAll(it) }
                    channel.referer?.let { headers["Referer"] = it }
                    channel.origin?.let { headers["Origin"] = it }
                    channel.userAgent?.let { headers["User-Agent"] = it }

                    builder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(Uri.parse(drm.drmKey))
                            .setLicenseRequestHeaders(headers)
                            .setMultiSession(false)
                            .build()
                    )
                }

                "clearkey", "clear_key", "clear-key" -> {
                    // Support format "KID:KEY" (hex) maupun JSON ClearKey
                    val licenseUri = buildClearKeyLicenseUri(drm.drmKey)
                    if (licenseUri != null) {
                        builder.setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                                .setLicenseUri(licenseUri)
                                .build()
                        )
                    } else {
                        Log.w(TAG, "ClearKey format tidak valid: ${drm.drmKey}")
                    }
                }

                "playready" -> {
                    builder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.PLAYREADY_UUID)
                            .setLicenseUri(drm.drmKey)
                            .build()
                    )
                }

                else -> Log.w(TAG, "DRM type tidak dikenali: ${drm.drmType}")
            }
        }

        return builder.build()
    }

    // Build ClearKey license URI dari format "KID:KEY" hex
    private fun buildClearKeyLicenseUri(drmKey: String): String? {
        return try {
            // Bisa multi-key: "kid1:key1,kid2:key2"
            val pairs = drmKey.split(",").mapNotNull { pair ->
                val parts = pair.trim().split(":")
                if (parts.size < 2) return@mapNotNull null
                val kid = parts[0].trim().hexToBase64Url()
                val key = parts[1].trim().hexToBase64Url()
                JSONObject().apply {
                    put("kty", "oct")
                    put("kid", kid)
                    put("k", key)
                }
            }
            if (pairs.isEmpty()) return null

            val json = JSONObject().apply {
                put("keys", JSONArray(pairs))
                put("type", "temporary")
            }

            "data:application/json;base64," +
                Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "buildClearKeyLicenseUri error", e)
            null
        }
    }

    private fun buildDataSourceFactory(channel: Channel): OkHttpDataSource.Factory {
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { headers["Referer"] = it }
        channel.origin?.let { headers["Origin"] = it }

        return OkHttpDataSource.Factory(httpClient).apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> onBuffering?.invoke(true)
                Player.STATE_READY -> {
                    retryCount = 0
                    onBuffering?.invoke(false)
                    onReady?.invoke()
                }
                Player.STATE_ENDED -> {
                    // Live stream ended → seek to live edge dan retry
                    onBuffering?.invoke(false)
                    exoPlayer?.seekToDefaultPosition()
                    exoPlayer?.prepare()
                }
                Player.STATE_IDLE -> onBuffering?.invoke(false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error [${error.errorCode}]: ${error.errorCodeName}", error)
            onBuffering?.invoke(false)

            // BEHIND_LIVE_WINDOW: seek ke live edge dulu baru prepare
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                Log.w(TAG, "Behind live window → seek to default position")
                exoPlayer?.seekToDefaultPosition()
                exoPlayer?.prepare()
                return
            }

            if (retryCount < maxRetries && isRetryable(error)) {
                retryCount++
                val delayMs = (retryCount * 2000L).coerceAtMost(10_000L)
                Log.w(TAG, "Retry $retryCount/$maxRetries in ${delayMs}ms")

                handler.postDelayed({
                    val ch = currentChannel
                    val pl = currentPlaylist
                    if (ch != null && pl != null) {
                        // Rebuild full media source, bukan hanya prepare()
                        startPlayback(ch, pl)
                    }
                }, delayMs)
                return
            }

            onError?.invoke(getReadableError(error))
        }
    }

    private fun isRetryable(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            else -> false
        }
    }

    private fun getReadableError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                "DRM error: lisensi tidak dapat diperoleh"
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
                "DRM error: perangkat tidak didukung"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Koneksi gagal. Periksa internet atau server stream."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Koneksi ke stream terlalu lama."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Stream menolak akses (HTTP error). Periksa URL atau header."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "Format stream tidak didukung perangkat ini."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "Manifest stream tidak valid atau corrupt."
            else -> "Error: ${error.errorCodeName}"
        }
    }

    // Track selection helpers
    fun setAutoQuality() {
        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
            ?.buildUpon()?.clearOverridesOfType(C.TRACK_TYPE_VIDEO)?.build() ?: return
    }

    fun setMaxVideoHeight(maxHeight: Int) {
        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
            ?.buildUpon()?.setMaxVideoSize(Int.MAX_VALUE, maxHeight)?.build() ?: return
    }

    fun setPreferredAudioLanguage(languageCode: String?) {
        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
            ?.buildUpon()?.setPreferredAudioLanguage(languageCode)?.build() ?: return
    }

    fun pause() { exoPlayer?.pause() }
    fun resume() { exoPlayer?.play() }
    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    private fun releasePlayerOnly() {
        handler.removeCallbacksAndMessages(null)
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
    }

    fun release() {
        releasePlayerOnly()
        currentChannel = null
        currentPlaylist = null
        retryCount = 0
    }

    companion object {
        private fun String.hexToBase64Url(): String {
            val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return Base64.encodeToString(
                bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }
    }
}
