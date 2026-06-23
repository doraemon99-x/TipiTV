package id.tipime.tv.data.model

import com.google.gson.annotations.SerializedName

data class Playlist(
    @SerializedName("categories")
    val categories: List<Category> = emptyList(),

    @SerializedName("drm_licenses")
    val drmLicenses: List<DrmLicense> = emptyList()
) {
    fun findDrmLicense(drmId: String?): DrmLicense? {
        if (drmId.isNullOrEmpty()) return null
        return drmLicenses.firstOrNull { it.drmId == drmId }
    }
}

data class Category(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("channels")
    val channels: List<Channel> = emptyList()
)

data class Channel(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("stream_url")
    val streamUrl: String = "",

    @SerializedName("logo")
    val logo: String? = null,

    @SerializedName("drm_id")
    val drmId: String? = null,

    @SerializedName("user_agent")
    val userAgent: String? = null,

    @SerializedName("referer")
    val referer: String? = null,

    @SerializedName("origin")
    val origin: String? = null
)

data class DrmLicense(
    @SerializedName("drm_id")
    val drmId: String = "",

    // "clearkey" | "widevine" | "playready"
    @SerializedName("drm_type")
    val drmType: String = "",

    // clearkey  → "KID:KEY"
    // widevine  → "https://license-server.url"
    // playready → "https://license-server.url"
    @SerializedName("drm_key")
    val drmKey: String = "",

    // optional extra headers for license request e.g. {"Authorization":"Bearer xxx"}
    @SerializedName("drm_headers")
    val drmHeaders: Map<String, String>? = null
)
