package id.tipime.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
    private var currentChannel: Channel? = null
    private var currentPlaylist: Playlist? = null
    private var retryCount = 0
    private val maxRetries = 3

    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
        val mediaItem = buildMediaItem(channel, drm)
        val dataSourceFactory = buildDataSourceFactory(channel)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("id")
                    .setPreferredTextLanguage("id")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            )
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10_000,
                45_000,
                1_500,
                3_000
            )
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
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
                    onError?.invoke(
                        "ClearKey tidak dikonfigurasi pada build ini. " +
                            "Gunakan endpoint lisensi resmi dari penyedia stream."
                    )
                }

                "playready" -> {
                    onError?.invoke(
                        "PlayReady tidak didukung pada Android ExoPlayer standar."
                    )
                }

                else -> {
                    Log.w(TAG, "DRM type tidak dikenali: ${drm.drmType}")
                }
            }
        }

        return builder.build()
    }

    private fun buildDataSourceFactory(channel: Channel): OkHttpDataSource.Factory {
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
                Player.STATE_BUFFERING -> onBuffering?.invoke(true)

                Player.STATE_READY -> {
                    retryCount = 0
                    onBuffering?.invoke(false)
                    onReady?.invoke()
                }

                Player.STATE_ENDED -> {
                    onBuffering?.invoke(false)
                    Log.d(TAG, "Playback ended")
                }

                Player.STATE_IDLE -> onBuffering?.invoke(false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            onBuffering?.invoke(false)

            if (retryCount < maxRetries && isRetryable(error)) {
                retryCount++

                val channel = currentChannel
                val playlist = currentPlaylist

                if (channel != null && playlist != null) {
                    Log.w(TAG, "Retry playback $retryCount/$maxRetries")
                    exoPlayer?.playWhenReady = false
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = true
                    return
                }
            }

            onError?.invoke(getReadableError(error))
        }
    }

    private fun isRetryable(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true

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
                "Stream menolak akses atau sedang tidak tersedia."

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "Posisi live sudah tertinggal. Coba putar ulang channel."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "Format stream tidak didukung perangkat."

            else -> "Player error: ${error.errorCodeName}"
        }
    }

    fun setAutoQuality() {
        exoPlayer?.trackSelectionParameters =
            exoPlayer?.trackSelectionParameters
                ?.buildUpon()
                ?.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                ?.build()
                ?: return
    }

    fun setMaxVideoHeight(maxHeight: Int) {
        exoPlayer?.trackSelectionParameters =
            exoPlayer?.trackSelectionParameters
                ?.buildUpon()
                ?.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                ?.build()
                ?: return
    }

    fun setPreferredAudioLanguage(languageCode: String?) {
        exoPlayer?.trackSelectionParameters =
            exoPlayer?.trackSelectionParameters
                ?.buildUpon()
                ?.setPreferredAudioLanguage(languageCode)
                ?.build()
                ?: return
    }

    fun setPreferredSubtitleLanguage(languageCode: String?) {
        exoPlayer?.trackSelectionParameters =
            exoPlayer?.trackSelectionParameters
                ?.buildUpon()
                ?.setPreferredTextLanguage(languageCode)
                ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, languageCode.isNullOrBlank())
                ?.build()
                ?: return
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    private fun releasePlayerOnly() {
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
}
