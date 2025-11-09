package com.radioapp.network

import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.Socket
import java.net.URL

class IpDetectingHttpDataSource(
    userAgent: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    allowCrossProtocolRedirects: Boolean,
    private val onIpVersionDetected: (String) -> Unit,
    transferListener: TransferListener?
) : DefaultHttpDataSource(
    userAgent,
    connectTimeoutMs,
    readTimeoutMs,
    allowCrossProtocolRedirects,
    null  // defaultRequestProperties
) {

    init {
        // Attacher le TransferListener après construction
        transferListener?.let { addTransferListener(it) }
    }

    private var ipDetected = false

    override fun open(dataSpec: DataSpec): Long {
        val result = super.open(dataSpec)

        // Détecter l'IP seulement une fois par station, en arrière-plan
        if (!ipDetected) {
            ipDetected = true
            Thread {
                try {
                    // Extraire le host depuis l'URL
                    val url = URL(dataSpec.uri.toString())
                    val host = url.host
                    val port = if (url.port != -1) url.port else if (url.protocol == "https") 443 else 80

                    // Créer une socket de test pour voir quelle adresse IP est réellement utilisée
                    val socket = Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), 5000)

                    // Récupérer l'adresse IP distante réellement connectée
                    val remoteAddress = socket.inetAddress

                    // Déterminer le type d'adresse IP
                    val ipVersion = when (remoteAddress) {
                        is Inet6Address -> "IPv6"
                        is Inet4Address -> "IPv4"
                        else -> "N/A"
                    }

                    socket.close()
                    onIpVersionDetected(ipVersion)
                } catch (e: Exception) {
                    // En cas d'erreur, signaler N/A
                    e.printStackTrace()
                    onIpVersionDetected("N/A")
                }
            }.start()
        }

        return result
    }

    class Factory(
        private val userAgent: String,
        private val connectTimeoutMs: Int,
        private val readTimeoutMs: Int,
        private val allowCrossProtocolRedirects: Boolean,
        private val onIpVersionDetected: (String) -> Unit,
        private val transferListener: TransferListener?
    ) : HttpDataSource.Factory {

        override fun createDataSource(): HttpDataSource {
            return IpDetectingHttpDataSource(
                userAgent,
                connectTimeoutMs,
                readTimeoutMs,
                allowCrossProtocolRedirects,
                onIpVersionDetected,
                transferListener
            )
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
            return this
        }
    }
}
