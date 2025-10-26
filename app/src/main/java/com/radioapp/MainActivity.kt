package com.radioapp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import com.radioapp.adapter.RadioStationAdapter
import com.radioapp.databinding.ActivityMainBinding
import com.radioapp.model.RadioStation
import com.radioapp.service.RadioService
import com.radioapp.data.StatsManager
import com.radioapp.widget.RadioWidgetProvider
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), RadioService.RadioServiceListener {

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityMainBinding
    private var radioService: RadioService? = null
    private var serviceBound = false
    private lateinit var statsManager: StatsManager
    private lateinit var adapter: RadioStationAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var metadataResetJob: Job? = null
    private var currentMetadataTitle: String? = null
    private val metadataService = com.radioapp.data.MetadataService()
    
    private val radioStations = listOf(
        RadioStation(1, "France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3", "Généraliste", R.drawable.logo_france_inter),
        RadioStation(2, "France Culture", "https://icecast.radiofrance.fr/franceculture-midfi.mp3", "Culture", R.drawable.logo_france_culture),
        RadioStation(3, "France Info", "https://icecast.radiofrance.fr/franceinfo-midfi.mp3", "Info", R.drawable.logo_france_info),
        RadioStation(4, "RTL", "http://streaming.radio.rtl.fr/rtl-1-44-128", "Généraliste", R.drawable.logo_rtl),
        RadioStation(5, "BBC Radio 3", "http://as-hls-ww-live.akamaized.net/pool_23461179/live/ww/bbc_radio_three/bbc_radio_three.isml/bbc_radio_three-audio%3d96000.norewind.m3u8", "Classique", R.drawable.logo_bbc_radio3),
        RadioStation(6, "FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "Musique", R.drawable.logo_fip),
        RadioStation(7, "BBC Radio Scotland", "http://as-hls-ww-live.akamaized.net/pool_43322914/live/ww/bbc_radio_scotland_fm/bbc_radio_scotland_fm.isml/bbc_radio_scotland_fm-audio%3d96000.norewind.m3u8", "Scotland", R.drawable.logo_bbc_radio_scotland),
        RadioStation(8, "Radio Nova", "http://novazz.ice.infomaniak.ch/novazz-128.mp3", "Alternative", R.drawable.logo_radio_nova),
        RadioStation(9, "RFI", "http://live02.rfi.fr/rfimonde-64.mp3", "International", R.drawable.logo_rfi),
        RadioStation(10, "BBC Radio 1", "http://as-hls-ww-live.akamaized.net/pool_01505109/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio%3d96000.norewind.m3u8", "Pop", R.drawable.logo_bbc_radio1),
        RadioStation(11, "BBC World Service", "http://as-hls-ww-live.akamaized.net/pool_87948813/live/ww/bbc_world_service/bbc_world_service.isml/bbc_world_service-audio%3d96000.norewind.m3u8", "World", R.drawable.logo_bbc_world_service),
        RadioStation(12, "Radio Canada Première", "https://rcavliveaudio.akamaized.net/hls/live/2006635/P-2QMTL0_MTL/master.m3u8", "Montreal", R.drawable.logo_radio_canada_premiere),
        RadioStation(13, "France Musique", "https://icecast.radiofrance.fr/francemusique-midfi.mp3", "Classique", R.drawable.logo_france_musique),
        RadioStation(14, "Raje", "https://rajenimes.ice.infomaniak.ch/rajenimes-128.mp3", "Indé", R.drawable.logo_raje),
        RadioStation(15, "Bide et Musique", "http://relay2.bide-et-musique.com:9100/", "Insolite", R.drawable.logo_bide_et_musique),
        RadioStation(16, "So! Radio Oman 91.4", "https://listen-soradio.sharp-stream.com/soradio_high.mp3", "Rock", R.drawable.logo_so_radio_oman),
        RadioStation(17, "97 Underground", "https://s6.reliastream.com/proxy/97underground?mp=/stream", "Rock/Metal", R.drawable.logo_97_underground),
        RadioStation(18, "Pink Unicorn Radio", "https://listen6.myradio24.com/unicorn", "Rock/Metal", R.drawable.logo_pink_unicorn_radio),
        RadioStation(19, "Radio Meuh", "http://radiomeuh.ice.infomaniak.ch/radiomeuh-128.mp3", "Groove", R.drawable.logo_radio_meuh),
        RadioStation(20, "Ibiza Global Radio", "http://ibizaglobalradio.streaming-pro.com:8024/ibizaglobalradio.mp3", "Techno/House", R.drawable.logo_ibiza_global_radio),
        RadioStation(21, "WWOZ New Orleans", "https://wwoz-sc.streamguys1.com/wwoz-hi.mp3", "Jazz/Blues", R.drawable.logo_wwoz),
        RadioStation(22, "Radio Caroline", "http://sc6.radiocaroline.net:8040/;", "Rock/Pop", R.drawable.logo_radio_caroline)
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.RadioBinder
            radioService = binder.getService()
            radioService?.setListener(this@MainActivity)
            serviceBound = true
            updateUIState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Demander la permission de notification pour Android 13+
            requestNotificationPermission()

            statsManager = StatsManager(this)
            setupRecyclerView()
            setupControls()
            bindRadioService()
            startStatsUpdateTimer()
            updateTotalStats()

            // Gérer le lancement depuis le widget
            handleWidgetIntent(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur lors de l'initialisation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Mettre à jour l'intent de l'activité
        handleWidgetIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Restaurer l'état de l'UI quand on revient à l'activité
        // (par exemple depuis la notification)
        if (serviceBound && radioService != null) {
            updateUIState()
        }
    }

    private fun handleWidgetIntent(intent: Intent?) {
        val stationId = intent?.getIntExtra("station_id", -1)
        if (stationId != null && stationId > 0) {
            // Attendre que le service soit lié avant de lancer la station
            binding.root.postDelayed({
                val station = radioStations.find { it.id == stationId }
                station?.let {
                    // Vérifier si c'est la station déjà en cours
                    val currentStation = radioService?.getCurrentStation()
                    if (currentStation?.id == station.id) {
                        // Station déjà chargée, juste s'assurer qu'elle joue
                        if (!radioService!!.isPlaying()) {
                            ensureServiceStarted()
                            radioService?.play()
                            statsManager.resumeListening()
                            Toast.makeText(this, "Reprise: ${station.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Déjà en lecture: ${station.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Nouvelle station, lancer normalement
                        selectStation(it)
                    }
                }
            }, 500)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission accordée
                    Toast.makeText(this, "Notifications activées", Toast.LENGTH_SHORT).show()
                } else {
                    // Permission refusée
                    Toast.makeText(
                        this,
                        "Les notifications sont nécessaires pour contrôler la lecture depuis la barre de notification",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        // Trier les stations par utilisation dès le démarrage
        val sortedStations = radioStations.sortedByDescending { station ->
            statsManager.getPlayCount(station.id)
        }
        
        adapter = RadioStationAdapter(sortedStations, statsManager) { station ->
            selectStation(station)
        }
        
        binding.rvRadioStations.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            this.adapter = this@MainActivity.adapter
        }
    }

    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            radioService?.let { service ->
                ensureServiceStarted()
                service.play()
                service.getCurrentStation()?.let { station ->
                    statsManager.resumeListening()
                }
            }
        }

        binding.btnPause.setOnClickListener {
            radioService?.pause()
            statsManager.pauseListening()
        }

        binding.btnStop.setOnClickListener {
            radioService?.stop()
            statsManager.stopListening()
            adapter.setCurrentPlayingStation(null)

            // Réinitialiser les métadonnées et cacher la pochette
            metadataResetJob?.cancel()
            currentMetadataTitle = null
            metadataService.stopMonitoring()
            binding.ivAlbumCover.visibility = android.view.View.GONE

            binding.tvCurrentStation.text = getString(R.string.select_station)
            updateTotalStats()

            // Mettre à jour le widget (pas de station en cours)
            RadioWidgetProvider.updateWidget(this, null)
        }

        binding.btnSkipBuffer.setOnClickListener {
            radioService?.skipBuffer()
            Toast.makeText(this, "Passage de la pub - rechargement du direct...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindRadioService() {
        // Just bind to the service - it will be created on demand
        // Don't start it as a foreground service until user actually plays something
        val intent = Intent(this, RadioService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun ensureServiceStarted() {
        // Start the service as a foreground service when user wants to play
        // This must be called before play() to satisfy Android 12+ restrictions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, RadioService::class.java)
            startForegroundService(intent)
        } else {
            val intent = Intent(this, RadioService::class.java)
            startService(intent)
        }
    }

    private fun selectStation(station: RadioStation) {
        radioService?.let { service ->
            // Stop current playback if any
            statsManager.stopListening()
            service.stop()

            // Réinitialiser les métadonnées et cacher la pochette
            metadataResetJob?.cancel()
            currentMetadataTitle = null
            metadataService.stopMonitoring()
            binding.ivAlbumCover.visibility = android.view.View.GONE

            // Load new station
            service.loadStation(station)
            binding.tvCurrentStation.text = station.name
            updateTotalStats()

            // Update adapter to show current playing station
            adapter.setCurrentPlayingStation(station.id)

            // Start playing automatically
            ensureServiceStarted()
            service.play()
            statsManager.startListening(station.id)

            // Démarrer le monitoring des métadonnées pour les stations Radio France
            metadataService.startMonitoring(station.id) { metadata ->
                if (metadata != null) {
                    // Construire le texte en évitant le tiret si l'artiste est vide
                    val displayText = if (metadata.artist.isNotBlank()) {
                        "${metadata.artist} - ${metadata.title}"
                    } else {
                        metadata.title
                    }
                    binding.tvCurrentStation.text = displayText

                    // Afficher la pochette si disponible
                    if (metadata.coverBitmap != null) {
                        binding.ivAlbumCover.setImageBitmap(metadata.coverBitmap)
                        binding.ivAlbumCover.visibility = android.view.View.VISIBLE
                    } else {
                        binding.ivAlbumCover.visibility = android.view.View.GONE
                    }

                    // Annuler le timer précédent
                    metadataResetJob?.cancel()
                    currentMetadataTitle = displayText

                    // Timer de 1 minute pour revenir au nom de la station
                    metadataResetJob = scope.launch {
                        delay(60000)
                        currentMetadataTitle = null
                        binding.tvCurrentStation.text = station.name
                        binding.ivAlbumCover.visibility = android.view.View.GONE
                    }
                }
            }

            // Mettre à jour le widget avec la station en cours
            RadioWidgetProvider.updateWidget(this, station.id)

            Toast.makeText(this, "Lecture: ${station.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUIState() {
        try {
            radioService?.let { service ->
                val isPlaying = service.isPlaying()
                binding.btnPlay.isEnabled = !isPlaying
                binding.btnPause.isEnabled = isPlaying
                binding.btnStop.isEnabled = isPlaying

                service.getCurrentStation()?.let { station ->
                    binding.tvCurrentStation.text = station.name

                    // Restaurer l'indicateur visuel de la station en cours
                    if (::adapter.isInitialized) {
                        adapter.setCurrentPlayingStation(station.id)
                    }

                    // Redémarrer le tracking des stats si la radio est en train de jouer
                    // (par exemple après une rotation d'écran)
                    if (isPlaying && ::statsManager.isInitialized) {
                        statsManager.restoreListening(station.id)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            binding.btnPlay.isEnabled = !isPlaying
            binding.btnPause.isEnabled = isPlaying
            binding.btnStop.isEnabled = isPlaying || radioService?.getCurrentStation() != null
            binding.btnSkipBuffer.isEnabled = isPlaying
        }
    }

    override fun onBufferingUpdate(bufferedPercentage: Int) {
        runOnUiThread {
            updateTotalStats()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            binding.btnPlay.isEnabled = true
            binding.btnPause.isEnabled = false
        }
    }

    override fun onMetadataChanged(title: String?, artworkUri: String?) {
        runOnUiThread {
            // Annuler le timer précédent
            metadataResetJob?.cancel()

            if (title != null && title.isNotBlank()) {
                // Parser le titre pour séparer artiste et titre si format "Artiste - Titre"
                val displayText = if (title.contains(" - ")) {
                    val parts = title.split(" - ", limit = 2)
                    if (parts[0].isNotBlank()) {
                        title // Format "Artiste - Titre"
                    } else {
                        parts[1] // Juste le titre si commence par " - "
                    }
                } else {
                    title
                }

                currentMetadataTitle = displayText
                binding.tvCurrentStation.text = displayText

                // Cacher la pochette car ICY ne fournit pas d'images
                binding.ivAlbumCover.visibility = android.view.View.GONE

                // Démarrer un timer de 1 minute pour revenir au nom de la station
                metadataResetJob = scope.launch {
                    delay(60000) // 60 secondes
                    currentMetadataTitle = null
                    val station = radioService?.getCurrentStation()
                    if (station != null) {
                        binding.tvCurrentStation.text = station.name
                    }
                }
            }
        }
    }

    private fun startStatsUpdateTimer() {
        scope.launch {
            while (isActive) {
                delay(10000) // 10 secondes
                adapter.updateStats()
                updateTotalStats()

                // Mettre à jour le widget avec la station en cours
                val currentStationId = radioService?.getCurrentStation()?.id
                RadioWidgetProvider.updateWidget(this@MainActivity, currentStationId)
            }
        }
    }

    private fun updateTotalStats() {
        // Calculer le nombre total de plays, le temps total d'écoute et les données consommées
        var totalPlays = 0
        var totalTime = 0L
        var totalData = 0L

        radioStations.forEach { station ->
            totalPlays += statsManager.getPlayCount(station.id)
            totalTime += statsManager.getListeningTime(station.id)
            totalData += statsManager.getDataConsumed(station.id)
        }

        val formattedTime = statsManager.formatListeningTime(totalTime)
        val formattedData = statsManager.formatDataSize(totalData)
        binding.tvTotalStats.text = "Total: $totalPlays plays • $formattedTime • $formattedData"
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataResetJob?.cancel()
        metadataService.cleanup()
        scope.cancel()
        statsManager.stopListening()
        statsManager.cleanup()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}