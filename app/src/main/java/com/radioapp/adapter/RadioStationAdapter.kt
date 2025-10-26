package com.radioapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.radioapp.R
import com.radioapp.data.StatsManager
import com.radioapp.model.RadioStation

class RadioStationAdapter(
    private var stations: List<RadioStation>,
    private val statsManager: StatsManager,
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var currentPlayingStationId: Int? = null

    companion object {
        private const val PAYLOAD_STATS_UPDATE = "stats_update"
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivStationLogo: ImageView = view.findViewById(R.id.ivStationLogo)
        val tvStationStats: TextView = view.findViewById(R.id.tvStationStats)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        onBindViewHolder(holder, position, emptyList())
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val station = stations[position]

        if (payloads.isEmpty()) {
            // Full bind - set everything including logo
            holder.ivStationLogo.setImageResource(station.logoResId)
            updateStats(holder, station)
            updateBackground(holder, station)

            holder.itemView.setOnClickListener {
                // Ne rien faire si c'est déjà la station en cours de lecture
                if (station.id != currentPlayingStationId) {
                    onStationClick(station)
                }
            }
        } else {
            // Partial bind - only update what changed
            if (payloads.contains(PAYLOAD_STATS_UPDATE)) {
                updateStats(holder, station)
            }
        }
    }

    private fun updateStats(holder: ViewHolder, station: RadioStation) {
        val playCount = statsManager.getPlayCount(station.id)
        val listeningTime = statsManager.getListeningTime(station.id)
        val formattedTime = statsManager.formatListeningTime(listeningTime)
        holder.tvStationStats.text = "$playCount plays\n$formattedTime"
    }

    private fun updateBackground(holder: ViewHolder, station: RadioStation) {
        if (station.id == currentPlayingStationId) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_blue_light)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
        }
    }

    override fun getItemCount() = stations.size
    
    fun updateStats() {
        // Trier les stations par nombre d'utilisations (décroissant)
        stations = stations.sortedByDescending { station ->
            statsManager.getPlayCount(station.id)
        }
        // Use payload to only update stats, not reload images
        notifyItemRangeChanged(0, stations.size, PAYLOAD_STATS_UPDATE)
    }
    
    fun setCurrentPlayingStation(stationId: Int?) {
        currentPlayingStationId = stationId
        notifyDataSetChanged()
    }
}