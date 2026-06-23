package id.tipime.tv.player

import android.content.Context
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import id.tipime.tv.data.model.Channel
import id.tipime.tv.data.model.DrmLicense
import id.tipime.tv.data.model.Playlist
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

@UnstableApi
object PlayerHelper {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun buildPlayer(context: Context): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    fun buildMediaItem(channel: Channel, playlist: Playlist): MediaItem {
        val drm = playlist.findDrmLicense(channel.drmId)
        val url = channel.streamUrl

        val builder = MediaItem.Builder()
            .setUri(url)
            .setMimeType(detectMimeType(url))

        if (drm != null) {
            builder.setDrmConfiguration(buildDrmConfig(drm))
        }

        return builder.build()
    }

    fun buildMediaSource(channel: Channel, playlist: Playlist): MediaSource {
        val drm = playlist.findDrmLicense(channel.drmId)
        val url = channel.streamUrl

        // Build data source factory with custom headers
        val headers = mutableMapOf<String, String>()
        channel.userAgent?.let { headers["User-Agent"] = it }
        channel.referer?.let { headers["Referer"] = it }
        channel.origin?.let { headers["Origin"] = it }

        val dataSourceFactory = if (headers.isNotEmpty()) {
            OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(headers)
        } else {
            OkHttpDataSource.Factory(httpClient)
        }

        val mediaItem = buildMediaItem(channel, playlist)

        return when {
            url.endsWith(".mpd", true) || url.contains("/dash/", true) -> {
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            url.endsWith(".m3u8", true) || url.contains("/hls/", true) -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            url.startsWith("rtsp://", true) -> {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            }
            else -> {
                DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
            }
        }
    }

    private fun buildDrmConfig(drm: DrmLicense): DrmConfiguration {
        return when (drm.drmType.lowercase()) {
            "clearkey" -> buildClearKeyConfig(drm)
            "widevine" -> buildWidevineConfig(drm)
            "playready" -> buildPlayReadyConfig(drm)
            else -> buildWidevineConfig(drm)
        }
    }

    // ClearKey: drmKey format "KID:KEY" (hex)
    private fun buildClearKeyConfig(drm: DrmLicense): DrmConfiguration {
        val parts = drm.drmKey.split(":")
        val kid = parts.getOrNull(0)?.hexToBase64Url() ?: ""
        val key = parts.getOrNull(1)?.hexToBase64Url() ?: ""

        val clearKeyJson = JSONObject().apply {
            put("keys", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("kty", "oct")
                    put("kid", kid)
                    put("k", key)
                })
            })
            put("type", "temporary")
        }

        val licenseUri = "data:application/json;base64," +
            Base64.encodeToString(clearKeyJson.toString().toByteArray(), Base64.NO_WRAP)

        return DrmConfiguration.Builder(C.CLEARKEY_UUID)
            .setLicenseUri(licenseUri)
            .build()
    }

    // Widevine: drmKey is license server URL
    private fun buildWidevineConfig(drm: DrmLicense): DrmConfiguration {
        val builder = DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(drm.drmKey)

        drm.drmHeaders?.let { headers ->
            builder.setLicenseRequestHeaders(headers)
        }

        return builder.build()
    }

    // PlayReady: drmKey is license server URL
    private fun buildPlayReadyConfig(drm: DrmLicense): DrmConfiguration {
        return DrmConfiguration.Builder(C.PLAYREADY_UUID)
            .setLicenseUri(drm.drmKey)
            .build()
    }

    private fun detectMimeType(url: String): String? {
        return when {
            url.endsWith(".mpd", true) -> MimeTypes.APPLICATION_MPD
            url.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.endsWith(".ism/manifest", true) -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    private fun String.hexToBase64Url(): String {
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
