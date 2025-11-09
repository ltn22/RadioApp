package com.radioapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.radioapp.MainActivity
import com.radioapp.R
import com.radioapp.data.StatsManager

class RadioWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_PLAY_STATION = "com.radioapp.ACTION_PLAY_STATION"
        private const val ACTION_UPDATE_WIDGET = "com.radioapp.ACTION_UPDATE_WIDGET"
        private const val EXTRA_STATION_ID = "station_id"
        private const val EXTRA_CURRENT_STATION_ID = "current_station_id"
        private const val PREFS_NAME = "RadioWidgetPrefs"
        private const val PREF_CURRENT_STATION = "current_station_id"

        // Tous les logos disponibles
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

        fun updateWidget(context: Context, currentStationId: Int?) {
            val intent = Intent(context, RadioWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
                if (currentStationId != null) {
                    putExtra(EXTRA_CURRENT_STATION_ID, currentStationId)
                }
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_STATION -> {
                val stationId = intent.getIntExtra(EXTRA_STATION_ID, -1)
                if (stationId != -1) {
                    // Ouvrir l'application et lancer la station
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(EXTRA_STATION_ID, stationId)
                    }
                    context.startActivity(launchIntent)
                }
            }
            ACTION_UPDATE_WIDGET -> {
                val currentStationId = intent.getIntExtra(EXTRA_CURRENT_STATION_ID, -1)
                val actualStationId = if (currentStationId != -1) currentStationId else null

                // Sauvegarder la station actuelle
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(PREF_CURRENT_STATION, actualStationId ?: -1).apply()

                // Mettre à jour tous les widgets
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, RadioWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId, actualStationId)
                }
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        currentStationId: Int?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val statsManager = StatsManager(context)

        // Récupérer la station actuelle sauvegardée si non fournie
        val actualCurrentStationId = currentStationId ?: run {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs.getInt(PREF_CURRENT_STATION, -1)
            if (savedId != -1) savedId else null
        }

        // Récupérer les 3 stations les plus populaires (par playCount, puis par listeningTime)
        val allStations = stationLogos.keys.toList()
        val topStations = allStations
            .sortedWith(compareByDescending<Int> { stationId ->
                statsManager.getPlayCount(stationId)
            }.thenByDescending { stationId ->
                statsManager.getListeningTime(stationId)
            })
            .filter { it != actualCurrentStationId } // Exclure la station en cours
            .take(3)

        // Construire la liste des 4 stations à afficher
        val displayStations = mutableListOf<Int>()

        // 1. Station en cours (si elle existe)
        if (actualCurrentStationId != null && stationLogos.containsKey(actualCurrentStationId)) {
            displayStations.add(actualCurrentStationId)
        }

        // 2. Compléter avec les top 3 (ou top 4 si pas de station en cours)
        displayStations.addAll(topStations.take(4 - displayStations.size))

        // 3. Si pas assez de stations, compléter avec les premières
        if (displayStations.size < 4) {
            allStations
                .filter { it !in displayStations }
                .take(4 - displayStations.size)
                .forEach { displayStations.add(it) }
        }

        // Configurer les 4 vues
        val viewIds = listOf(
            R.id.widget_station_1 to R.id.widget_logo_1,
            R.id.widget_station_2 to R.id.widget_logo_2,
            R.id.widget_station_3 to R.id.widget_logo_3,
            R.id.widget_station_4 to R.id.widget_logo_4
        )

        displayStations.take(4).forEachIndexed { index, stationId ->
            val (containerViewId, logoViewId) = viewIds[index]
            val logoResId = stationLogos[stationId] ?: R.drawable.ic_notification

            // Définir le logo
            views.setImageViewResource(logoViewId, logoResId)

            // Créer l'intent pour lancer la station
            val playIntent = Intent(context, RadioWidgetProvider::class.java).apply {
                action = ACTION_PLAY_STATION
                putExtra(EXTRA_STATION_ID, stationId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                stationId,
                playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Attacher l'intent au conteneur
            views.setOnClickPendingIntent(containerViewId, pendingIntent)
        }

        statsManager.cleanup()
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
