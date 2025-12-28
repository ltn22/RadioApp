package com.radioapp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val coverBitmap: Bitmap? = null,
    val programUrl: String? = null  // URL du programme (Radio France émissions)
)

class MetadataService {
    private var metadataJob: Job? = null
    private var onMetadataUpdate: ((TrackMetadata?) -> Unit)? = null
    var onSearchStatus: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var aiirWebSocket: WebSocket? = null
    private var lastAIIRConnectionTime = 0L  // Prevent duplicate AIIR connections
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // IDs des stations Radio France
    private val radioFranceStations = mapOf(
        1 to 1,    // France Inter (api id: 1)
        2 to 5,    // France Culture (api id: 5)
        // 3 to 47,   // France Info - désactivé, affiche uniquement le nom de la radio
        6 to 7,    // FIP (api id: 7 - station principale)
        13 to 4    // France Musique (api id: 4)
    )

    // IDs des stations BBC
    private val bbcStations = mapOf(
        5 to "bbc_radio_three",    // BBC Radio 3
        7 to "bbc_radio_scotland_fm",  // BBC Radio Scotland
        10 to "bbc_radio_one",  // BBC Radio 1
        36 to "bbc_radio_fourfm" // BBC Radio 4
    )

    // Stations qui tentent de récupérer les métadonnées depuis web scraping
    private val webScrapingStations = mapOf(
        15 to "bide"  // Bide et Musique - via radio-info.php
    )

    // Stations qui utilisent la websocket AIIR
    // serviceId to originUrl mapping for AIIR services
    private val aiirStations = mapOf(
        16 to "4425"  // So Radio Oman - AIIR serviceId
    )

    // Origin URLs for AIIR services
    private val aiirOrigins = mapOf(
        "4425" to "https://www.soradiooman.com"  // So Radio Oman
    )

    // Stations de radio directe sans metadata (affiche "En direct")
    private val liveOnlyStations = setOf<Int>(
        // Stations without any metadata service
    )

