package tv.nova.player.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.nova.player.R

enum class RailKind { ALL, FAVOURITES, RECENTS, GROUP }
data class RailEntry(val key: String, val title: String, val count: Int, val kind: RailKind)

class RailAdapter(private val onSelected: (RailEntry) -> Unit) : RecyclerView.Adapter<RailAdapter.Holder>() {
    private val entries = ArrayList<RailEntry>()
    var selectedKey: String = "all"
        private set

    fun submit(values: List<RailEntry>, keepSelection: Boolean = true) {
        val previous = selectedKey
        entries.clear()
        entries.addAll(values)
        selectedKey = if (keepSelection && entries.any { it.key == previous }) previous else entries.firstOrNull()?.key.orEmpty()
        notifyDataSetChanged()
    }

    fun selected(): RailEntry? = entries.firstOrNull { it.key == selectedKey }
    override fun getItemCount(): Int = entries.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_rail, parent, false) as TextView,
    )
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.text.text = "${entry.title}  ${entry.count}"
        holder.text.isSelected = entry.key == selectedKey
        holder.text.setOnClickListener {
            selectedKey = entry.key
            notifyDataSetChanged()
            onSelected(entry)
        }
    }
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
