package com.radioapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import java.net.Inet4Address
import java.net.Inet6Address
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.DefaultLoadControl
import java.net.InetAddress
import java.net.URL
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.radioapp.R
import com.radioapp.model.RadioStation
import com.radioapp.data.StatsManager
import kotlinx.coroutines.*

class RadioService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RadioServiceChannel"

        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_SKIP_BUFFER = "action_skip_buffer"
    }

    private lateinit var exoPlayer: ExoPlayer
    private var currentStation: RadioStation? = null
    private lateinit var statsManager: StatsManager
    private lateinit var mediaSession: MediaSessionCompat

    private var sessionStartTime: Long = 0L
    private var isSessionActive = false
    private var totalBytesReceived: Long = 0L // Pour la notification (session actuelle)
    private var lastSavedBytes: Long = 0L // Dernier montant sauvegardé

    // Variables pour le calcul du débit moyen
    private var bitrateStartTime: Long = 0L
    private var bitrateStartBytes: Long = 0L
    private var averageBitrate: Double = 0.0 // Débit moyen en kbps

    // Information sur le type de connexion IP
    private var ipVersion: String = "N/A"

    // Information sur le codec audio
    private var audioCodec: String = "N/A"

    // Cache du logo pour la notification
    private var cachedStationLogo: Bitmap? = null
    private var cachedStationLogoId: Int? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // TransferListener pour compter les octets transférés
    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) {
                totalBytesReceived += bytesTransferred
            }
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }
    
    interface RadioServiceListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onBufferingUpdate(bufferedPercentage: Int)
        fun onError(message: String)
        fun onMetadataChanged(title: String?, artworkUri: String?)
    }
    
    private var listener: RadioServiceListener? = null
    
    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }
    
    private val binder = RadioBinder()

    override fun onCreate() {
        super.onCreate()
        statsManager = StatsManager(this)
        createNotificationChannel()
        initializeMediaSession()
        initializePlayer()
        // Don't start foreground here - will be done in play() to avoid Android 12+ restrictions
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle media button events from lock screen / AOD
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_PLAY -> {
                if (currentStation != null) {
                    play()
                } else {
                    // Si aucune station n'est chargée, ne rien faire
                    listener?.onError("Aucune station sélectionnée")
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_STOP -> {
                stop()
                // Arrêter complètement le service quand on stop
                stopSelf()
            }
            ACTION_SKIP_BUFFER -> skipBuffer()
            else -> {
                // Service started without action - will be controlled via binding
                // Start foreground immediately to satisfy Android 12+ requirements
                // This will be updated when play() is called
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                } catch (e: Exception) {
                    // Ignore if foreground fails here, play() will try again
                }
            }
        }
        return START_STICKY
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                listener?.onPlaybackStateChanged(isPlaying)
                                updateNotification()
                            }
                            Player.STATE_BUFFERING -> {
                                listener?.onPlaybackStateChanged(false)
                            }
                            Player.STATE_ENDED -> {
                                listener?.onPlaybackStateChanged(false)
                            }
                        }
                    }

                    override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                        listener?.onError("Playback error: ${error.message}")
                    }

                    override fun onMetadata(metadata: Metadata) {
                        // Parcourir les métadonnées pour trouver les informations ICY
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is IcyInfo) {
                                val title = entry.title
                                listener?.onMetadataChanged(title, null)
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // Détecter le codec audio
                        detectAudioCodec(tracks)
                    }
                })
            }

        startBufferMonitoring()
    }

    private fun startBufferMonitoring() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (::exoPlayer.isInitialized) {
                        val bufferedPercentage = exoPlayer.bufferedPercentage
                        listener?.onBufferingUpdate(bufferedPercentage)
                    }
                } catch (e: Exception) {
                    // Ignore monitoring errors
                }
                delay(500) // Update every 500ms
            }
        }
    }

    fun setListener(listener: RadioServiceListener) {
        this.listener = listener
    }

    fun loadStation(station: RadioStation) {
        // Sauvegarder les données consommées restantes de la station précédente
        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
            val unsavedBytes = totalBytesReceived - lastSavedBytes
            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
        }

        currentStation = station

        // Réinitialiser le temps de session et les données lors du changement de station
        sessionStartTime = 0L
        isSessionActive = false
        totalBytesReceived = 0L
        lastSavedBytes = 0L

        // Réinitialiser les variables de débit, IP et codec
        bitrateStartTime = 0L
        bitrateStartBytes = 0L
        averageBitrate = 0.0
        ipVersion = "N/A"
        audioCodec = "N/A"

        // Vider le cache du logo pour forcer le rechargement du nouveau logo
        cachedStationLogo = null
        cachedStationLogoId = null

        val mediaItem = MediaItem.fromUri(station.url)

        // Créer le DataSourceFactory avec le TransferListener
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioApp/1.0")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(transferListener)

        val mediaSource = if (station.url.contains(".m3u8")) {
            // HLS stream
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            // Progressive stream (MP3, etc.)
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()

        // Mettre à jour les métadonnées de la session média
        updateMediaSessionMetadata()

        // Détecter la version IP utilisée pour cette station
        detectIpVersion()
    }

    fun play() {
        if (!isSessionActive) {
            // Démarrer une nouvelle session
            sessionStartTime = System.currentTimeMillis()
            isSessionActive = true
            startSessionTimeUpdater()
        }
        exoPlayer.play()
        updateMediaSessionState(true)
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        listener?.onPlaybackStateChanged(true)
    }

    fun pause() {
        exoPlayer.pause()
        updateMediaSessionState(false)
        listener?.onPlaybackStateChanged(false)
        updateNotification()
    }

    fun stop() {
        // Sauvegarder les données consommées restantes avant de stopper
        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
            val unsavedBytes = totalBytesReceived - lastSavedBytes
            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
        }

        exoPlayer.stop()
        updateMediaSessionState(false)

        // Réinitialiser la session
        isSessionActive = false
        sessionStartTime = 0L
        totalBytesReceived = 0L
        lastSavedBytes = 0L

        // Réinitialiser les variables de débit, IP et codec
        bitrateStartTime = 0L
        bitrateStartBytes = 0L
        averageBitrate = 0.0
        ipVersion = "N/A"
        audioCodec = "N/A"

        // Vider le cache du logo
        cachedStationLogo = null
        cachedStationLogoId = null

        listener?.onPlaybackStateChanged(false)
        stopForeground(true)
    }

    private fun startSessionTimeUpdater() {
        serviceScope.launch {
            var counter = 0
            while (isActive && isSessionActive) {
                delay(1000) // Mettre à jour chaque seconde
                counter++
                if (isSessionActive) {
                    // Calculer le débit
                    calculateBitrate()

                    updateNotification()

                    // Sauvegarder les données consommées toutes les 10 secondes
                    if (counter >= 10) {
                        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
                            val unsavedBytes = totalBytesReceived - lastSavedBytes
                            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
                            lastSavedBytes = totalBytesReceived // Mettre à jour le dernier montant sauvegardé
                        }
                        counter = 0
                    }
                }
            }
        }
    }

    private fun getSessionDuration(): String {
        if (!isSessionActive || sessionStartTime == 0L) {
            return "0s"
        }
        val durationMs = System.currentTimeMillis() - sessionStartTime
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60))

        return when {
            hours > 0 -> "${hours}h ${minutes}m" // Pas de secondes quand il y a des heures
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun formatDataReceived(): String {
        val kilobytes = totalBytesReceived / 1024.0
        val megabytes = totalBytesReceived / (1024.0 * 1024.0)

        return when {
            megabytes >= 1.0 -> String.format("%.2f MB", megabytes)
            kilobytes >= 1.0 -> String.format("%.2f KB", kilobytes)
            else -> "0.00 KB"
        }
    }

    fun skipBuffer() {
        if (::exoPlayer.isInitialized && exoPlayer.isPlaying) {
            serviceScope.launch {
                try {
                    // Stratégie : Fast-forward pour vider rapidement le buffer
                    listener?.onError("Fast-forward en cours...")
                    
                    // 1. Couper le son pour le fast-forward silencieux
                    val originalVolume = exoPlayer.volume
                    val originalPlaybackSpeed = exoPlayer.playbackParameters.speed
                    exoPlayer.volume = 0f
                    
                    // 2. Accélérer la lecture pour "consommer" le buffer rapidement
                    // Speed 8x = 10 secondes de pub en 1.25 secondes
                    exoPlayer.setPlaybackSpeed(8.0f)
                    
                    // 3. Laisser le fast-forward pendant 2 secondes 
                    // (= 16 secondes de contenu à vitesse normale)
                    delay(2000)
                    
                    // 4. Revenir à la vitesse normale et remettre le son
                    exoPlayer.setPlaybackSpeed(1.0f)
                    exoPlayer.volume = originalVolume
                    
                    listener?.onError("Fast-forward terminé - reprise normale")
                    listener?.onBufferingUpdate(0)
                    
                } catch (e: Exception) {
                    // En cas d'erreur, restaurer les paramètres normaux
                    try {
                        exoPlayer.setPlaybackSpeed(1.0f)
                        exoPlayer.volume = 1f
                    } catch (e2: Exception) {
                        // Ignore les erreurs de restauration
                    }
                    listener?.onError("Erreur fast-forward: ${e.message}")
                }
            }
        }
    }

    fun isPlaying(): Boolean = if (::exoPlayer.isInitialized) exoPlayer.isPlaying else false

    fun getCurrentStation(): RadioStation? = currentStation

    private fun detectAudioCodec(tracks: Tracks) {
        try {
            // Parcourir tous les groupes de pistes
            for (trackGroup in tracks.groups) {
                // Vérifier si c'est une piste audio
                if (trackGroup.type == com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO) {
                    // Obtenir le format de la première piste audio
                    val format = trackGroup.getTrackFormat(0)

                    // Extraire le nom du codec de manière lisible
                    audioCodec = when {
                        format.sampleMimeType?.contains("mp3", ignoreCase = true) == true -> "MP3"
                        format.sampleMimeType?.contains("mp4a", ignoreCase = true) == true -> "AAC"
                        format.sampleMimeType?.contains("aac", ignoreCase = true) == true -> "AAC"
                        format.sampleMimeType?.contains("opus", ignoreCase = true) == true -> "Opus"
                        format.sampleMimeType?.contains("vorbis", ignoreCase = true) == true -> "Vorbis"
                        format.sampleMimeType?.contains("flac", ignoreCase = true) == true -> "FLAC"
                        format.sampleMimeType?.contains("ac3", ignoreCase = true) == true -> "AC-3"
                        format.sampleMimeType?.contains("eac3", ignoreCase = true) == true -> "E-AC-3"
                        format.codecs != null -> format.codecs ?: "N/A"
                        else -> format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "N/A"
                    }

                    // Ajouter le bitrate du codec si disponible
                    if (format.bitrate > 0 && format.bitrate != Format.NO_VALUE) {
                        val codecBitrate = format.bitrate / 1000 // Convertir en kbps
                        audioCodec = "$audioCodec ($codecBitrate kbps)"
                    }

                    // On prend la première piste audio trouvée
                    break
                }
            }
        } catch (e: Exception) {
            audioCodec = "N/A"
        }
    }

    private fun detectIpVersion() {
        // Détection asynchrone de la version IP réellement utilisée
        serviceScope.launch(Dispatchers.IO) {
            try {
                val stationUrl = currentStation?.url ?: return@launch
                val url = URL(stationUrl)
                val hostname = url.host

                // Résoudre le nom de domaine pour obtenir l'adresse IP
                val addresses = InetAddress.getAllByName(hostname)

                if (addresses.isNotEmpty()) {
                    // Prendre la première adresse (celle qui sera utilisée par défaut)
                    val primaryAddress = addresses[0]

                    ipVersion = when (primaryAddress) {
                        is Inet6Address -> "IPv6"
                        is Inet4Address -> "IPv4"
                        else -> "N/A"
                    }
                } else {
                    ipVersion = "N/A"
                }
            } catch (e: Exception) {
                ipVersion = "N/A"
            }
        }
    }

    private fun calculateBitrate() {
        val currentTime = System.currentTimeMillis()

        // Initialisation au premier appel
        if (bitrateStartTime == 0L) {
            bitrateStartTime = currentTime
            bitrateStartBytes = totalBytesReceived
            return
        }

        // Calculer le débit moyen depuis le début de la session
        val totalTimeDiff = currentTime - bitrateStartTime
        if (totalTimeDiff > 0) {
            val totalBytesDiff = totalBytesReceived - bitrateStartBytes
            // Convertir en kbps: (bytes * 8) / (time_in_ms)
            averageBitrate = (totalBytesDiff * 8.0) / totalTimeDiff
        }
    }

    private fun formatBitrate(): String {
        return if (averageBitrate >= 1000) {
            String.format(java.util.Locale.US, "%.1f Mbps", averageBitrate / 1000)
        } else if (averageBitrate > 0) {
            String.format(java.util.Locale.US, "%.0f kbps", averageBitrate)
        } else {
            "—"
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Radio Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Radio playback service"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "RadioService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onStop() {
                    stop()
                    stopSelf()
                }
            })

            isActive = true
        }
    }

    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentStation?.name ?: "Radio App")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentStation?.genre ?: "")
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun createNotification(): Notification {
        val stationName = currentStation?.name ?: "Radio App"
        val isPlaying = if (::exoPlayer.isInitialized) exoPlayer.isPlaying else false
        val sessionDuration = getSessionDuration()
        val dataReceived = formatDataReceived()
        val bitrate = formatBitrate()

        // Texte de la notification avec le temps de session et les données reçues
        val notificationText = "$sessionDuration • $dataReceived"

        // Convertir le logo de la station en Bitmap pour l'icône large (avec cache)
        val largeIcon: Bitmap? = currentStation?.logoResId?.let { logoResId ->
            // Utiliser le cache si le logo n'a pas changé
            if (cachedStationLogoId == logoResId && cachedStationLogo != null) {
                cachedStationLogo
            } else {
                // Décoder et mettre en cache le nouveau logo
                val bitmap = BitmapFactory.decodeResource(resources, logoResId)
                cachedStationLogo = bitmap
                cachedStationLogoId = logoResId
                bitmap
            }
        }

        // Intent pour ouvrir l'app
        val openAppIntent = Intent(this, com.radioapp.MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent pour play/pause
        val playPauseIntent = Intent(this, RadioService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent pour stop
        val stopIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent pour skip buffer
        val skipIntent = Intent(this, RadioService::class.java).apply {
            action = ACTION_SKIP_BUFFER
        }
        val skipPendingIntent = PendingIntent.getService(
            this, 3, skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Créer un style qui combine MediaStyle (pour AOD) et BigTextStyle (pour le contenu étendu)
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1) // Afficher Play/Pause et Stop en mode compact

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stationName)
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentInfo("$bitrate • $audioCodec") // Infos compactes
            .setStyle(mediaStyle)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Passer pub",
                skipPendingIntent
            )

        // Ajouter le logo de la station comme icône large si disponible
        largeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        // Sauvegarder les données consommées restantes avant de détruire le service
        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
            val unsavedBytes = totalBytesReceived - lastSavedBytes
            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
        }
        mediaSession.isActive = false
        mediaSession.release()
        statsManager.cleanup()
        serviceScope.cancel()
        exoPlayer.release()
        stopForeground(true)
    }
}