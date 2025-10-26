package com.radioapp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val coverBitmap: Bitmap? = null
)

class MetadataService {
    private var metadataJob: Job? = null
    private var onMetadataUpdate: ((TrackMetadata?) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // IDs des stations Radio France
    private val radioFranceStations = mapOf(
        1 to 1,    // France Inter (api id: 1)
        2 to 5,    // France Culture (api id: 5)
        3 to 47,   // France Info (api id: 47)
        6 to 7,    // FIP (api id: 7 - station principale)
        13 to 4    // France Musique (api id: 4)
    )

    // IDs des stations BBC
    private val bbcStations = mapOf(
        5 to "bbc_radio_three",    // BBC Radio 3
        7 to "bbc_radio_scotland_fm",  // BBC Radio Scotland
        10 to "bbc_radio_one"  // BBC Radio 1
    )

    fun startMonitoring(stationId: Int, callback: (TrackMetadata?) -> Unit) {
        stopMonitoring()
        onMetadataUpdate = callback

        val radioFranceId = radioFranceStations[stationId]
        val bbcServiceId = bbcStations[stationId]

        if (radioFranceId != null) {
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
        }
    }

    fun stopMonitoring() {
        metadataJob?.cancel()
        metadataJob = null
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
                        val album = step.optString("titreAlbum", "")
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

                            return@withContext TrackMetadata(
                                title = title,
                                artist = artist,
                                album = album.ifEmpty { null },
                                coverUrl = coverUrl.ifEmpty { null },
                                coverBitmap = bitmap
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
                    val imageUrlObj = segment.optJSONObject("image_url")
                    if (imageUrlObj != null) {
                        imageUrlTemplate = imageUrlObj.optString("template", null)
                    }

                    // Si pas trouvé, essayer synopses/image_url
                    if (imageUrlTemplate == null || imageUrlTemplate.isEmpty()) {
                        val synopses = segment.optJSONObject("synopses")
                        val imageObj = synopses?.optJSONObject("image_url")
                        if (imageObj != null) {
                            imageUrlTemplate = imageObj.optString("template", null)
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
                            coverBitmap = bitmap
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
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "RadioApp/1.0")
            val inputStream = connection.getInputStream()
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
