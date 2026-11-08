package com.homepanel.kiosk

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Client MQTT 3.1.1 minimal (sans bibliothèque) : connexion avec identifiants et
 * « testament » (disponibilité), abonnement, publication QoS 0 (avec retain),
 * keep-alive et reconnexion automatique. Les rappels arrivent sur le thread principal.
 */
class MqttClient(private val listener: Listener) {
    interface Listener {
        fun onMqttConnected()
        fun onMqttDisconnected(error: String?)
        fun onMqttMessage(topic: String, payload: String)
    }

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "mqtt-write").apply { isDaemon = true } }
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var running = false
    private var gen = 0

    private var host = ""
    private var port = 1883
    private var user = ""
    private var pass = ""
    private var clientId = ""
    private var willTopic = ""

    fun start(host: String, port: Int, user: String, pass: String, clientId: String, willTopic: String) {
        stop()
        this.host = host; this.port = port; this.user = user; this.pass = pass
        this.clientId = clientId; this.willTopic = willTopic
        running = true
        val myGen = ++gen
        Thread({ loop(myGen) }, "mqtt").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        gen++
        connected = false
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    /** Publication QoS 0 (ignorée si déconnecté). */
    fun publish(topic: String, payload: String, retain: Boolean = false) {
        if (!connected) return
        val body = ByteArrayOutputStream()
        writeString(body, topic)
        body.write(payload.toByteArray(Charsets.UTF_8))
        send(0x30 or (if (retain) 1 else 0), body.toByteArray())
    }

    fun subscribe(topic: String) {
        if (!connected) return
        val body = ByteArrayOutputStream()
        body.write(0); body.write(1) // identifiant de paquet
        writeString(body, topic)
        body.write(0) // QoS 0
        send(0x82, body.toByteArray())
    }

    // ------------------------------------------------------------ interne

    private fun loop(myGen: Int) {
        var backoff = 2000L
        while (running && myGen == gen) {
            try {
                session(myGen)
                backoff = 2000L
            } catch (e: java.io.EOFException) {
                lastError = if (connected) tr("connexion coupée par le broker") else tr("le broker a refusé la connexion (identifiants ?)")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            val wasConnected = connected
            connected = false
            try { socket?.close() } catch (_: IOException) {}
            if (myGen != gen) return
            val err = lastError
            main.post { listener.onMqttDisconnected(err) }
            if (!running) return
            if (!wasConnected) backoff = min(backoff * 2, 60_000L)
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
        }
    }

    private fun session(myGen: Int) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 10_000)
        s.soTimeout = 20_000
        s.tcpNoDelay = true
        socket = s
        out = BufferedOutputStream(s.getOutputStream())
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))

        // CONNECT
        val body = ByteArrayOutputStream()
        writeString(body, "MQTT")
        body.write(4) // MQTT 3.1.1
        var flags = 0x02 or 0x04 or 0x20 // session propre, testament, testament retenu
        if (user.isNotEmpty()) flags = flags or 0x80
        // MQTT 3.1.1 : un mot de passe n'est permis qu'avec un nom d'utilisateur.
        if (pass.isNotEmpty() && user.isNotEmpty()) flags = flags or 0x40
        body.write(flags)
        body.write(0); body.write(30) // keep-alive 30 s
        writeString(body, clientId)
        writeString(body, willTopic)
        writeString(body, "offline")
        if (user.isNotEmpty()) writeString(body, user)
        if (pass.isNotEmpty() && user.isNotEmpty()) writeString(body, pass)
        writeNow(0x10, body.toByteArray())

        val (type, ack) = readPacket(input)
        if (type and 0xF0 != 0x20 || ack.size < 2) throw IOException("réponse inattendue du broker")
        when (ack[1].toInt()) {
            0 -> {}
            4 -> throw IOException(tr("identifiant ou mot de passe refusé"))
            5 -> throw IOException(tr("accès non autorisé"))
            else -> throw IOException("connexion refusée (code ${ack[1]})")
        }
        connected = true
        lastError = null
        main.post { if (myGen == gen) listener.onMqttConnected() }

        var idle = 0
        while (running && myGen == gen) {
            val (t, data) = try { readPacket(input) } catch (e: SocketTimeoutException) {
                if (++idle >= 3) throw IOException(tr("le broker ne répond plus"))
                send(0xC0, ByteArray(0)) // PINGREQ
                continue
            }
            idle = 0
            if (t and 0xF0 == 0x30) handlePublish(t, data, myGen)
        }
    }

    private fun handlePublish(header: Int, data: ByteArray, myGen: Int) {
        val tl = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val topic = String(data, 2, tl, Charsets.UTF_8)
        var pos = 2 + tl
        val qos = (header shr 1) and 3
        if (qos > 0) {
            val idHi = data[pos]; val idLo = data[pos + 1]
            pos += 2
            if (qos == 1) send(0x40, byteArrayOf(idHi, idLo)) // PUBACK
        }
        val payload = String(data, pos, data.size - pos, Charsets.UTF_8)
        main.post { if (myGen == gen) listener.onMqttMessage(topic, payload) }
    }

    private fun send(type: Int, body: ByteArray) {
        writer.execute { try { writeNow(type, body) } catch (_: IOException) { try { socket?.close() } catch (_: IOException) {} } }
    }

    private fun writeNow(type: Int, body: ByteArray) {
        val o = out ?: throw IOException("non connecté")
        synchronized(this) {
            o.write(type)
            var len = body.size
            do {
                var b = len % 128
                len /= 128
                if (len > 0) b = b or 0x80
                o.write(b)
            } while (len > 0)
            o.write(body)
            o.flush()
        }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        var mult = 1
        var len = 0
        var b: Int
        do {
            b = input.readUnsignedByte()
            len += (b and 0x7F) * mult
            mult *= 128
        } while (b and 0x80 != 0 && mult <= 128 * 128 * 128)
        if (len > 1_000_000) throw IOException("message trop gros")
        val data = ByteArray(len)
        input.readFully(data)
        return type to data
    }

    private fun writeString(o: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write(b.size shr 8); o.write(b.size and 0xFF)
        o.write(b)
    }
}
