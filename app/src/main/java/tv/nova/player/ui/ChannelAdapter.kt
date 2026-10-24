package tv.nova.player.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.nova.player.R
import tv.nova.player.data.Channel

class ChannelAdapter(
    private val logos: LogoLoader,
    private val isFavourite: (String) -> Boolean,
    private val onPlay: (Channel) -> Unit,
    private val onFavourite: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
    private val channels = ArrayList<Channel>()
    val snapshot: List<Channel> get() = channels.toList()

    fun submit(values: List<Channel>) {
        channels.clear()
        channels.addAll(values)
        notifyDataSetChanged()
    }

    fun indexOf(url: String): Int = channels.indexOfFirst { it.url == url }
    fun at(index: Int): Channel? = channels.getOrNull(index)
    override fun getItemCount(): Int = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = channels[position]
        holder.name.text = channel.name
        holder.quality.text = channel.quality.label
        holder.quality.visibility = if (channel.quality.label.isEmpty()) View.GONE else View.VISIBLE
        holder.star.setText(if (isFavourite(channel.url)) R.string.star_full else R.string.star_empty)
        holder.itemView.contentDescription = "${channel.name}, ${channel.group}, ${channel.quality.label}"
        holder.itemView.setOnClickListener { onPlay(channel) }
        holder.itemView.setOnLongClickListener { onFavourite(channel); true }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && keyCode in okKeys) {
                onFavourite(channel); true
            } else false
        }
        logos.bind(channel.logoUrl, holder.logo)
    }

    override fun onViewRecycled(holder: Holder) {
        logos.cancel(holder.logo)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        super.onViewRecycled(holder)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.channelLogo)
        val name: TextView = view.findViewById(R.id.channelName)
        val quality: TextView = view.findViewById(R.id.qualityBadge)
        val star: TextView = view.findViewById(R.id.favouriteStar)
    }

    companion object {
        private val okKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
    }
}
