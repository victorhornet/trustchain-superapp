package nl.tudelft.trustchain.eurotoken.ui.benchmarks

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import nl.tudelft.trustchain.eurotoken.R

data class LegendItem(
    val color: Int,
    val label: String,
    val percentage: Double,
    val duration: String
)

class LegendAdapter : RecyclerView.Adapter<LegendAdapter.LegendViewHolder>() {
    
    private var items = listOf<LegendItem>()
    
    fun setItems(newItems: List<LegendItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LegendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_legend, parent, false)
        return LegendViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: LegendViewHolder, position: Int) {
        holder.bind(items[position])
    }
    
    override fun getItemCount() = items.size
    
    class LegendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val titleText: TextView = itemView.findViewById(R.id.txtLegendTitle)
        private val subtitleText: TextView = itemView.findViewById(R.id.txtLegendSubtitle)
        
        fun bind(item: LegendItem) {
            titleText.text = "${item.label} (${String.format("%.1f%%", item.percentage)})"
            subtitleText.text = "Average: ${item.duration}"
            
            // Set the color indicator
            val drawable = colorIndicator.background as? GradientDrawable
            drawable?.setColor(item.color)
        }
    }
}