    fun startMonitoring(stationId: Int, callback: (TrackMetadata?) -> Unit) {
        stopMonitoring()
        onMetadataUpdate = callback

        Log.d("MetadataService", "=== startMonitoring called for stationId=$stationId ===")

        val radioFranceId = radioFranceStations[stationId]
        val bbcServiceId = bbcStations[stationId]
        val scrapingKey = webScrapingStations[stationId]
        val aiirServiceId = aiirStations[stationId]
        val isLiveOnly = liveOnlyStations.contains(stationId)

        Log.d("MetadataService", "Station checks: radioFrance=$radioFranceId, bbc=$bbcServiceId, scraping=$scrapingKey, aiir=$aiirServiceId, liveOnly=$isLiveOnly")

        if (isLiveOnly) {
            // Pour les stations en direct sans métadonnées officielles
            Log.d("MetadataService", "Station $stationId has no official metadata service - displaying 'Live'")
            // Afficher "En direct" ou le nom de la station
            // Le RadioService affichera le titre depuis les métadonnées ICY si disponibles
        } else if (radioFranceId != null) {
            metadataJob = scope.launch {
                while (isActive) {
                    try {
                        val metadata = fetchRadioFranceMetadata(radioFranceId)
                        withContext(Dispatchers.Main) {
                            onMetadataUpdate?.invoke(metadata)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(15000) // Vérifier toutes les 15 secondes
                }
            }
        } else if (bbcServiceId != null) {
            metadataJob = scope.launch {
                while (isActive) {
                    try {
                        val metadata = fetchBBCMetadata(bbcServiceId)
                        withContext(Dispatchers.Main) {
                            onMetadataUpdate?.invoke(metadata)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(15000) // Vérifier toutes les 15 secondes
                }
            }
        } else if (scrapingKey != null) {
            metadataJob = scope.launch {
                while (isActive) {
                    try {
                        val metadata = fetchWebMetadata(scrapingKey)
                        withContext(Dispatchers.Main) {
                            onMetadataUpdate?.invoke(metadata)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(20000) // Vérifier toutes les 20 secondes
                }
            }
        } else if (aiirServiceId != null) {
            connectToAIIRWebSocket(aiirServiceId)
        }
    }

    fun stopMonitoring() {
        metadataJob?.cancel()
        metadataJob = null
        aiirWebSocket?.close(1000, "Stopping monitoring")
        aiirWebSocket = null
    }

    fun cleanup() {
        stopMonitoring()
        scope.cancel()
    }

    private suspend fun fetchRadioFranceMetadata(stationId: Int): TrackMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.radiofrance.fr/livemeta/pull/$stationId")
                val connection = url.openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioApp/1.0")

                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val data = JSONObject(json)

                // Parcourir les steps pour trouver le morceau en cours
                val steps = data.optJSONObject("steps") ?: return@withContext null
                val currentTime = System.currentTimeMillis() / 1000.0

                val keys = steps.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val step = steps.getJSONObject(key)
                    val start = step.optDouble("start", 0.0)
                    val end = step.optDouble("end", 0.0)

                    // Si le morceau est en cours (avec marge de 5 secondes)
                    if (start <= currentTime && currentTime <= (end + 5)) {
                        var title = step.optString("title", "")
                        var artist = step.optString("authors", "")
                        var album = step.optString("titreAlbum", "")
                        // Utiliser titleConcept comme fallback pour le nom du programme
                        if (album.isEmpty()) {
                            album = step.optString("titleConcept", "")
                        }
                        var coverUrl = step.optString("visual", "")

                        // Pour FIP et autres, essayer aussi "annonceur" pour l'artiste
                        if (artist.isEmpty()) {
                            artist = step.optString("annonceur", "")
                        }

                        // Essayer aussi "personnalites" si vide
                        if (artist.isEmpty()) {
                            val personnalites = step.optJSONArray("personnalites")
                            if (personnalites != null && personnalites.length() > 0) {
                                val firstPerson = personnalites.getJSONObject(0)
                                artist = firstPerson.optString("nom", "")
                            }
                        }

                        // Nettoyer le titre et l'artiste
                        title = title.trim()
                        artist = artist.trim()

                        if (title.isNotEmpty() || artist.isNotEmpty()) {
                            // Télécharger la pochette si disponible
                            val bitmap = if (coverUrl.isNotEmpty() && coverUrl.startsWith("http")) {
                                downloadImage(coverUrl)
                            } else null

                            // Récupérer l'URL du programme/émission si disponible
                            // Utiliser le champ "path" pour construire l'URL de l'émission
                            var programUrl: String? = null
                            val path = step.optString("path", null)
                            if (!path.isNullOrEmpty()) {
                                programUrl = "https://www.radiofrance.fr/$path"
                            }

                            Log.d("MetadataService", "Radio France metadata - Title: '$title', Artist: '$artist', Program URL: $programUrl")

                            return@withContext TrackMetadata(
                                title = title,
                                artist = artist,
                                album = album.ifEmpty { null },
                                coverUrl = coverUrl.ifEmpty { null },
                                coverBitmap = bitmap,
                                programUrl = programUrl
                            )
                        }
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun fetchBBCMetadata(serviceId: String): TrackMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://rms.api.bbc.co.uk/v2/services/$serviceId/segments/latest?experience=domestic&limit=1")
                val connection = url.openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioApp/1.0")

                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val data = JSONObject(json)

                // Récupérer le premier segment (morceau en cours)
                val segments = data.optJSONArray("data")
                if (segments != null && segments.length() > 0) {
                    val segment = segments.getJSONObject(0)

                    // Extraire les informations du morceau
                    val titles = segment.optJSONObject("titles")
                    val title = titles?.optString("primary", "") ?: ""
                    val artist = titles?.optString("secondary", "") ?: ""

                    // Extraire l'image - essayer plusieurs chemins possibles
                    var imageUrlTemplate: String? = null

                    // Essayer image_url
                    // D'abord voir si c'est une string directe (nouveau format)
                    val imageUrlString = segment.optString("image_url", "")
                    if (imageUrlString.isNotEmpty() && imageUrlString.contains("{recipe}")) {
                        imageUrlTemplate = imageUrlString
                    } else {
                        // Sinon essayer comme un objet (ancien format)
                        val imageUrlObj = segment.optJSONObject("image_url")
                        if (imageUrlObj != null) {
                            imageUrlTemplate = imageUrlObj.optString("template", "")
                        }
                    }

                    // Si pas trouvé, essayer synopses/image_url
                    if (imageUrlTemplate == null || imageUrlTemplate.isEmpty()) {
                        val synopses = segment.optJSONObject("synopses")
                        val imageObj = synopses?.optJSONObject("image_url")
                        if (imageObj != null) {
                            imageUrlTemplate = imageObj.optString("template", "")
                        }
                    }

                    // Construire l'URL finale de l'image
                    val finalImageUrl = if (!imageUrlTemplate.isNullOrEmpty()) {
                        imageUrlTemplate.replace("{recipe}", "400x400")
                    } else null

                    if (title.isNotEmpty()) {
                        // Télécharger la pochette si disponible
                        val bitmap = if (finalImageUrl != null && finalImageUrl.startsWith("http")) {
                            downloadImage(finalImageUrl)
                        } else null

                        return@withContext TrackMetadata(
                            title = title,
                            artist = artist,
                            album = null,
                            coverUrl = finalImageUrl,
                            coverBitmap = bitmap,
                            programUrl = null
                        )
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun downloadImage(urlString: String): Bitmap? {
        return try {
            Log.d("MetadataService", "Downloading image from: $urlString")
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "RadioApp/1.0")
            val inputStream = connection.getInputStream()
            // Utiliser BitmapFactory.Options pour éviter le warning "pinning deprecated"
            val options = BitmapFactory.Options().apply {
                inMutable = false  // Bitmap immutable pour Android Q+
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            Log.d("MetadataService", "Image downloaded successfully: ${bitmap != null}")
            bitmap
        } catch (e: Exception) {
            Log.e("MetadataService", "Error downloading image: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchCoverFromItunesPublic(artist: String, trackTitle: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val searchQuery = "$artist $trackTitle".replace(" ", "+")
                val url = URL("https://itunes.apple.com/search?term=$searchQuery&media=music&limit=1")
                val connection = url.openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioApp/1.0")

                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val data = JSONObject(json)

                val results = data.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val firstResult = results.getJSONObject(0)
                    val url600 = firstResult.optString("artworkUrl600", "")
                    if (url600.isNotEmpty()) {
                        return@withContext url600
                    }
                    val url100 = firstResult.optString("artworkUrl100", "")
                    if (url100.isNotEmpty()) {
                        return@withContext url100
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun downloadImagePublic(urlString: String): Bitmap? {
        return downloadImage(urlString)
    }

    private suspend fun fetchWebMetadata(stationKey: String): TrackMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                when (stationKey) {
                    "bide" -> fetchBideEtMusiqueMetadata()
                    else -> null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun fetchBideEtMusiqueMetadata(): TrackMetadata? {
        return try {
            Log.d("MetadataService", "Fetching Bide et Musique metadata from radio-info.php...")

            // Récupérer depuis la page radio-info.php qui contient les métadonnées en temps réel
            val url = URL("https://www.bide-et-musique.com/radio-info.php")
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val html = connection.getInputStream().bufferedReader().use { it.readText() }
            Log.d("MetadataService", "HTML fetched, length=${html.length}")

            // Log du HTML pour déboguer
            if (html.length < 2000) {
                Log.d("MetadataService", "HTML content: $html")
            } else {
                Log.d("MetadataService", "HTML (first 500 chars): ${html.take(500)}")
            }

            // Chercher l'artiste et le titre dans le HTML
            var artist = ""
            var title = ""

            // Normaliser le HTML en supprimant les newlines et les espaces multiples pour faciliter le parsing
            val normalizedHtml = html.replace("\n", " ").replace(Regex("""\s+"""), " ")

            // Extraire le titre depuis <p class="titre-song"><a>Titre</a>
            val titlePattern = """<p\s+class="titre-song"[^>]*>[^<]*<a[^>]*>([^<]+)</a>""".toRegex()
            val titleMatch = titlePattern.find(normalizedHtml)
            if (titleMatch != null && titleMatch.groupValues.size >= 2) {
                title = titleMatch.groupValues[1].trim()
                Log.d("MetadataService", "Extracted title: '$title'")
            }

            // Extraire l'artiste depuis <p class="titre-song2"><a>Artiste</a>
            val artistPattern = """<p\s+class="titre-song2"[^>]*>[^<]*<a[^>]*>([^<]+)</a>""".toRegex()
            val artistMatch = artistPattern.find(normalizedHtml)
            if (artistMatch != null && artistMatch.groupValues.size >= 2) {
                artist = artistMatch.groupValues[1].trim()
                Log.d("MetadataService", "Extracted artist: '$artist'")
            }

            if (artist.isNotEmpty() && title.isNotEmpty()) {
                // Essayer de récupérer la pochette depuis iTunes
                val coverUrl = fetchCoverFromItunesPublic(artist, title)
                val bitmap = if (coverUrl != null) {
                    downloadImage(coverUrl)
                } else null

                return TrackMetadata(
                    title = title,
                    artist = artist,
                    album = null,
                    coverUrl = coverUrl,
                    coverBitmap = bitmap,
                    programUrl = null
                )
            } else {
                Log.d("MetadataService", "Could not parse artist/title from HTML")
            }
            null
        } catch (e: Exception) {
            Log.e("MetadataService", "Error fetching Bide metadata", e)
            e.printStackTrace()
            null
        }
    }

    private fun connectToAIIRWebSocket(stationId: String) {
        Log.d("MetadataService", "=== connectToAIIRWebSocket CALLED for station: $stationId ===")

        // Prevent duplicate rapid connections (< 500ms apart)
        val now = System.currentTimeMillis()
        if (now - lastAIIRConnectionTime < 500) {
            Log.d("MetadataService", "Ignoring duplicate AIIR connection within 500ms")
            return
        }
        lastAIIRConnectionTime = now

        // Close any existing AIIR WebSocket before creating a new one
        if (aiirWebSocket != null) {
            Log.d("MetadataService", "Closing existing AIIR WebSocket")
            aiirWebSocket?.close(1000, "New connection")
            aiirWebSocket = null
        }

        val wsUrl = "wss://metadata.aiir.net/now-playing"

        // Get the Origin URL for this service (default to soradiooman.com for any 4425 serviceId)
        val originUrl = aiirOrigins[stationId] ?: "https://www.soradiooman.com"

        Log.d("MetadataService", "Connecting to AIIR WebSocket with Origin: $originUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("User-Agent", "RadioApp/1.0")
            .addHeader("Origin", originUrl)
            .build()

        aiirWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("MetadataService", "AIIR WebSocket connected successfully for station: $stationId")
                Log.d("MetadataService", "Connected to AIIR now-playing feed")

                // Log response details for debugging
                Log.d("MetadataService", "WebSocket response code: ${response.code}")
                Log.d("MetadataService", "WebSocket response message: ${response.message}")
                val headers = response.headers
                for (i in 0 until headers.size) {
                    Log.d("MetadataService", "Response header ${i}: ${headers.name(i)} = ${headers.value(i)}")
                }

                // Send subscription message with the serviceId
                scope.launch {
                    try {
                        // The stationId is actually the AIIR serviceId (e.g., "4425" for So Radio)
                        val subscribeMsg = JSONObject().apply {
                            put("action", "subscribe")
                            put("serviceId", stationId)
                        }

                        val msgString = subscribeMsg.toString()
                        webSocket.send(msgString)
                        Log.d("MetadataService", "Sent subscription: $msgString")
                    } catch (e: Exception) {
                        Log.e("MetadataService", "Error sending subscription message", e)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("MetadataService", "AIIR WebSocket message received (${text.length} chars): ${text.take(200)}")
                scope.launch {
                    try {
                        val metadata = parseAIIRMetadata(text)
                        if (metadata != null) {
                            Log.d("MetadataService", "Successfully parsed AIIR metadata")
                            Log.d("MetadataService", "onMetadataUpdate callback is null? ${onMetadataUpdate == null}")
                            MainScope().launch {
                                Log.d("MetadataService", "Calling onMetadataUpdate with: ${metadata.artist} - ${metadata.title}")
                                onMetadataUpdate?.invoke(metadata)
                                Log.d("MetadataService", "onMetadataUpdate callback completed")
                            }
                        } else {
                            Log.d("MetadataService", "Could not parse AIIR metadata from message")
                        }
                    } catch (e: Exception) {
                        Log.e("MetadataService", "Error parsing AIIR message", e)
                        e.printStackTrace()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("MetadataService", "AIIR WebSocket error: ${t.message}")
                Log.e("MetadataService", "Error cause: ${t.cause}")
                if (response != null) {
                    Log.e("MetadataService", "Response code: ${response.code}, message: ${response.message}")
                }
                t.printStackTrace()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("MetadataService", "AIIR WebSocket closed: code=$code, reason=$reason")
                aiirWebSocket = null
            }
        })
    }

    private suspend fun parseAIIRMetadata(jsonString: String): TrackMetadata? {
        return try {
            // Handle error messages from the server
            if (jsonString.contains("not recognised") || jsonString.contains("not recognized") ||
                jsonString.startsWith("Service") || jsonString.startsWith("Error")) {
                Log.d("MetadataService", "Server error message: $jsonString")
                return null
            }

            val json = JSONObject(jsonString)

            // La structure AIIR: {"nowPlaying": {"artist": "...", "title": "..."}, "nowProgramme": {"name": "..."}}
            var title = ""
            var artist = ""
            var albumProgram: String? = null
            var coverUrl: String? = null

            // AIIR format - nowPlaying object with artist and title
            if (json.has("nowPlaying")) {
                val nowPlaying = json.getJSONObject("nowPlaying")
                title = nowPlaying.optString("title", "")
                artist = nowPlaying.optString("artist", "")
                coverUrl = nowPlaying.optString("image", null)
            }
            // Also check for nowProgramme (programme en cours) - this has the cover image!
            if (json.has("nowProgramme")) {
                val nowProgramme = json.getJSONObject("nowProgramme")
                albumProgram = nowProgramme.optString("name", null)
                // Programme image URL - use this as cover if not found elsewhere
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = nowProgramme.optString("imageUrl", null)
                }
            }
            // Fallback to older format if needed
            if (title.isEmpty() && json.has("track")) {
                val track = json.getJSONObject("track")
                title = track.optString("title", "")
                artist = track.optString("artist", "")
                if (json.has("image")) {
                    coverUrl = json.optString("image", null)
                }
            }
            // Fallback: try fields at root level
            if (title.isEmpty()) {
                title = json.optString("title", "")
                artist = json.optString("artist", "")
                coverUrl = json.optString("image", null)
            }

            if (title.isNotEmpty() || artist.isNotEmpty()) {
                Log.d("MetadataService", "Parsed AIIR metadata - Artist: '$artist', Title: '$title'")

                // Try to get cover from iTunes (use title and artist, not programme image)
                coverUrl = null  // Don't use programme image
                if (artist.isNotEmpty() && title.isNotEmpty()) {
                    coverUrl = fetchCoverFromItunesPublic(artist, title)
                    Log.d("MetadataService", "iTunes cover URL: $coverUrl")
                }

                // Télécharger la pochette si disponible
                val bitmap = if (!coverUrl.isNullOrEmpty() && coverUrl.startsWith("http")) {
                    downloadImage(coverUrl)
                } else null

                return TrackMetadata(
                    title = title.trim(),
                    artist = artist.trim(),
                    album = albumProgram,  // Use the programme name if available
                    coverUrl = coverUrl,
                    coverBitmap = bitmap
                )
            }
            null
        } catch (e: Exception) {
            Log.e("MetadataService", "Error parsing AIIR metadata", e)
            null
        }
    }
}
