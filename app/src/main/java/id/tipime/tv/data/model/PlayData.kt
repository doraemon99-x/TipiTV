package id.tipime.tv.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayData(
    val categoryIndex: Int,
    val channelIndex: Int
) : Parcelable
