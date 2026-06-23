package id.tipime.tv.util

import id.tipime.tv.data.model.Category
import id.tipime.tv.data.model.Channel
import id.tipime.tv.data.model.DrmLicense
import id.tipime.tv.data.model.Playlist
import java.net.URLDecoder

object M3uParser {

    fun parse(content: String): Playlist {
        val lines = content.lines()
        val categories = mutableMapOf<String, MutableList<Channel>>()
        val drmLicenses = mutableListOf<DrmLicense>()
        val drmIds = mutableSetOf<String>()

        var name = ""
        var logo = ""
        var group = "General"
        var drmType = ""
        var drmKey = ""
        var userAgent = ""
        var referer = ""
        var origin = ""

        fun reset() {
            name = ""; logo = ""; group = "General"
            drmType = ""; drmKey = ""
            userAgent = ""; referer = ""; origin = ""
        }

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("#EXTINF") -> {
                    reset()
                    name = t.substringAfterLast(",").trim()
                    logo = t.extractAttr("tvg-logo")
                    group = t.extractAttr("group-title").ifEmpty { "General" }
                }
                t.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val raw = t.substringAfter("=").trim()
                    drmType = when {
                        raw.contains("clearkey", true) -> "clearkey"
                        raw.contains("widevine", true) -> "widevine"
                        raw.contains("playready", true) -> "playready"
                        else -> raw
                    }
                }
                t.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    drmKey = t.substringAfter("=").trim()
                }
                t.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    userAgent = t.substringAfter("=").trim()
                }
                t.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    referer = t.substringAfter("=").trim()
                }
                t.startsWith("#KODIPROP:inputstream.adaptive.stream_headers=") -> {
                    val headers = t.substringAfter("=").trim()
                    headers.split("&").forEach { pair ->
                        val k = pair.substringBefore("=").trim()
                        val v = URLDecoder.decode(pair.substringAfter("=").trim(), "UTF-8")
                        when (k.lowercase()) {
                            "user-agent" -> userAgent = v
                            "referer" -> referer = v
                            "origin" -> origin = v
                        }
                    }
                }
                t.isNotEmpty() && !t.startsWith("#") && name.isNotEmpty() -> {
                    var streamUrl = t
                    if (streamUrl.contains("|")) {
                        val parts = streamUrl.split("|", limit = 2)
                        streamUrl = parts[0]
                        parts[1].split("&").forEach { pair ->
                            val k = pair.substringBefore("=")
                            val v = pair.substringAfter("=")
                            when (k.lowercase()) {
                                "user-agent" -> if (userAgent.isEmpty()) userAgent = v
                                "referer" -> if (referer.isEmpty()) referer = v
                            }
                        }
                    }

                    var drmId: String? = null
                    if (drmType.isNotEmpty() && drmKey.isNotEmpty()) {
                        val key = "$drmType:$drmKey"
                        if (!drmIds.contains(key)) {
                            val id = "drm_${drmIds.size + 1}"
                            drmLicenses.add(DrmLicense(id, drmType, drmKey))
                            drmIds.add(key)
                        }
                        drmId = drmLicenses.first { it.drmKey == drmKey && it.drmType == drmType }.drmId
                    }

                    categories.getOrPut(group) { mutableListOf() }.add(
                        Channel(
                            name = name,
                            streamUrl = streamUrl,
                            logo = logo.ifEmpty { null },
                            drmId = drmId,
                            userAgent = userAgent.ifEmpty { null },
                            referer = referer.ifEmpty { null },
                            origin = origin.ifEmpty { null }
                        )
                    )
                    reset()
                }
            }
        }

        return Playlist(
            categories = categories.map { (n, ch) -> Category(name = n, channels = ch) },
            drmLicenses = drmLicenses
        )
    }

    private fun String.extractAttr(attr: String): String {
        val pattern = Regex("""$attr="([^"]*?)"""")
        return pattern.find(this)?.groupValues?.get(1) ?: ""
    }
}
