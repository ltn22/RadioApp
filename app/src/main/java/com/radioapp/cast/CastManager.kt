package com.radioapp.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.radioapp.data.TrackMetadata
import com.radioapp.model.RadioStation

/**
 * Manages Google Cast sessions and playback for RadioApp.
 * Handles switching between local ExoPlayer and remote CastPlayer.
 */
class CastManager(context: Context) {

    companion object {
        private const val TAG = "CastManager"
    }

    private val castContext: CastContext = CastContext.getSharedInstance(context)
    private val sessionManager: SessionManager = castContext.sessionManager

    var listener: CastManagerListener? = null
    var currentStation: RadioStation? = null
    var currentMetadata: TrackMetadata? = null

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "Cast session started: $sessionId, currentStation=${currentStation?.name}")
            listener?.onCastSessionStarted()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d(TAG, "Cast session ended with error code: $error")
            listener?.onCastSessionEnded()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d(TAG, "Cast session resumed, wasSuspended: $wasSuspended, currentStation=${currentStation?.name}")
            listener?.onCastSessionStarted()
        }

        override fun onSessionStarting(session: CastSession) {
            Log.d(TAG, "Cast session starting...")
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session start failed with error: $error")
        }

        override fun onSessionEnding(session: CastSession) {
            Log.d(TAG, "Cast session ending...")
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            Log.d(TAG, "Cast session resuming: $sessionId")
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session resume failed with error: $error")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.d(TAG, "Cast session suspended with reason: $reason")
        }
    }

    init {
        sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }

    private fun getRemoteMediaClient(): RemoteMediaClient? {
        return sessionManager.currentCastSession?.remoteMediaClient
    }

    /**
     * Check if a Cast session is currently active and connected.
     */
    fun isCastSessionActive(): Boolean {
        return sessionManager.currentCastSession?.isConnected == true
    }

    /**
     * Load a radio station on the Chromecast device.
     * @param station The radio station to stream
     * @param metadata Optional track metadata for display on TV
     */
    fun loadStationOnCast(station: RadioStation, metadata: TrackMetadata? = null) {
        Log.d(TAG, "loadStationOnCast called: ${station.name}")
        currentStation = station
        currentMetadata = metadata

        val remoteMediaClient = getRemoteMediaClient()
        if (remoteMediaClient == null) {
            Log.e(TAG, "No RemoteMediaClient available! Session connected: ${sessionManager.currentCastSession?.isConnected}")
            return
        }

        Log.d(TAG, "RemoteMediaClient available, loading station: ${station.name}")

        // Build Cast MediaMetadata
        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, metadata?.title ?: station.name)
            putString(MediaMetadata.KEY_ARTIST, metadata?.artist ?: station.genre)
            putString(MediaMetadata.KEY_ALBUM_TITLE, metadata?.album ?: station.name)

            // Add cover image if available (Chromecast will fetch from URL)
            metadata?.coverUrl?.let { url ->
                if (url.isNotEmpty() && url.startsWith("http")) {
                    Log.d(TAG, "Adding cover image URL: $url")
                    addImage(WebImage(Uri.parse(url)))
                }
            }
        }

        // Determine content type based on URL
        val contentType = when {
            station.url.contains(".m3u8") -> "application/x-mpegURL"
            station.url.contains(".aac") -> "audio/aac"
            else -> "audio/mpeg"
        }

        val mediaInfo = MediaInfo.Builder(station.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(castMetadata)
            .build()

        Log.d(TAG, "MediaInfo built: url=${station.url}, contentType=$contentType")

        // Use RemoteMediaClient to load media on Chromecast
        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        remoteMediaClient.load(loadRequest)
            .setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.d(TAG, "Cast load SUCCESS for: ${station.name}")
                } else {
                    Log.e(TAG, "Cast load FAILED: ${result.status.statusCode} - ${result.status.statusMessage}")
                }
            }

        Log.d(TAG, "Station load request sent to Cast: ${station.name}")
    }

    /**
     * Update the metadata displayed on the Chromecast.
     * Called when track info changes (new song, etc.)
     */
    fun updateMetadataOnCast(metadata: TrackMetadata) {
        currentMetadata = metadata
        currentStation?.let { station ->
            if (isCastSessionActive()) {
                Log.d(TAG, "Updating metadata on Cast: ${metadata.artist} - ${metadata.title}")
                // Reload with new metadata to update the display
                loadStationOnCast(station, metadata)
            }
        }
    }

    /**
     * Start playback on the Cast device.
     */
    fun play() {
        getRemoteMediaClient()?.let { client ->
            Log.d(TAG, "Playing on Cast")
            client.play()
        }
    }

    /**
     * Pause playback on the Cast device.
     */
    fun pause() {
        getRemoteMediaClient()?.let { client ->
            Log.d(TAG, "Pausing on Cast")
            client.pause()
        }
    }

    /**
     * Stop playback on the Cast device.
     */
    fun stop() {
        getRemoteMediaClient()?.let { client ->
            Log.d(TAG, "Stopping on Cast")
            client.stop()
        }
    }

    /**
     * Set the volume of the Cast device.
     * @param volume Volume level between 0.0 and 1.0
     */
    fun setVolume(volume: Double) {
        try {
             sessionManager.currentCastSession?.let { session ->
                 Log.d(TAG, "Setting Cast volume to: $volume")
                 session.volume = volume
             }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Cast volume: ${e.message}")
        }
    }

    /**
     * Get the name of the currently connected Cast device.
     */
    fun getConnectedDeviceName(): String? {
        return sessionManager.currentCastSession?.castDevice?.friendlyName
    }

    /**
     * Release resources. Call this when the service is destroyed.
     */
    fun release() {
        Log.d(TAG, "Releasing CastManager resources")
        sessionManager.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }

    /**
     * Listener interface for Cast session events.
     */
    interface CastManagerListener {
        fun onCastSessionStarted()
        fun onCastSessionEnded()
    }
}
