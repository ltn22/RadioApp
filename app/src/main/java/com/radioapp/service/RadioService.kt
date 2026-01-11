package com.radioapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.MediaBrowserServiceCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
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
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioAttributes
import java.net.InetAddress
import java.net.URL
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.radioapp.R
import com.radioapp.model.RadioStation
import com.radioapp.data.StatsManager
import com.radioapp.data.MetadataService
import com.radioapp.data.TrackMetadata
import com.radioapp.cast.CastManager
import kotlinx.coroutines.*

class RadioService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "RadioService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RadioServiceChannelV2" // Nouveau canal pour AOD
        private const val MEDIA_ROOT_ID = "root"
        private const val MEDIA_STATIONS_ID = "stations"

        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_SKIP_BUFFER = "action_skip_buffer"
        const val ACTION_CUSTOM_SKIP = "com.radioapp.ACTION_SKIP_AD"

        // Android Auto content style constants
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
    }

    private lateinit var exoPlayer: ExoPlayer
    private var currentStation: RadioStation? = null
    private lateinit var statsManager: StatsManager
    private lateinit var mediaSession: MediaSessionCompat
    private val metadataService = MetadataService()

    private var sessionStartTime: Long = 0L
    private var isSessionActive = false
    private var totalBytesReceived: Long = 0L // Pour la notification (session actuelle)
    private var lastSavedBytes: Long = 0L // Dernier montant sauvegardé
    private var isForegroundServiceStarted = false // Track if startForeground was already called

    // Variables pour le calcul du débit moyen
    private var bitrateStartTime: Long = 0L
    private var bitrateStartBytes: Long = 0L
    private var averageBitrate: Double = 0.0 // Débit moyen en kbps

    // Information sur le type de connexion IP
    private var ipVersion: String = "N/A"

    // Information sur le codec audio
    private var audioCodec: String = "N/A"

    // Métadonnées du morceau actuel
    private var currentTrackTitle: String? = null
    private var currentArtwork: Bitmap? = null

    // Flag pour éviter les appels concurrents à skipBuffer
    private var isSkippingBuffer = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionTimeUpdaterJob: Job? = null

    // Chromecast support
    private var castManager: CastManager? = null

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
        fun onTrackMetadataChanged(metadata: TrackMetadata?)
        fun onIpVersionChanged(ipVersion: String)
        fun onSearchStatus(status: String)
        fun onCastStateChanged(isCasting: Boolean)
    }
    
    private var listener: RadioServiceListener? = null
    
    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }
    
    private val binder = RadioBinder()

    override fun onCreate() {
        super.onCreate()
        statsManager = StatsManager.getInstance(this)
        createNotificationChannel()
        initializeMediaSession()
        initializePlayer()
        initializeCastManager()
        // Set the session token for Android Auto
        sessionToken = mediaSession.sessionToken
        // Don't start foreground here - will be done in play() to avoid Android 12+ restrictions
    }

    private fun initializeCastManager() {
        try {
            castManager = CastManager(this).apply {
                listener = object : CastManager.CastManagerListener {
                    override fun onCastSessionStarted() {
                        Log.d(TAG, "Cast session started - switching to remote playback, currentStation=${currentStation?.name}")
                        // Pause local playback when casting starts
                        if (::exoPlayer.isInitialized && exoPlayer.isPlaying) {
                            Log.d(TAG, "Pausing local ExoPlayer")
                            exoPlayer.pause()
                        }
                        // Load current station on Cast
                        if (currentStation != null) {
                            val station = currentStation!!
                            val metadata = metadataService.getCurrentMetadata()
                            Log.d(TAG, "Loading station on Cast: ${station.name}, metadata=${metadata?.title}")
                            loadStationOnCast(station, metadata)
                        } else {
                            Log.w(TAG, "No current station to load on Cast!")
                        }
                        this@RadioService.listener?.onCastStateChanged(true)
                    }

                    override fun onCastSessionEnded() {
                        Log.d(TAG, "Cast session ended - resuming local playback")
                        // Resume local playback when casting ends
                        if (isSessionActive && currentStation != null) {
                            exoPlayer.play()
                        }
                        this@RadioService.listener?.onCastStateChanged(false)
                    }
                }
            }
            Log.d(TAG, "CastManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CastManager: ${e.message}", e)
            castManager = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Handle MediaBrowserService connections from Android Auto
        if (intent?.action == "android.media.browse.MediaBrowserService") {
            return super.onBind(intent)
        }
        // Handle custom local binding for MainActivity
        return binder
    }

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                } catch (e: Exception) {
                    // Ignore if foreground fails here, play() will try again
                }
            }
        }
        return START_STICKY
    }

    private fun initializePlayer() {
        // Configuration optimisée du buffering pour le streaming radio
        // Augmentation des buffers pour éviter les interruptions fréquentes
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000,  // minBufferMs: 50s - buffer minimum avant démarrage (défaut: 50s)
                120000, // maxBufferMs: 120s - buffer maximum (augmenté de 50s à 120s)
                2500,   // bufferForPlaybackMs: 2.5s - buffer pour démarrer la lecture (défaut: 2.5s)
                10000   // bufferForPlaybackAfterRebufferMs: 10s - buffer après rebuffering (augmenté de 5s à 10s)
            )
            .setPrioritizeTimeOverSizeThresholds(true) // Priorité à la durée plutôt qu'à la taille
            .build()

        // Configuration des attributs audio pour le focus audio
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // true = handle audio focus automatically
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
                            Log.d(TAG, "Raw Metadata: $entry")
                            
                            if (entry is IcyInfo) {
                                var title = entry.title
                                
                                // Fix encoding for legacy Shoutcast streams (often Latin-1)
                                if (currentStation?.name == "Bide et Musique" || currentStation?.name == "Radio Nova") {
                                    try {
                                        // Shoutcast often sends Latin-1 but it gets decoded as UTF-8
                                        // So we reverse the bad decoding: get the bytes as if they were UTF-8,
                                        // then reinterpret them as Latin-1
                                        val bytes = entry.title?.toByteArray(Charsets.UTF_8)
                                        if (bytes != null) {
                                           val newTitle = String(bytes, Charsets.ISO_8859_1)
                                           Log.d(TAG, "Re-encoded title from Latin-1: $newTitle")
                                           title = newTitle
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Encoding conversion failed", e)
                                    }
                                }
                                
                                currentTrackTitle = title
                                Log.d(TAG, "ICY Metadata processing: $title")
                                listener?.onMetadataChanged(title, null)

                                // Pour les stations non gérées par MetadataService (ICY uniquement),
                                // essayer de récupérer la pochette depuis iTunes
                                if (title != null && title.contains(" - ")) {
                                    Log.d(TAG, "ICY title contains ' - ', attempting iTunes search")
                                    serviceScope.launch(Dispatchers.IO) {
                                        try {
                                            val parts = title.split(" - ", limit = 2)
                                            if (parts.size == 2) {
                                                val artist = parts[0].trim()
                                                val trackTitle = parts[1].trim()
                                                Log.d(TAG, "Parsed: Artist='$artist', Title='$trackTitle'")

                                                if (artist.isNotEmpty() && trackTitle.isNotEmpty()) {
                                                    Log.d(TAG, "Searching iTunes for: $artist - $trackTitle")
                                                    // Rechercher sur iTunes
                                                    val coverUrl = metadataService.fetchCoverFromItunesPublic(artist, trackTitle)
                                                    Log.d(TAG, "iTunes search result: coverUrl=$coverUrl")

                                                    val coverBitmap = if (coverUrl != null) {
                                                        Log.d(TAG, "Downloading image from: $coverUrl")
                                                        metadataService.downloadImagePublic(coverUrl)
                                                    } else {
                                                        Log.d(TAG, "No cover URL found on iTunes")
                                                        null
                                                    }

                                                    if (coverBitmap != null) {
                                                        Log.d(TAG, "Cover bitmap downloaded successfully")
                                                        val trackMetadata = TrackMetadata(
                                                            title = trackTitle,
                                                            artist = artist,
                                                            album = null,
                                                            coverUrl = coverUrl,
                                                            coverBitmap = coverBitmap
                                                        )

                                                        withContext(Dispatchers.Main) {
                                                            currentArtwork = coverBitmap
                                                            updateMediaSessionMetadata(coverBitmap)
                                                            listener?.onTrackMetadataChanged(trackMetadata)
                                                            updateNotification()
                                                        }
                                                    } else {
                                                        Log.d(TAG, "Failed to download cover bitmap")
                                                    }
                                                } else {
                                                    Log.d(TAG, "Artist or title is empty after trimming")
                                                }
                                            } else {
                                                Log.d(TAG, "Failed to split title into 2 parts")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error fetching iTunes cover for ICY metadata", e)
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "ICY title does NOT contain ' - ' or is null")
                                }

                                // Mettre à jour les métadonnées de la session média pour Android Auto
                                updateMediaSessionMetadata()
                                // Mettre à jour la notification avec le nouveau titre
                                updateNotification()
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

        // Store station in CastManager for later Cast session
        castManager?.currentStation = station

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
        currentTrackTitle = null
        currentArtwork = null

        // Arrêter le monitoring précédent
        metadataService.stopMonitoring()

        // Démarrer le monitoring des métadonnées riches
        metadataService.onSearchStatus = { status ->
            listener?.onSearchStatus(status)
        }
        metadataService.startMonitoring(station.id) { metadata ->
            if (metadata != null) {
                currentTrackTitle = if (metadata.artist.isNotBlank()) {
                    "${metadata.artist} - ${metadata.title}"
                } else {
                    metadata.title
                }

                // Mettre à jour la session média avec la pochette
                currentArtwork = metadata.coverBitmap
                updateMediaSessionMetadata(metadata.coverBitmap)

                // Synchroniser les métadonnées vers Chromecast si actif
                updateMetadataOnCast(metadata)

                // Notifier l'activité
                listener?.onTrackMetadataChanged(metadata)

                // Mettre à jour la notification
                updateNotification()
            } else {
                // Reset si null (fin de morceau ou erreur)
                // On garde le titre ICY s'il existe, sinon null
                // updateMediaSessionMetadata() gère le fallback
            }
        }

        // Démarrer le tracking des statistiques
        statsManager.startListening(station.id)

        val mediaItem = MediaItem.fromUri(station.url)

        // Créer le DataSourceFactory avec détection IPv4/IPv6 et TransferListener
        // Timeouts augmentés pour gérer les connexions lentes/instables
        val dataSourceFactory = com.radioapp.network.IpDetectingHttpDataSource.Factory(
            userAgent = "RadioApp/1.0",
            connectTimeoutMs = 20000,
            readTimeoutMs = 20000,
            allowCrossProtocolRedirects = true,
            onIpVersionDetected = { detectedIpVersion ->
                // Callback appelé quand l'IP est détectée depuis la socket active
                ipVersion = detectedIpVersion
                listener?.onIpVersionChanged(detectedIpVersion)
            },
            transferListener = transferListener
        )

        val mediaSource = if (station.url.contains(".m3u8")) {
            // HLS stream
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            // Progressive stream (MP3, etc.)
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()

        // If casting, load the new station on Chromecast
        if (isCasting()) {
            Log.d(TAG, "Currently casting, loading new station on Cast: ${station.name}")
            loadStationOnCast(station, null)
        }

        // Mettre à jour les métadonnées de la session média
        updateMediaSessionMetadata()

        // La détection IP se fait automatiquement via IpDetectingHttpDataSource
    }

    fun play() {
        if (!isSessionActive) {
            // Démarrer une nouvelle session
            sessionStartTime = System.currentTimeMillis()
            isSessionActive = true
            // Annuler le timer précédent s'il existe avant d'en créer un nouveau
            sessionTimeUpdaterJob?.cancel()
            startSessionTimeUpdater()
        }

        // Delegate to Cast if casting, otherwise play locally
        if (isCasting()) {
            castManager?.play()
        } else {
            exoPlayer.play()
        }

        mediaSession.isActive = true
        updateMediaSessionState(true)

        val notification = createNotification()

        Log.d(TAG, "=== STARTING FOREGROUND SERVICE ===")
        Log.d(TAG, "SDK Version: ${Build.VERSION.SDK_INT}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Calling startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK")
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                Log.d(TAG, "Calling startForeground (old API)")
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundServiceStarted = true
            Log.d(TAG, "startForeground SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "=== FOREGROUND SERVICE STARTED ===")

        listener?.onPlaybackStateChanged(true)
    }

    fun pause() {
        // Delegate to Cast if casting, otherwise pause locally
        if (isCasting()) {
            castManager?.pause()
        } else {
            exoPlayer.pause()
        }

        updateMediaSessionState(false)
        listener?.onPlaybackStateChanged(false)
        // Ne pas annuler le timer en pause, car la session continue
        updateNotification()
    }

    fun stop() {
        // Sauvegarder les données consommées restantes avant de stopper
        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
            val unsavedBytes = totalBytesReceived - lastSavedBytes
            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
        }

        // Arrêter le monitoring des métadonnées
        metadataService.stopMonitoring()

        // Stop both local and Cast playback
        exoPlayer.stop()
        castManager?.stop()

        updateMediaSessionState(false)

        // Annuler le timer de session
        sessionTimeUpdaterJob?.cancel()
        sessionTimeUpdaterJob = null

        // Réinitialiser la session
        isSessionActive = false
        sessionStartTime = 0L
        totalBytesReceived = 0L
        lastSavedBytes = 0L
        isForegroundServiceStarted = false

        // Clear current station state so it can be re-selected
        currentStation = null

        // Réinitialiser les variables de débit, IP et codec
        bitrateStartTime = 0L
        bitrateStartBytes = 0L
        averageBitrate = 0.0
        ipVersion = "N/A"
        audioCodec = "N/A"
        currentTrackTitle = null
        currentArtwork = null

        listener?.onPlaybackStateChanged(false)
        stopForeground(true)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun startSessionTimeUpdater() {
        sessionTimeUpdaterJob = serviceScope.launch {
            var counter = 0
            while (isActive && isSessionActive) {
                delay(1000) // Mettre à jour chaque seconde
                counter++
                if (isSessionActive) {
                    // Calculer le débit
                    calculateBitrate()

                    updateNotification()

                    // Sauvegarder les données consommées toutes les secondes
                    if (counter >= 1) {
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
            // Vérifier si un skipBuffer est déjà en cours
            if (isSkippingBuffer) {
                listener?.onError("Fast-forward déjà en cours, veuillez patienter...")
                return
            }

            serviceScope.launch {
                try {
                    isSkippingBuffer = true

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
                } finally {
                    // Toujours réinitialiser le flag, même en cas d'erreur
                    isSkippingBuffer = false
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        if (::exoPlayer.isInitialized) {
            exoPlayer.volume = volume
        }
        // Pour le cast, on pourrait aussi ajuster le volume si nécessaire
        castManager?.setVolume(volume.toDouble())
    }

    fun isPlaying(): Boolean {
        return ::exoPlayer.isInitialized && exoPlayer.isPlaying
    }

    fun isCasting(): Boolean {
        return castManager?.isCastSessionActive() == true
    }

    fun getCastManager(): CastManager? = castManager

    fun getConnectedCastDeviceName(): String? {
        return castManager?.getConnectedDeviceName()
    }

    private fun loadStationOnCast(station: RadioStation, metadata: TrackMetadata?) {
        castManager?.loadStationOnCast(station, metadata)
    }

    private fun updateMetadataOnCast(metadata: TrackMetadata) {
        if (isCasting()) {
            castManager?.updateMetadataOnCast(metadata)
        }
    }

    fun getCurrentStation(): RadioStation? = currentStation

    fun getIpVersion(): String = ipVersion

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
            "Radio Playback",
            NotificationManager.IMPORTANCE_DEFAULT // Pour AOD et visibilité
        ).apply {
            description = "Contrôles de lecture radio"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // Silencieux malgré IMPORTANCE_DEFAULT
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
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
                    stopForeground(true)
                    isSessionActive = false

                    // Mettre à jour l'état de la session média
                    updateMediaSessionState(false)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.let { id ->
                        try {
                            val stationId = id.toInt()
                            val station = getAllStations().find { it.id == stationId }
                            station?.let {
                                loadStation(it)
                                play()
                                // Increment play count
                                statsManager.incrementPlayCount(it.id)
                            }
                        } catch (e: NumberFormatException) {
                            // Invalid media ID
                        }
                    }
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_CUSTOM_SKIP) {
                        skipBuffer()
                    }
                }
            })

            isActive = true
        }
    }

    private fun updateMediaSessionMetadata(artworkBitmap: Bitmap? = null) {
        val stationName = currentStation?.name ?: "Radio App"
        val trackInfo = if (!currentTrackTitle.isNullOrBlank()) {
            currentTrackTitle
        } else {
            currentStation?.genre ?: ""
        }

        // Pour Bluetooth/Lockscreen, on garde le comportement standard (Titre = Morceau ou Station)
        val standardTitle = if (!currentTrackTitle.isNullOrBlank()) currentTrackTitle else stationName

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, standardTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentStation?.genre ?: "")

            // Pour Android Auto : Ligne 1 = Station, Ligne 2 = Titre du morceau (ou genre)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, stationName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, trackInfo)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "")

        // Gestion de la pochette / Logo
        if (artworkBitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artworkBitmap)
        } else {
            // Fallback sur le logo de la station
            currentStation?.let { station ->
                try {
                    val logoBitmap = BitmapFactory.decodeResource(resources, station.logoResId)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, logoBitmap)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, logoBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading station logo", e)
                }
            }
        }

        mediaSession.setMetadata(builder.build())
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
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_CUSTOM_SKIP,
                    "Passer pub",
                    R.drawable.ic_skip_next
                ).build()
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun createNotification(): Notification {
        Log.d(TAG, "=== createNotification START ===")
        val stationName = currentStation?.name ?: "Radio App"
        val isPlaying = if (::exoPlayer.isInitialized) exoPlayer.isPlaying else false
        val sessionDuration = getSessionDuration()
        val dataReceived = formatDataReceived()
        val bitrate = formatBitrate()

        Log.d(TAG, "Station: $stationName, isPlaying: $isPlaying")
        Log.d(TAG, "MediaSession active: ${mediaSession.isActive}")
        Log.d(TAG, "MediaSession token: ${mediaSession.sessionToken}")

        // Titre pour la notification : station + titre du morceau si disponible
        val notificationTitle = if (!currentTrackTitle.isNullOrBlank()) {
            "$stationName • $currentTrackTitle"
        } else {
            stationName
        }
        Log.d(TAG, "Notification title: $notificationTitle")

        // Texte étendu pour BigTextStyle
        val expandedText = buildString {
            if (!currentTrackTitle.isNullOrBlank()) {
                append("🎵 $currentTrackTitle\n")
            }
            append("⏱ Durée: $sessionDuration\n")
            append("📊 Données: $dataReceived\n")
            append("⚡ Débit: $bitrate\n")
            append("🎼 Codec: $audioCodec\n")
            append("🌐 Connexion: $ipVersion")
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

        Log.d(TAG, "Creating notification builder...")
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText("$sessionDuration • $dataReceived") // Texte court pour la vue réduite
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(currentArtwork ?: currentStation?.let {
                try {
                    BitmapFactory.decodeResource(resources, it.logoResId)
                } catch (e: Exception) {
                    null
                }
            })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            // Utiliser BigTextStyle pour afficher toutes les infos techniques
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(expandedText)
                .setBigContentTitle(notificationTitle)
            )

        Log.d(TAG, "Notification builder created with BigTextStyle")
        builder
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                playPausePendingIntent
            )
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )

        // Si un titre est disponible, afficher le bouton Spotify, sinon afficher "Passer pub"
        if (!currentTrackTitle.isNullOrBlank()) {
            // Intent pour rechercher dans Spotify
            val spotifyIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://open.spotify.com/search/${android.net.Uri.encode(currentTrackTitle)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val spotifyPendingIntent = PendingIntent.getActivity(
                this, 4, spotifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_search,
                "Spotify",
                spotifyPendingIntent
            )
        } else {
            // Pas de titre disponible, afficher le bouton "Passer pub"
            builder.addAction(
                R.drawable.ic_skip_next,
                "Passer pub",
                skipPendingIntent
            )
        }

        Log.d(TAG, "Building notification...")
        val notification = builder.build()
        Log.d(TAG, "Notification built successfully. ID: $NOTIFICATION_ID")
        Log.d(TAG, "=== createNotification END ===")
        return notification
    }

    private fun updateNotification() {
        if (!isSessionActive && !isForegroundServiceStarted) return

        Log.d(TAG, "=== updateNotification CALLED ===")
        val notification = createNotification()

        // Ne PAS appeler startForeground() plusieurs fois!
        // Utiliser notify() pour les mises à jour après le premier startForeground()
        if (isForegroundServiceStarted) {
            Log.d(TAG, "Using NotificationManager.notify() to update (foreground already started)")
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            Log.d(TAG, "Calling startForeground (first time)")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundServiceStarted = true
                Log.d(TAG, "startForeground SUCCESS in updateNotification")
            } catch (e: Exception) {
                Log.e(TAG, "updateNotification startForeground failed: ${e.message}", e)
            }
        }
    }

    // Android Auto / MediaBrowserService implementation
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        Log.d(TAG, "onGetRoot: clientPackageName=$clientPackageName, clientUid=$clientUid")
        // Allow all clients to browse the media library
        // In production, you might want to restrict this based on clientPackageName

        // Create extras bundle for Android Auto support
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }

        return BrowserRoot(MEDIA_ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren: parentId=$parentId")
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (parentId) {
            MEDIA_ROOT_ID -> {
                // Return a single category: Stations
                val stationsDescription = MediaDescriptionCompat.Builder()
                    .setMediaId(MEDIA_STATIONS_ID)
                    .setTitle("Stations de radio")
                    .setSubtitle("Toutes les stations")
                    .build()
                mediaItems.add(
                    MediaBrowserCompat.MediaItem(
                        stationsDescription,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                )
            }
            MEDIA_STATIONS_ID -> {
                // Return all radio stations, sorted by play count descending
                val sortedStations = getAllStations().sortedByDescending { statsManager.getPlayCount(it.id) }
                for (station in sortedStations) {
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId(station.id.toString())
                        .setTitle(station.name)
                        .setSubtitle(station.genre)
                        .setIconUri(android.net.Uri.parse("android.resource://${packageName}/${station.logoResId}"))
                        .setMediaUri(android.net.Uri.parse(station.url))
                        .build()

                    mediaItems.add(
                        MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                    )
                }
            }
        }

        result.sendResult(mediaItems)
    }

    private fun getAllStations(): List<RadioStation> {
        // Return the list of all available radio stations
        return com.radioapp.MainActivity.Companion.getAllRadioStations()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Sauvegarder les données consommées restantes avant de détruire le service
        if (currentStation != null && totalBytesReceived > lastSavedBytes) {
            val unsavedBytes = totalBytesReceived - lastSavedBytes
            statsManager.addDataConsumed(currentStation!!.id, unsavedBytes)
        }
        statsManager.stopListening() // Added this line
        mediaSession.isActive = false
        mediaSession.release()
        statsManager.cleanup()
        metadataService.cleanup()
        castManager?.release()
        castManager = null
        serviceScope.cancel()
        exoPlayer.release()
        stopForeground(true)
    }
}