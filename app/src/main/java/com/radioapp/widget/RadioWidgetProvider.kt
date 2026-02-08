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
            33 to R.drawable.logo_oe1,
            34 to R.drawable.logo_radio_fg,
            35 to R.drawable.logo_chicago_house,
            36 to R.drawable.logo_bbc_radio4,
            37 to R.drawable.logo_alpha_radio,
            38 to R.drawable.logo_bbc_radio6,
            39 to R.drawable.logo_kexp
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

        // Sauvegarder la station actuelle dans les prefs si fournie pour que le Factory la récupère
        if (currentStationId != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(PREF_CURRENT_STATION, currentStationId).apply()
        }

        // Configurer l'adapter pour la GridView
        val intent = Intent(context, RadioWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_grid, intent)

        // Configurer le template de click pour les items de la liste
        val clickIntentTemplate = Intent(context, RadioWidgetProvider::class.java).apply {
            action = ACTION_PLAY_STATION
        }
        val clickPendingIntentTemplate = PendingIntent.getBroadcast(
            context,
            0,
            clickIntentTemplate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_grid, clickPendingIntentTemplate)

        // Gérer la vue vide
        views.setEmptyView(R.id.widget_grid, R.id.empty_view)

        // Notifier le changement de données pour que le Factory recharge et trie
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
