package com.radioapp.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.radioapp.R
import com.radioapp.data.StatsManager

class RadioWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return RadioRemoteViewsFactory(this.applicationContext)
    }
}

class RadioRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var stationIds: List<Int> = emptyList()
    
    // Copie de la map des logos (devrait idéalement être partagée avec RadioWidgetProvider ou MainActivity)
    private val stationLogos = mapOf(
        1 to R.drawable.logo_france_inter,
        2 to R.drawable.logo_france_culture,
        3 to R.drawable.logo_france_info,
        4 to R.drawable.logo_rtl,
        5 to R.drawable.logo_bbc_radio3,
        6 to R.drawable.logo_fip,
        7 to R.drawable.logo_bbc_radio_scotland,
        8 to R.drawable.logo_radio_nova,
        9 to R.drawable.logo_rfi,
        10 to R.drawable.logo_bbc_radio1,
        11 to R.drawable.logo_bbc_world_service,
        12 to R.drawable.logo_radio_canada_premiere,
        13 to R.drawable.logo_france_musique,
        14 to R.drawable.logo_raje,
        15 to R.drawable.logo_bide_et_musique,
        16 to R.drawable.logo_so_radio_oman,
        17 to R.drawable.logo_97_underground,
        18 to R.drawable.logo_pink_unicorn_radio,
        19 to R.drawable.logo_radio_meuh,
        20 to R.drawable.logo_ibiza_global_radio,
        21 to R.drawable.logo_wwoz,
        22 to R.drawable.logo_radio_caroline,
        23 to R.drawable.logo_ibiza_live_radio,
        24 to R.drawable.logo_nts_1,
        25 to R.drawable.logo_nts_2,
        26 to R.drawable.logo_dublab,
        27 to R.drawable.logo_cashmere_radio,
        28 to R.drawable.logo_rinse_fm,
        29 to R.drawable.logo_le_mellotron,
        30 to R.drawable.logo_refuge_worldwide_1,
        31 to R.drawable.logo_refuge_worldwide_2,
        32 to R.drawable.logo_fluxfm,
        33 to R.drawable.logo_oe1
    )

    override fun onCreate() {
        // Init data
    }

    override fun onDataSetChanged() {
        // Recharger et trier les données
        val prefs = context.getSharedPreferences("RadioWidgetPrefs", Context.MODE_PRIVATE)
        val currentStationId = prefs.getInt("current_station_id", -1)
        
        val statsManager = StatsManager.getInstance(context)
        val allStations = stationLogos.keys.toList()

        stationIds = allStations.sortedWith(Comparator { id1, id2 ->
            // 1. Station courante en premier
            if (id1 == currentStationId) return@Comparator -1
            if (id2 == currentStationId) return@Comparator 1

            // 2. Play count descendant
            val count1 = statsManager.getPlayCount(id1)
            val count2 = statsManager.getPlayCount(id2)
            if (count1 != count2) return@Comparator count2 - count1

            // 3. Listening time descendant
            val time1 = statsManager.getListeningTime(id1)
            val time2 = statsManager.getListeningTime(id2)
            if (time1 != time2) return@Comparator (time2 - time1).toInt() // Attention overflow si diff > Int.MAX_VALUE

            // 4. Par ID par défaut
            id1 - id2
        })
    }

    override fun onDestroy() {
        stationIds = emptyList()
    }

    override fun getCount(): Int {
        return stationIds.size
    }

    override fun getViewAt(position: Int): RemoteViews {
        if (position == -1 || position >= stationIds.size) {
            return RemoteViews(context.packageName, R.layout.widget_item)
        }

        val stationId = stationIds[position]
        val logoResId = stationLogos[stationId] ?: R.drawable.ic_notification

        val views = RemoteViews(context.packageName, R.layout.widget_item)
        views.setImageViewResource(R.id.widget_item_logo, logoResId)

        // Fill-in Intent pour le click
        val fillInIntent = Intent().apply {
            putExtra("station_id", stationId)
        }
        views.setOnClickFillInIntent(R.id.widget_item_container, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return if (position < stationIds.size) stationIds[position].toLong() else position.toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }
}
