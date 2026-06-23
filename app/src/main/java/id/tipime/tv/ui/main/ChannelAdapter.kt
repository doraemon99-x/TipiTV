package id.tipime.tv.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import id.tipime.tv.R
import id.tipime.tv.data.model.Channel
import id.tipime.tv.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (Channel, Int, Int) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ViewHolder>(DIFF) {

    private var categoryIndex = 0

    fun submitList(channels: List<Channel>, catIdx: Int) {
        categoryIndex = catIdx
        submitList(channels)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), categoryIndex, position)
    }

    inner class ViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, catIdx: Int, chIdx: Int) {
            binding.tvName.text = channel.name
            binding.ivLogo.load(channel.logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
            // show DRM badge
            binding.tvDrm.text = when {
                channel.drmId != null -> "DRM"
                else -> ""
            }
            binding.root.setOnClickListener {
                onChannelClick(channel, catIdx, chIdx)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.streamUrl == b.streamUrl
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
