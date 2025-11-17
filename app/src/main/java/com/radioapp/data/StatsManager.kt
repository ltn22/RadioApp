package com.radioapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import java.util.Locale

class StatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("radio_stats", Context.MODE_PRIVATE)
    private var currentStationId: Int? = null
    private var startTime: Long = 0
    private var isPlaying = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null
    private var isActuallyPlayingCallback: (() -> Boolean)? = null
    
    fun startListening(stationId: Int) {
        if (currentStationId != stationId) {
            // Nouvelle station, incrémenter le compteur
            incrementPlayCount(stationId)
        }
        
        currentStationId = stationId
        startTime = System.currentTimeMillis()
        isPlaying = true

        // Démarrer la mise à jour du temps d'écoute chaque seconde (si le player joue réellement)
        startTimeTracking()
    }
    
    fun stopListening() {
        if (isPlaying && currentStationId != null) {
            val duration = System.currentTimeMillis() - startTime
            addListeningTime(currentStationId!!, duration)
        }
        isPlaying = false
        currentStationId = null
    }
    
    fun pauseListening() {
        if (isPlaying && currentStationId != null) {
            val duration = System.currentTimeMillis() - startTime
            addListeningTime(currentStationId!!, duration)
        }
        isPlaying = false
    }
    
    fun resumeListening() {
        startTime = System.currentTimeMillis()
        isPlaying = true
        startTimeTracking()
    }

    // Restaurer le tracking après une rotation d'écran sans incrémenter le play count
    fun restoreListening(stationId: Int) {
        // Ne rien faire si le tracking est déjà actif pour cette station
        if (isPlaying && currentStationId == stationId) {
            return
        }

        currentStationId = stationId
        startTime = System.currentTimeMillis()
        isPlaying = true
        startTimeTracking()
    }

    // Vérifier si le tracking est actif
    fun isListening(): Boolean {
        return isPlaying && currentStationId != null
    }

    // Définir la callback pour vérifier l'état réel de lecture
    fun setPlaybackStateCallback(callback: (() -> Boolean)?) {
        isActuallyPlayingCallback = callback
    }
    
    private fun startTimeTracking() {
        // Annuler le job précédent s'il existe pour éviter les doublons
        trackingJob?.cancel()

        trackingJob = scope.launch {
            while (isPlaying && currentStationId != null) {
                delay(1000) // 1 seconde
                if (isPlaying && currentStationId != null) {
                    // Vérifier si le player joue vraiment (pas en buffering)
                    val isActuallyPlaying = isActuallyPlayingCallback?.invoke() ?: true
                    if (isActuallyPlaying) {
                        val duration = System.currentTimeMillis() - startTime
                        addListeningTime(currentStationId!!, duration)
                        startTime = System.currentTimeMillis() // Reset start time
                    }
                }
            }
        }
    }
    
    fun incrementPlayCount(stationId: Int) {
        val currentCount = prefs.getInt("play_count_$stationId", 0)
        prefs.edit().putInt("play_count_$stationId", currentCount + 1).apply()
    }
    
    private fun addListeningTime(stationId: Int, duration: Long) {
        val currentTime = prefs.getLong("listening_time_$stationId", 0L)
        prefs.edit().putLong("listening_time_$stationId", currentTime + duration).apply()
    }
    
    fun getPlayCount(stationId: Int): Int {
        return prefs.getInt("play_count_$stationId", 0)
    }
    
    fun getListeningTime(stationId: Int): Long {
        return prefs.getLong("listening_time_$stationId", 0L)
    }

    fun addDataConsumed(stationId: Int, bytes: Long) {
        val currentData = prefs.getLong("data_consumed_$stationId", 0L)
        prefs.edit().putLong("data_consumed_$stationId", currentData + bytes).apply()
    }

    fun getDataConsumed(stationId: Int): Long {
        return prefs.getLong("data_consumed_$stationId", 0L)
    }

    fun formatDataSize(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        if (megabytes < 0.01) {
            return "0.00 MB"
        }

        // Formater avec séparateur de milliers (espace) - use Locale.US to ensure period as decimal separator
        val formatted = String.format(Locale.US, "%.2f", megabytes)
        val parts = formatted.split(".")
        val integerPart = parts[0]
        val decimalPart = if (parts.size > 1) parts[1] else "00"

        // Ajouter des espaces insécables fins tous les 3 chiffres (de droite à gauche)
        val formattedInteger = integerPart.reversed()
            .chunked(3)
            .joinToString("\u202F") // Espace insécable fine
            .reversed()

        return "$formattedInteger.$decimalPart\u202FMB" // Espace insécable fine avant MB
    }

    fun formatListeningTime(timeInMillis: Long): String {
        val totalSeconds = timeInMillis / 1000
        val weeks = totalSeconds / (7 * 24 * 3600)
        val days = (totalSeconds % (7 * 24 * 3600)) / (24 * 3600)
        val hours = (totalSeconds % (24 * 3600)) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            weeks > 0 -> "${weeks}s ${days}j ${hours}h ${minutes}m ${seconds}s"
            days > 0 -> "${days}j ${hours}h ${minutes}m ${seconds}s"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}