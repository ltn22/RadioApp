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
    private val onStationClick: (RadioStation) -> Unit,
    private val onAlarmStateChanged: (Boolean) -> Unit = {},
    private val onClockClick: () -> Unit = {}
) : RecyclerView.Adapter<RadioStationAdapter.ViewHolder>() {

    private var currentPlayingStationId: Int? = null
    var alarmTimeText: String = "06:30"
    private var currentIpVersion: String = "N/A"
    private val stationsWithClock = HashSet<Int>()

    var isAlarmSet: Boolean
        get() = stationsWithClock.contains(STATION_ID_FRANCE_CULTURE)
        set(value) {
            if (value) {
                stationsWithClock.add(STATION_ID_FRANCE_CULTURE)
            } else {
                stationsWithClock.remove(STATION_ID_FRANCE_CULTURE)
            }
            notifyDataSetChanged()
        }

    companion object {
        private const val PAYLOAD_STATS_UPDATE = "stats_update"
        private const val STATION_ID_FRANCE_CULTURE = 2
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivStationLogo: ImageView = view.findViewById(R.id.ivStationLogo)
        val tvStationStats: TextView = view.findViewById(R.id.tvStationStats)
        val tcStationClock: TextView = view.findViewById(R.id.tcStationClock)
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

            // Gérer l'affichage de l'horloge
            updateClockVisibility(holder, station)

            holder.itemView.setOnClickListener {
                // Ne rien faire si c'est déjà la station en cours de lecture
                if (station.id != currentPlayingStationId) {
                    onStationClick(station)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (station.id == STATION_ID_FRANCE_CULTURE) {
                    if (stationsWithClock.contains(station.id)) {
                        stationsWithClock.remove(station.id)
                        onAlarmStateChanged(false)
                    } else {
                        stationsWithClock.add(station.id)
                        onAlarmStateChanged(true)
                    }
                    updateClockVisibility(holder, station)
                    true // Consommer l'événement
                } else {
                    false // Laisser passer pour d'autres gestionnaires potentiels
                }
            }
        } else {
            // Partial bind - only update what changed
            if (payloads.contains(PAYLOAD_STATS_UPDATE)) {
                updateStats(holder, station)
            }
        }
    }

    private fun updateClockVisibility(holder: ViewHolder, station: RadioStation) {
        if (stationsWithClock.contains(station.id)) {
            holder.tcStationClock.visibility = View.VISIBLE
        } else {
            holder.tcStationClock.visibility = View.GONE
        }
    }

    private fun updateStats(holder: ViewHolder, station: RadioStation) {
        val playCount = statsManager.getPlayCount(station.id)
        val listeningTime = statsManager.getListeningTime(station.id)
        val formattedTime = statsManager.formatListeningTime(listeningTime)
        val formattedPlayCount = statsManager.formatPlayCount(playCount)
        holder.tvStationStats.text = "$formattedPlayCount plays\n$formattedTime"

        // Update clock text
        holder.tcStationClock.text = alarmTimeText
        holder.tcStationClock.setOnClickListener {
            if (stationsWithClock.contains(station.id)) {
                onClockClick()
            }
        }
    }

    private fun updateBackground(holder: ViewHolder, station: RadioStation) {
        if (station.id == currentPlayingStationId) {
            // Choisir la couleur en fonction de la version IP
            val colorResId = when (currentIpVersion) {
                "IPv4" -> R.color.active_station_ipv4  // Jaune
                "IPv6" -> R.color.active_station_ipv6  // Violet
                else -> android.R.color.holo_blue_light  // Bleu par défaut si N/A
            }
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, colorResId)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
        }
    }

    override fun getItemCount() = stations.size
    
    fun updateStats() {
        // Just update the displayed stats without reordering
        // Use payload to only update stats, not reload images
        notifyItemRangeChanged(0, stations.size, PAYLOAD_STATS_UPDATE)
    }

    fun sortStations() {
        // Trier les stations par nombre d'utilisations (décroissant), puis par durée d'écoute (décroissant)
        stations = stations.sortedWith(compareByDescending<RadioStation> { station ->
            statsManager.getPlayCount(station.id)
        }.thenByDescending { station ->
            statsManager.getListeningTime(station.id)
        })

        notifyDataSetChanged()
    }

    fun setCurrentPlayingStation(stationId: Int?) {
        currentPlayingStationId = stationId
        notifyDataSetChanged()
    }

    fun setIpVersion(ipVersion: String) {
        currentIpVersion = ipVersion
        notifyDataSetChanged()
    }
}